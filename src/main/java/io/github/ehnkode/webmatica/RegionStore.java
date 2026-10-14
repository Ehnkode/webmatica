package io.github.ehnkode.webmatica;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.storage.RegionFile;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;

/**
 * Receives chunks from the client and writes them to .mca files.
 *
 * Flow:
 * <ul>
 *   <li>Client thread: chunk loaded or changed -> create a {@link ChunkSnapshot}
 *       (copy) and hand it to the IO thread.</li>
 *   <li>IO thread: build NBT, compare against the last written state
 *       and only write via vanilla's {@link RegionFile} if changed.
 *       Vanilla manages the sectors itself, so the file doesn't grow
 *       on every rewrite.</li>
 * </ul>
 *
 * Changed chunks (building, breaking, block updates from the server) are first
 * collected and written after {@link #DIRTY_DELAY_MS} of quiet, so that e.g.
 * digging a tunnel doesn't trigger a write for every single block.
 */
public final class RegionStore {

    private static final RegionStore INSTANCE = new RegionStore();

    private static final long DIRTY_DELAY_MS = 1500L;
    private static final int MAX_OPEN_REGIONS = 32;

    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "webmatica-io");
        t.setDaemon(true);
        return t;
    });

    // --- client thread only ---
    /** Chunk key -> time of last change. */
    private final Map<Long, Long> dirty = new HashMap<>();
    private volatile boolean enabled = true;

    // --- IO thread only ---
    private final LinkedHashMap<Path, RegionFile> openRegions = new LinkedHashMap<>(16, 0.75f, true);
    /** "regionDir|chunkKey" -> CRC of the last written NBT. */
    private final Map<String, Long> lastWritten = new HashMap<>();
    /** Timeline: chunks whose old state has already been backed up (or checked) today. */
    private final Set<String> historyChecked = new HashSet<>();
    private String historyDay = "";

    private final AtomicInteger writtenCount = new AtomicInteger();

    private RegionStore() {}

    public static RegionStore getInstance() {
        return INSTANCE;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean on) {
        this.enabled = on;
        if (!on) {
            dirty.clear();
        }
    }

    /** Number of chunks actually written (changed) since game start. */
    public int getWrittenCount() {
        return writtenCount.get();
    }

    // ---------------------------------------------------------------- Client thread

    /**
     * Chunk received from the server. Only written after {@link #DIRTY_DELAY_MS}:
     * light arrives in the same packet but is only applied by the client afterwards.
     */
    public void onChunkLoad(ClientLevel world, LevelChunk chunk) {
        if (enabled) {
            dirty.put(Mc.pack(chunk.getPos()), System.currentTimeMillis());
        }
    }

    /** Chunk leaves view distance: save pending changes immediately. */
    public void onChunkUnload(ClientLevel world, LevelChunk chunk) {
        if (enabled && dirty.remove(Mc.pack(chunk.getPos())) != null) {
            submit(world, chunk);
        }
    }

    /** A block in this chunk has changed. */
    public void markDirty(int chunkX, int chunkZ) {
        if (enabled) {
            dirty.put(Mc.pack(chunkX, chunkZ), System.currentTimeMillis());
        }
    }

    /** Every client tick: write settled changes. */
    public void tick(Minecraft mc) {
        ClientLevel world = mc.level;
        if (world == null || dirty.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<Long, Long>> it = dirty.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Long, Long> e = it.next();
            if (now - e.getValue() < DIRTY_DELAY_MS) {
                continue;
            }
            it.remove();
            ChunkPos pos = Mc.unpack(e.getKey());
            // create=false returns null for missing chunks. world.getChunk(x, z)
            // would instead return an empty chunk, which would overwrite real data
            // with air.
            LevelChunk chunk = world.getChunkSource().getChunk(Mc.x(pos), Mc.z(pos), ChunkStatus.FULL, false);
            if (chunk != null) {
                submit(world, chunk);
            }
        }
    }

    /** Disconnected: write what is still pending, then close files. */
    public void onDisconnect() {
        onWorldChange(Minecraft.getInstance().level);
    }

    /**
     * Dimension changed or disconnected: pending chunks of {@code old} are written
     * right away (without the settle delay), then the files are closed. Without
     * {@code old} (already gone) the pending changes are dropped.
     */
    public void onWorldChange(ClientLevel old) {
        if (old != null && enabled) {
            flushAll(old);
        }
        dirty.clear();
        io.submit(this::closeAll);
    }

    private void flushAll(ClientLevel world) {
        for (long key : dirty.keySet()) {
            ChunkPos pos = Mc.unpack(key);
            LevelChunk chunk = world.getChunkSource().getChunk(Mc.x(pos), Mc.z(pos), ChunkStatus.FULL, false);
            if (chunk != null) {
                submit(world, chunk);
            }
        }
    }

    private void submit(ClientLevel world, LevelChunk chunk) {
        final ChunkSnapshot snap;
        final Path regionDir;
        final Path historyDir;
        final ResourceKey<Level> dim = world.dimension();
        try {
            snap = ChunkSnapshot.capture(chunk);
            Minecraft mc = Minecraft.getInstance();
            regionDir = RecordingTarget.regionDir(mc, dim);
            String sub = RecordingTarget.dimensionDir(dim);
            Path hist = RecordingTarget.root(mc).resolve(RecordingTarget.serverId(mc)).resolve(".history");
            historyDir = (sub.isEmpty() ? hist.resolve("%DAY%") : hist.resolve("%DAY%").resolve(sub)).resolve("region");
        } catch (Throwable t) {
            MapperClient.LOGGER.warn("[webmatica] Chunk-Kopie fehlgeschlagen ({})", chunk.getPos(), t);
            return;
        }
        io.submit(() -> write(regionDir, historyDir, dim, snap));
    }

    // ---------------------------------------------------------------- IO thread

    private void write(Path regionDir, Path historyDir, ResourceKey<Level> dim, ChunkSnapshot snap) {
        try {
            CompoundTag nbt = snap.toNbt();
            ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
            NbtIo.write(nbt, new DataOutputStream(buf));
            byte[] bytes = buf.toByteArray();

            CRC32 crc = new CRC32();
            crc.update(bytes);
            String key = regionDir + "|" + Mc.pack(snap.pos());
            Long prev = lastWritten.get(key);
            if (prev != null && prev == crc.getValue()) {
                return; // unchanged since the last write
            }

            RegionFile region = region(regionDir, dim, snap.pos().getRegionX(), snap.pos().getRegionZ());
            backupOld(region, historyDir, dim, snap, bytes);
            try (DataOutputStream out = region.getChunkDataOutputStream(snap.pos())) {
                out.write(bytes);
            }
            if (lastWritten.size() > 200_000) {
                lastWritten.clear();        // long sessions: at worst one extra write per chunk
            }
            lastWritten.put(key, crc.getValue());
            int n = writtenCount.incrementAndGet();
            if (n % 500 == 0) {
                MapperClient.LOGGER.info("[webmatica] {} Chunks geschrieben", n);
            }
        } catch (Throwable t) {
            MapperClient.LOGGER.warn("[webmatica] Schreiben fehlgeschlagen ({})", snap.pos(), t);
        }
    }

    /**
     * Timeline: before a chunk is overwritten with different content, back up the
     * old state once per day to .history/YYYY-MM-DD/. The viewer uses this to
     * compare "state on day X" with today.
     */
    private void backupOld(RegionFile region, Path historyDir, ResourceKey<Level> dim, ChunkSnapshot snap, byte[] bytes) {
        String day = LocalDate.now().toString();
        if (!day.equals(historyDay)) {
            historyChecked.clear();         // only today's entries matter
            historyDay = day;
        }
        String key = historyDir + "|" + day + "|" + Mc.pack(snap.pos());
        if (historyChecked.contains(key)) {
            return;
        }
        try {
            byte[] old;
            try (java.io.DataInputStream in = region.getChunkDataInputStream(snap.pos())) {
                if (in == null) {
                    historyChecked.add(key);
                    return;     // new chunk, nothing to back up
                }
                old = in.readAllBytes();
            }
            if (Arrays.equals(old, bytes)) {
                return;         // same state (e.g. reloaded): check again on a real change
            }
            historyChecked.add(key);
            Path dir = Path.of(historyDir.toString().replace("%DAY%", day));
            Files.createDirectories(dir);
            Path file = dir.resolve("r." + snap.pos().getRegionX() + "." + snap.pos().getRegionZ() + ".mca");
            RegionFile h = new RegionFile(new RegionStorageInfo("webmatica-history", dim, "chunk"), file, dir, false);
            try {
                if (!h.hasChunk(snap.pos())) {
                    try (DataOutputStream out = h.getChunkDataOutputStream(snap.pos())) {
                        out.write(old);
                    }
                }
            } finally {
                h.close();
            }
        } catch (Throwable t) {
            MapperClient.LOGGER.debug("[webmatica] Zeitleiste: Sichern fehlgeschlagen ({})", snap.pos(), t);
        }
    }

    private RegionFile region(Path regionDir, ResourceKey<Level> dim, int rx, int rz) throws Exception {
        Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
        RegionFile rf = openRegions.get(file);
        if (rf != null) {
            return rf;
        }
        Files.createDirectories(regionDir);
        rf = new RegionFile(new RegionStorageInfo("webmatica", dim, "chunk"), file, regionDir, false);
        openRegions.put(file, rf);
        if (openRegions.size() > MAX_OPEN_REGIONS) {
            Iterator<Map.Entry<Path, RegionFile>> it = openRegions.entrySet().iterator();
            Map.Entry<Path, RegionFile> eldest = it.next();
            it.remove();
            closeQuietly(eldest.getValue());
        }
        return rf;
    }

    private void closeAll() {
        for (RegionFile rf : openRegions.values()) {
            closeQuietly(rf);
        }
        openRegions.clear();
    }

    private static void closeQuietly(RegionFile rf) {
        try {
            rf.close();
        } catch (Throwable t) {
            MapperClient.LOGGER.warn("[webmatica] Region-Datei schliessen fehlgeschlagen", t);
        }
    }

    /** On game exit: write pending chunks, drain the queue, then close. */
    public void shutdown() {
        ClientLevel world = Minecraft.getInstance().level;
        if (world != null && enabled) {
            flushAll(world);
        }
        dirty.clear();
        io.submit(this::closeAll);
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

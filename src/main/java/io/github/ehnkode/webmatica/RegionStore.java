package io.github.ehnkode.webmatica;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtIo;
import net.minecraft.registry.RegistryKey;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.world.storage.RegionFile;
import net.minecraft.world.storage.StorageKey;

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
 * Nimmt Chunks vom Client entgegen und schreibt sie in .mca-Dateien.
 *
 * Ablauf:
 * <ul>
 *   <li>Client-Thread: Chunk geladen oder geaendert -> {@link ChunkSnapshot}
 *       erstellen (Kopie) und an den IO-Thread geben.</li>
 *   <li>IO-Thread: NBT bauen, gegen den zuletzt geschriebenen Stand vergleichen
 *       und nur bei Aenderung ueber Vanillas {@link RegionFile} schreiben.
 *       Vanilla verwaltet die Sektoren selbst, die Datei waechst also nicht
 *       bei jedem Neuschreiben.</li>
 * </ul>
 *
 * Geaenderte Chunks (Bauen, Abbauen, Block-Updates vom Server) werden erst
 * gesammelt und nach {@link #DIRTY_DELAY_MS} Ruhe geschrieben, damit z. B.
 * beim Tunnelgraben nicht jeder einzelne Block einen Schreibvorgang ausloest.
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

    // --- nur Client-Thread ---
    /** Chunk-Key -> Zeitpunkt der letzten Aenderung. */
    private final Map<Long, Long> dirty = new HashMap<>();
    private volatile boolean enabled = true;

    // --- nur IO-Thread ---
    private final LinkedHashMap<Path, RegionFile> openRegions = new LinkedHashMap<>(16, 0.75f, true);
    /** "regionDir|chunkKey" -> CRC des zuletzt geschriebenen NBT. */
    private final Map<String, Long> lastWritten = new HashMap<>();
    /** Zeitleiste: Chunks, deren alter Stand heute schon gesichert (oder geprueft) ist. */
    private final Set<String> historyChecked = new HashSet<>();

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

    /** Anzahl tatsaechlich geschriebener (geaenderter) Chunks seit Spielstart. */
    public int getWrittenCount() {
        return writtenCount.get();
    }

    // ---------------------------------------------------------------- Client-Thread

    /** Chunk vom Server empfangen. */
    public void onChunkLoad(ClientWorld world, WorldChunk chunk) {
        if (!enabled) {
            return;
        }
        dirty.remove(chunk.getPos().toLong());
        submit(world, chunk);
    }

    /** Chunk verlaesst die Sichtweite: offene Aenderungen sofort sichern. */
    public void onChunkUnload(ClientWorld world, WorldChunk chunk) {
        if (enabled && dirty.remove(chunk.getPos().toLong()) != null) {
            submit(world, chunk);
        }
    }

    /** Ein Block in diesem Chunk hat sich geaendert. */
    public void markDirty(int chunkX, int chunkZ) {
        if (enabled) {
            dirty.put(ChunkPos.toLong(chunkX, chunkZ), System.currentTimeMillis());
        }
    }

    /** Jeden Client-Tick: beruhigte Aenderungen schreiben. */
    public void tick(MinecraftClient mc) {
        ClientWorld world = mc.world;
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
            ChunkPos pos = new ChunkPos(e.getKey());
            // create=false liefert null fuer fehlende Chunks. world.getChunk(x, z)
            // gaebe stattdessen einen leeren Chunk zurueck, der echte Daten mit
            // Luft ueberschreiben wuerde.
            WorldChunk chunk = world.getChunkManager().getChunk(pos.x, pos.z, ChunkStatus.FULL, false);
            if (chunk != null) {
                submit(world, chunk);
            }
        }
    }

    /** Verbindung getrennt oder Dimension gewechselt: Dateien schliessen. */
    public void onDisconnect() {
        dirty.clear();
        io.submit(this::closeAll);
    }

    private void submit(ClientWorld world, WorldChunk chunk) {
        final ChunkSnapshot snap;
        final Path regionDir;
        final Path historyDir;
        final RegistryKey<World> dim = world.getRegistryKey();
        try {
            snap = ChunkSnapshot.capture(chunk);
            MinecraftClient mc = MinecraftClient.getInstance();
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

    // ---------------------------------------------------------------- IO-Thread

    private void write(Path regionDir, Path historyDir, RegistryKey<World> dim, ChunkSnapshot snap) {
        try {
            NbtCompound nbt = snap.toNbt();
            ByteArrayOutputStream buf = new ByteArrayOutputStream(64 * 1024);
            NbtIo.writeCompound(nbt, new DataOutputStream(buf));
            byte[] bytes = buf.toByteArray();

            CRC32 crc = new CRC32();
            crc.update(bytes);
            String key = regionDir + "|" + snap.pos().toLong();
            Long prev = lastWritten.get(key);
            if (prev != null && prev == crc.getValue()) {
                return; // unveraendert seit dem letzten Schreiben
            }

            RegionFile region = region(regionDir, dim, snap.pos().getRegionX(), snap.pos().getRegionZ());
            backupOld(region, historyDir, dim, snap, bytes);
            try (DataOutputStream out = region.getChunkOutputStream(snap.pos())) {
                out.write(bytes);
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
     * Zeitleiste: bevor ein Chunk mit anderem Inhalt ueberschrieben wird, den alten
     * Stand einmal pro Tag nach .history/JJJJ-MM-TT/ sichern. Der Viewer vergleicht
     * damit "Stand an Tag X" mit heute.
     */
    private void backupOld(RegionFile region, Path historyDir, RegistryKey<World> dim, ChunkSnapshot snap, byte[] bytes) {
        String day = LocalDate.now().toString();
        String key = historyDir + "|" + day + "|" + snap.pos().toLong();
        if (historyChecked.contains(key)) {
            return;
        }
        try {
            byte[] old;
            try (java.io.DataInputStream in = region.getChunkInputStream(snap.pos())) {
                if (in == null) {
                    historyChecked.add(key);
                    return;     // neuer Chunk, nichts zu sichern
                }
                old = in.readAllBytes();
            }
            if (Arrays.equals(old, bytes)) {
                return;         // gleicher Stand (z. B. neu geladen): bei echter Aenderung erneut pruefen
            }
            historyChecked.add(key);
            Path dir = Path.of(historyDir.toString().replace("%DAY%", day));
            Files.createDirectories(dir);
            Path file = dir.resolve("r." + snap.pos().getRegionX() + "." + snap.pos().getRegionZ() + ".mca");
            RegionFile h = new RegionFile(new StorageKey("webmatica-history", dim, "chunk"), file, dir, false);
            try {
                if (!h.hasChunk(snap.pos())) {
                    try (DataOutputStream out = h.getChunkOutputStream(snap.pos())) {
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

    private RegionFile region(Path regionDir, RegistryKey<World> dim, int rx, int rz) throws Exception {
        Path file = regionDir.resolve("r." + rx + "." + rz + ".mca");
        RegionFile rf = openRegions.get(file);
        if (rf != null) {
            return rf;
        }
        Files.createDirectories(regionDir);
        rf = new RegionFile(new StorageKey("webmatica", dim, "chunk"), file, regionDir, false);
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

    /** Beim Beenden des Spiels: Warteschlange abarbeiten, dann schliessen. */
    public void shutdown() {
        io.submit(this::closeAll);
        io.shutdown();
        try {
            io.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}

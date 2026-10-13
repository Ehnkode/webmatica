package io.github.ehnkode.webmatica;

import net.minecraft.SharedConstants;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.ChunkSectionPos;
import net.minecraft.world.LightType;
import net.minecraft.world.chunk.ChunkNibbleArray;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.light.LightingProvider;
import net.minecraft.world.chunk.PalettesFactory;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Immutable copy of a client chunk.
 *
 * {@link #capture} runs on the client thread and only copies the sections
 * and block entities (cheap). {@link #toNbt} then runs on the IO thread
 * and no longer touches the live chunk, so the game may keep modifying
 * it at the same time.
 *
 * Light (block and sky light, as sent by the server) is included so the
 * viewer can show exactly where mobs can spawn.
 */
public final class ChunkSnapshot {

    private final ChunkPos pos;
    private final int bottomSectionY;
    private final ChunkSection[] sections;
    private final List<NbtCompound> blockEntities;
    private final PalettesFactory palettes;
    private final byte[][] blockLight;
    private final byte[][] skyLight;

    private ChunkSnapshot(ChunkPos pos, int bottomSectionY, ChunkSection[] sections,
                          List<NbtCompound> blockEntities, PalettesFactory palettes,
                          byte[][] blockLight, byte[][] skyLight) {
        this.pos = pos;
        this.bottomSectionY = bottomSectionY;
        this.sections = sections;
        this.blockEntities = blockEntities;
        this.palettes = palettes;
        this.blockLight = blockLight;
        this.skyLight = skyLight;
    }

    public ChunkPos pos() {
        return pos;
    }

    /** Must be called on the client thread. */
    public static ChunkSnapshot capture(WorldChunk chunk) {
        DynamicRegistryManager registries = chunk.getWorld().getRegistryManager();
        ChunkSection[] live = chunk.getSectionArray();
        ChunkSection[] copy = new ChunkSection[live.length];
        for (int i = 0; i < live.length; i++) {
            copy[i] = live[i] == null ? null : live[i].copy();
        }
        // Block entities: sign texts, banners, heads, spawner type ... whatever the
        // server sends to the client. Chest contents are not included.
        List<NbtCompound> bes = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            try {
                bes.add(be.createNbtWithIdentifyingData(registries));
            } catch (Throwable t) {
                // Skip a single broken block entity, keep saving the rest.
            }
        }
        LightingProvider light = chunk.getWorld().getLightingProvider();
        byte[][] block = new byte[live.length][];
        byte[][] sky = new byte[live.length][];
        for (int i = 0; i < live.length; i++) {
            ChunkSectionPos sp = ChunkSectionPos.from(chunk.getPos(), chunk.getBottomSectionCoord() + i);
            block[i] = lightBytes(light, LightType.BLOCK, sp);
            sky[i] = lightBytes(light, LightType.SKY, sp);
        }
        return new ChunkSnapshot(chunk.getPos(), chunk.getBottomSectionCoord(), copy, bes,
                PalettesFactory.fromRegistryManager(registries), block, sky);
    }

    /** Light values of a section (2048 bytes, 4 bits per block) or null if the client has none. */
    private static byte[] lightBytes(LightingProvider light, LightType type, ChunkSectionPos sp) {
        try {
            ChunkNibbleArray arr = light.get(type).getLightSection(sp);
            // copy(): an "empty" section only carries a default value (e.g. 0 in the
            // dark); asByteArray() on the copy fills it in without touching the original.
            return arr == null ? null : arr.copy().asByteArray();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Vanilla chunk NBT (like SerializedChunk), readable by Amulet, MCA Selector, BlueMap. */
    public NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        root.putInt("DataVersion", SharedConstants.getGameVersion().dataVersion().id());
        root.putInt("xPos", pos.x);
        root.putInt("yPos", bottomSectionY);
        root.putInt("zPos", pos.z);
        root.putString("Status", "minecraft:full");
        root.putLong("LastUpdate", 0L);
        root.putLong("InhabitedTime", 0L);
        root.putBoolean("isLightOn", true);

        NbtList sectionList = new NbtList();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null) {
                continue;
            }
            NbtCompound sec = new NbtCompound();
            sec.putByte("Y", (byte) (bottomSectionY + i));
            palettes.blockStatesContainerCodec()
                    .encodeStart(NbtOps.INSTANCE, section.getBlockStateContainer())
                    .result().ifPresent(nbt -> sec.put("block_states", nbt));
            palettes.biomeContainerCodec()
                    .encodeStart(NbtOps.INSTANCE, section.getBiomeContainer())
                    .result().ifPresent(nbt -> sec.put("biomes", nbt));
            if (blockLight[i] != null) {
                sec.putByteArray("BlockLight", blockLight[i]);
            }
            if (skyLight[i] != null) {
                sec.putByteArray("SkyLight", skyLight[i]);
            }
            sectionList.add(sec);
        }
        root.put("sections", sectionList);

        NbtList beList = new NbtList();
        beList.addAll(blockEntities);
        root.put("block_entities", beList);
        return root;
    }
}

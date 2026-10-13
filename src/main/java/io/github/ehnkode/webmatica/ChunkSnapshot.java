package io.github.ehnkode.webmatica;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.SectionPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import net.minecraft.world.level.lighting.LevelLightEngine;
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
    private final LevelChunkSection[] sections;
    private final List<CompoundTag> blockEntities;
    private final PalettedContainerFactory palettes;
    private final byte[][] blockLight;
    private final byte[][] skyLight;

    private ChunkSnapshot(ChunkPos pos, int bottomSectionY, LevelChunkSection[] sections,
                          List<CompoundTag> blockEntities, PalettedContainerFactory palettes,
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
    public static ChunkSnapshot capture(LevelChunk chunk) {
        RegistryAccess registries = chunk.getLevel().registryAccess();
        LevelChunkSection[] live = chunk.getSections();
        LevelChunkSection[] copy = new LevelChunkSection[live.length];
        for (int i = 0; i < live.length; i++) {
            copy[i] = live[i] == null ? null : live[i].copy();
        }
        // Block entities: sign texts, banners, heads, spawner type ... whatever the
        // server sends to the client. Chest contents are not included.
        List<CompoundTag> bes = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            try {
                bes.add(be.saveWithFullMetadata(registries));
            } catch (Throwable t) {
                // Skip a single broken block entity, keep saving the rest.
            }
        }
        LevelLightEngine light = chunk.getLevel().getLightEngine();
        byte[][] block = new byte[live.length][];
        byte[][] sky = new byte[live.length][];
        for (int i = 0; i < live.length; i++) {
            SectionPos sp = SectionPos.of(chunk.getPos(), chunk.getMinSectionY() + i);
            block[i] = lightBytes(light, LightLayer.BLOCK, sp);
            sky[i] = lightBytes(light, LightLayer.SKY, sp);
        }
        return new ChunkSnapshot(chunk.getPos(), chunk.getMinSectionY(), copy, bes,
                PalettedContainerFactory.create(registries), block, sky);
    }

    /** Light values of a section (2048 bytes, 4 bits per block) or null if the client has none. */
    private static byte[] lightBytes(LevelLightEngine light, LightLayer type, SectionPos sp) {
        try {
            DataLayer arr = light.getLayerListener(type).getDataLayerData(sp);
            // copy(): an "empty" section only carries a default value (e.g. 0 in the
            // dark); asByteArray() on the copy fills it in without touching the original.
            return arr == null ? null : arr.copy().getData();
        } catch (Throwable t) {
            return null;
        }
    }

    /** Vanilla chunk NBT (like SerializedChunk), readable by Amulet, MCA Selector, BlueMap. */
    public CompoundTag toNbt() {
        CompoundTag root = new CompoundTag();
        root.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        root.putInt("xPos", pos.x);
        root.putInt("yPos", bottomSectionY);
        root.putInt("zPos", pos.z);
        root.putString("Status", "minecraft:full");
        root.putLong("LastUpdate", 0L);
        root.putLong("InhabitedTime", 0L);
        root.putBoolean("isLightOn", true);

        ListTag sectionList = new ListTag();
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (section == null) {
                continue;
            }
            CompoundTag sec = new CompoundTag();
            sec.putByte("Y", (byte) (bottomSectionY + i));
            palettes.blockStatesContainerCodec()
                    .encodeStart(NbtOps.INSTANCE, section.getStates())
                    .result().ifPresent(nbt -> sec.put("block_states", nbt));
            palettes.biomeContainerCodec()
                    .encodeStart(NbtOps.INSTANCE, section.getBiomes())
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

        ListTag beList = new ListTag();
        beList.addAll(blockEntities);
        root.put("block_entities", beList);
        return root;
    }
}

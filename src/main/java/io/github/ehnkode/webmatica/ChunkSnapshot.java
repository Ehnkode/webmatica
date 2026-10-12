package io.github.ehnkode.webmatica;

import net.minecraft.SharedConstants;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.PalettesFactory;
import net.minecraft.world.chunk.WorldChunk;

import java.util.ArrayList;
import java.util.List;

/**
 * Unveraenderliche Kopie eines Client-Chunks.
 *
 * {@link #capture} laeuft auf dem Client-Thread und kopiert nur die Sektionen
 * und Block-Entities (billig). {@link #toNbt} laeuft danach auf dem IO-Thread
 * und fasst den lebenden Chunk nicht mehr an, sodass das Spiel ihn
 * gleichzeitig weiter veraendern darf.
 */
public final class ChunkSnapshot {

    private final ChunkPos pos;
    private final int bottomSectionY;
    private final ChunkSection[] sections;
    private final List<NbtCompound> blockEntities;
    private final PalettesFactory palettes;

    private ChunkSnapshot(ChunkPos pos, int bottomSectionY, ChunkSection[] sections,
                          List<NbtCompound> blockEntities, PalettesFactory palettes) {
        this.pos = pos;
        this.bottomSectionY = bottomSectionY;
        this.sections = sections;
        this.blockEntities = blockEntities;
        this.palettes = palettes;
    }

    public ChunkPos pos() {
        return pos;
    }

    /** Muss auf dem Client-Thread aufgerufen werden. */
    public static ChunkSnapshot capture(WorldChunk chunk) {
        DynamicRegistryManager registries = chunk.getWorld().getRegistryManager();
        ChunkSection[] live = chunk.getSectionArray();
        ChunkSection[] copy = new ChunkSection[live.length];
        for (int i = 0; i < live.length; i++) {
            copy[i] = live[i] == null ? null : live[i].copy();
        }
        // Block-Entities: Schildtexte, Banner, Koepfe, Spawner-Typ ... was der
        // Server dem Client eben mitschickt. Truheninhalte kommen nicht mit.
        List<NbtCompound> bes = new ArrayList<>();
        for (BlockEntity be : chunk.getBlockEntities().values()) {
            try {
                bes.add(be.createNbtWithIdentifyingData(registries));
            } catch (Throwable t) {
                // Einzelne kaputte Block-Entity ueberspringen, Rest weiter speichern.
            }
        }
        return new ChunkSnapshot(chunk.getPos(), chunk.getBottomSectionCoord(), copy, bes,
                PalettesFactory.fromRegistryManager(registries));
    }

    /** Vanilla-Chunk-NBT (wie SerializedChunk), lesbar von Amulet, MCA Selector, BlueMap. */
    public NbtCompound toNbt() {
        NbtCompound root = new NbtCompound();
        root.putInt("DataVersion", SharedConstants.getGameVersion().dataVersion().id());
        root.putInt("xPos", pos.x);
        root.putInt("yPos", bottomSectionY);
        root.putInt("zPos", pos.z);
        root.putString("Status", "minecraft:full");
        root.putLong("LastUpdate", 0L);
        root.putLong("InhabitedTime", 0L);
        root.putBoolean("isLightOn", false);

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
            sectionList.add(sec);
        }
        root.put("sections", sectionList);

        NbtList beList = new NbtList();
        beList.addAll(blockEntities);
        root.put("block_entities", beList);
        return root;
    }
}

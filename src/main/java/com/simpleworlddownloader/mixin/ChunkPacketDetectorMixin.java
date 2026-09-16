package com.simpleworlddownloader.mixin;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.HashSet;
import java.util.Set;

/**
 * Detects only obviously generated stone-noise chunks and does not flag normal terrain.
 */
@Mixin(ClientWorld.class)
public abstract class ChunkPacketDetectorMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("SWD-ChunkDetector");

    // Only treat a chunk as noise if it is effectively a uniform stone blanket.
    // This avoids false positives on normal caves, stone biomes, and regular terrain.
    private static final double STONE_NOISE_RATIO = 0.95D;
    private static final int MIN_NON_AIR_BLOCKS = 512;
    private static final int MAX_UNIQUE_BLOCK_TYPES = 4;

    @Inject(method = "addChunk", at = @At("TAIL"))
    private void onChunkAdded(WorldChunk chunk, CallbackInfo ci) {
        try {
            if (isStoneNoiseChunk(chunk)) {
                LOGGER.warn("[SWD] Detected stone-noise chunk {} - excluding from visual pass", chunk.getPos());
            }
        } catch (Exception e) {
            LOGGER.debug("Error analyzing chunk on add", e);
        }
    }

    private boolean isStoneNoiseChunk(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        int totalNonAir = 0;
        int stoneLike = 0;
        Set<Block> uniqueBlocks = new HashSet<>();

        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = chunk.getBottomY(); y < chunk.getTopY(); y++) {
                    var state = chunk.getBlockState(pos.getStartX() + x, y, pos.getStartZ() + z);
                    if (state.isAir()) {
                        continue;
                    }

                    totalNonAir++;
                    Block block = state.getBlock();
                    uniqueBlocks.add(block);

                    if (isNoiseBlock(block)) {
                        stoneLike++;
                    }
                }
            }
        }

        if (totalNonAir < MIN_NON_AIR_BLOCKS) {
            return false;
        }

        double ratio = (double) stoneLike / totalNonAir;
        boolean uniform = uniqueBlocks.size() <= MAX_UNIQUE_BLOCK_TYPES;

        // A normal terrain chunk is usually not almost entirely stone, and it has more varied blocks.
        return ratio >= STONE_NOISE_RATIO && uniform;
    }

    private boolean isNoiseBlock(Block block) {
        return block == Blocks.STONE
            || block == Blocks.COBBLESTONE
            || block == Blocks.DEEPSLATE
            || block == Blocks.GRANITE
            || block == Blocks.DIORITE
            || block == Blocks.ANDESITE
            || block == Blocks.GRAVEL;
    }
}

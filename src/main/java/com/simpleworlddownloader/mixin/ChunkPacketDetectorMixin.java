package com.simpleworlddownloader.mixin;

import net.minecraft.client.world.ClientWorld;
import net.minecraft.world.chunk.WorldChunk;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.block.Blocks;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Supplementary mixin for ClientWorld chunk loading events.
 * Monitors chunks as they are loaded/unloaded to detect obfuscation patterns
 * and cache original block data when possible.
 * 
 * Yarn mappings for 1.21.1:
 * - ClientWorld.addChunk()
 * - ClientWorld.removeChunk()
 */
@Mixin(ClientWorld.class)
public abstract class ChunkPacketDetectorMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("SWD-ChunkDetector");
    
    private static final int OBFUSCATION_THRESHOLD_PERCENT = 80;

    /**
     * Monitor chunk additions to detect obfuscation at load time.
     */
    @Inject(
        method = "addChunk",
        at = @At("TAIL")
    )
    private void onChunkAdded(WorldChunk chunk, CallbackInfo ci) {
        try {
            analyzeChunkObfuscation(chunk);
        } catch (Exception e) {
            LOGGER.debug("Error analyzing chunk on add", e);
        }
    }

    /**
     * Analyze a chunk for obfuscation patterns.
     * Logs warnings if high stone density is detected.
     */
    private void analyzeChunkObfuscation(WorldChunk chunk) {
        ChunkPos pos = chunk.getPos();
        
        int totalBlocks = 0;
        int stoneBlocks = 0;
        
        // Scan all blocks in the chunk
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                for (int y = chunk.getBottomY(); y < chunk.getTopY(); y++) {
                    var blockState = chunk.getBlockState(
                        pos.getStartX() + x,
                        y,
                        pos.getStartZ() + z
                    );
                    
                    // Skip air blocks
                    if (blockState.isAir()) {
                        continue;
                    }
                    
                    totalBlocks++;
                    
                    // Count stone variants
                    if (isObfuscationBlock(blockState.getBlock())) {
                        stoneBlocks++;
                    }
                }
            }
        }
        
        // Calculate density
        if (totalBlocks > 0) {
            double density = (double) stoneBlocks / totalBlocks * 100;
            
            if (density >= OBFUSCATION_THRESHOLD_PERCENT) {
                LOGGER.warn(
                    "[SWD] Chunk {} loaded with suspicious {}% stone density",
                    pos,
                    String.format("%.1f", density)
                );
                logChunkDetails(chunk, pos);
            }
        }
    }

    /**
     * Identifies blocks commonly used for obfuscation.
     */
    private boolean isObfuscationBlock(var block) {
        return block == Blocks.STONE ||
               block == Blocks.COBBLESTONE ||
               block == Blocks.DEEPSLATE ||
               block == Blocks.GRANITE ||
               block == Blocks.DIORITE ||
               block == Blocks.ANDESITE ||
               block == Blocks.GRAVEL;
    }

    /**
     * Logs detailed chunk information for debugging.
     */
    private void logChunkDetails(WorldChunk chunk, ChunkPos pos) {
        int minY = chunk.getBottomY();
        int maxY = chunk.getTopY();
        
        LOGGER.info(
            "[SWD] Chunk {} - Y range: {} to {} (height: {})",
            pos,
            minY,
            maxY,
            maxY - minY
        );
        
        // Sample a few block types for logging
        var sampleBlockState = chunk.getBlockState(
            pos.getCenterX(),
            (minY + maxY) / 2,
            pos.getCenterZ()
        );
        
        LOGGER.debug(
            "[SWD] Sample block at chunk center: {}",
            sampleBlockState.getBlock().toString()
        );
    }
}
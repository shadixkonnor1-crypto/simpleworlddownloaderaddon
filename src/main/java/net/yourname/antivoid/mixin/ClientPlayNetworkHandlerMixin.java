package net.yourname.antivoid.mixin;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDataS2CPacket;
import net.minecraft.world.level.chunk.ChunkSection;
import net.minecraft.world.level.chunk.ChunkData;
import net.minecraft.block.Blocks;
import net.minecraft.block.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Mixin for ClientPlayNetworkHandler to intercept chunk and block update packets.
 *
 * This mixin targets:
 * - onChunkData(ChunkDataS2CPacket packet) - Chunk data packets from server
 * - onBlockUpdate(BlockUpdateS2CPacket packet) - Individual block update packets
 *
 * Packet interception is performed at the HEAD of each method with cancellation support.
 * All logic includes null-safety checks to prevent crashes in edge cases.
 *
 * Yarn mappings for Minecraft 1.21.11:
 * - ClientPlayNetworkHandler.onChunkData()
 * - ClientPlayNetworkHandler.onBlockUpdate()
 * - MinecraftClient.getInstance()
 * - MinecraftClient.world
 */
@Mixin(ClientPlayNetworkHandler.class)
public abstract class ClientPlayNetworkHandlerMixin {
    private static final Logger LOGGER = LoggerFactory.getLogger("AntiVoid-Mixin");

    /**
     * Intercepts incoming ChunkDataS2CPacket at the HEAD of onChunkData.
     *
     * If the chunk is detected as scrambled/obfuscated, cancels the packet processing
     * to prevent the corrupted data from being applied to the client world.
     *
     * @param packet The ChunkDataS2CPacket being processed
     * @param ci Callback info for cancellation
     */
    @Inject(
        method = "onChunkData",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onChunkDataInterception(ChunkDataS2CPacket packet, CallbackInfo ci) {
        // Safety check: ensure world is loaded before processing
        if (MinecraftClient.getInstance().world == null) {
            return;
        }

        try {
            // Check if this chunk exhibits scrambling/obfuscation patterns
            if (isScrambledChunk(packet)) {
                LOGGER.warn(
                    "[AntiVoid] Detected scrambled chunk at X={}, Z={} - packet cancelled",
                    packet.getX(),
                    packet.getZ()
                );
                // Cancel the packet to prevent corrupted data from rendering
                ci.cancel();
            }
        } catch (Exception e) {
            LOGGER.debug("[AntiVoid] Exception during chunk interception", e);
        }
    }

    /**
     * Intercepts incoming BlockUpdateS2CPacket at the HEAD of onBlockUpdate.
     *
     * If the block update is detected as suspicious, cancels the packet processing
     * to prevent the corrupted block state from being applied to the client world.
     *
     * @param packet The BlockUpdateS2CPacket being processed
     * @param ci Callback info for cancellation
     */
    @Inject(
        method = "onBlockUpdate",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onBlockUpdateInterception(BlockUpdateS2CPacket packet, CallbackInfo ci) {
        // Safety check: ensure world is loaded before processing
        if (MinecraftClient.getInstance().world == null) {
            return;
        }

        try {
            // Check if this block update is suspicious/scrambled
            if (isScrambledBlockUpdate(packet)) {
                LOGGER.debug(
                    "[AntiVoid] Detected suspicious block update at pos {} - packet cancelled",
                    packet.getPos()
                );
                // Cancel the packet to prevent corrupted block state from rendering
                ci.cancel();
            }
        } catch (Exception e) {
            LOGGER.debug("[AntiVoid] Exception during block update interception", e);
        }
    }

    /**
     * Analyzes a ChunkDataS2CPacket to determine if its contents are scrambled/obfuscated.
     *
     * This implementation performs a passive, mapping-safe scan of the packet's chunk
     * section data. It detects the anti-WDL "loose floating stone" pattern by:
     *  - Sampling block states in the sections present in the packet
     *  - Counting stone/cobble samples and checking 6-direction adjacency
     *  - If stone samples are common but predominantly isolated (surrounded by air),
     *    the chunk is flagged as scrambled/obfuscated
     *
     * The method is completely passive (no network sends) and defensive against mapping
     * differences: on unexpected structure it returns false.
     *
     * @param packet The ChunkDataS2CPacket to analyze
     * @return true if the chunk is detected as scrambled, false otherwise
     */
    private boolean isScrambledChunk(ChunkDataS2CPacket packet) {
        if (packet == null) return false;

        // Heuristics / tuning parameters
        final int SAMPLE_STEP = 3;            // sample every N blocks per axis
        final int MIN_STONE_SAMPLES = 10;     // at least this many stone samples required
        final double MAX_DENSITY = 0.06;      // stone/sample density considered "low"
        final double ISOLATION_RATIO = 0.70;  // fraction of stone samples that must be isolated

        try {
            // Attempt to get ChunkData object from the packet using common Yarn accessor names.
            Object rawChunkData = null;
            try {
                rawChunkData = packet.getChunkData(); // common name in some mappings
            } catch (NoSuchMethodError | AbstractMethodError ignore) {
                try {
                    rawChunkData = packet.getData();
                } catch (NoSuchMethodError | AbstractMethodError ignore2) {
                    // reflection fallback
                    try {
                        java.lang.reflect.Method m = packet.getClass().getDeclaredMethod("getChunkData");
                        m.setAccessible(true);
                        rawChunkData = m.invoke(packet);
                    } catch (Throwable t) {
                        try {
                            java.lang.reflect.Method m2 = packet.getClass().getDeclaredMethod("getData");
                            m2.setAccessible(true);
                            rawChunkData = m2.invoke(packet);
                        } catch (Throwable t2) {
                            // Could not obtain chunk data -- fail safe.
                            return false;
                        }
                    }
                }
            }

            if (rawChunkData == null) return false;

            // Prefer direct ChunkData type if available
            ChunkData chunkData = null;
            if (rawChunkData instanceof ChunkData) {
                chunkData = (ChunkData) rawChunkData;
            } else {
                // Try to cast via known class name if mappings differ
                if (rawChunkData.getClass().getName().endsWith("ChunkData")) {
                    chunkData = (ChunkData) rawChunkData;
                } else {
                    // Unknown structure; don't attempt unsafe access
                    return false;
                }
            }

            // Retrieve sections; getSections() may return ChunkSection[] or List<ChunkSection>
            ChunkSection[] sectionsArray = null;
            try {
                Object secs = chunkData.getSections();
                if (secs == null) return false;
                if (secs instanceof ChunkSection[]) {
                    sectionsArray = (ChunkSection[]) secs;
                } else if (secs instanceof java.util.List) {
                    java.util.List<?> list = (java.util.List<?>) secs;
                    sectionsArray = list.toArray(new ChunkSection[0]);
                } else {
                    return false;
                }
            } catch (NoSuchMethodError | AbstractMethodError e) {
                // Method not present in mapping
                return false;
            }

            if (sectionsArray == null || sectionsArray.length == 0) return false;

            int totalSamples = 0;
            int stoneSamples = 0;
            int isolatedStoneSamples = 0;

            // Iterate over sections and sample block states
            for (int s = 0; s < sectionsArray.length; s++) {
                ChunkSection section = sectionsArray[s];
                if (section == null) continue;

                for (int lx = 0; lx < 16; lx += SAMPLE_STEP) {
                    for (int ly = 0; ly < 16; ly += SAMPLE_STEP) {
                        for (int lz = 0; lz < 16; lz += SAMPLE_STEP) {
                            totalSamples++;
                            BlockState state;
                            try {
                                state = section.getBlockState(lx, ly, lz);
                            } catch (NoSuchMethodError | AbstractMethodError ex) {
                                // Mapping mismatch for getBlockState; bail out
                                return false;
                            } catch (Throwable t) {
                                continue;
                            }

                            if (state == null) continue;

                            if (!(state.isOf(Blocks.STONE) || state.isOf(Blocks.COBBLESTONE))) {
                                continue;
                            }

                            stoneSamples++;

                            // Check 6-direction neighbors for non-air blocks
                            int nonAirNeighbors = 0;
                            final int[][] OFFS = new int[][]{{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

                            for (int[] d : OFFS) {
                                int nx = lx + d[0];
                                int ny = ly + d[1];
                                int nz = lz + d[2];
                                int neighborSectionIdx = s;
                                int neighborLocalY = ny;

                                if (ny < 0) {
                                    neighborSectionIdx = s - 1;
                                    neighborLocalY = ny + 16;
                                } else if (ny >= 16) {
                                    neighborSectionIdx = s + 1;
                                    neighborLocalY = ny - 16;
                                }

                                // If neighbor outside X/Z bounds, treat as air for isolation purposes
                                if (nx < 0 || nx >= 16 || nz < 0 || nz >= 16) {
                                    continue;
                                }

                                if (neighborSectionIdx < 0 || neighborSectionIdx >= sectionsArray.length) {
                                    continue;
                                }

                                ChunkSection neighSection = sectionsArray[neighborSectionIdx];
                                if (neighSection == null) continue;

                                try {
                                    BlockState nstate = neighSection.getBlockState(nx, neighborLocalY, nz);
                                    if (nstate != null && !nstate.isAir()) nonAirNeighbors++;
                                } catch (Throwable t) {
                                    // treat as air on error
                                }
                            }

                            if (nonAirNeighbors == 0) isolatedStoneSamples++;
                        }
                    }
                }
            }

            if (stoneSamples < MIN_STONE_SAMPLES) return false;

            double isolationRatio = (double) isolatedStoneSamples / (double) stoneSamples;
            double density = (double) stoneSamples / (double) Math.max(1, totalSamples);

            // If many of the stone samples are isolated and overall density is low -> obfuscation
            if (isolationRatio >= ISOLATION_RATIO && density <= MAX_DENSITY) {
                LOGGER.debug("[AntiVoid] packet stoneSamples={} isolated={} totalSamples={} density={} isolationRatio={}",
                        stoneSamples, isolatedStoneSamples, totalSamples, density, isolationRatio);
                return true;
            }

            // Additional looser heuristic: moderate isolation with low density
            if (stoneSamples >= 80 && isolationRatio >= 0.60 && density <= 0.10) {
                LOGGER.debug("[AntiVoid] loose heuristic matched: stoneSamples={} isolated={} density={} isolationRatio={}",
                        stoneSamples, isolatedStoneSamples, density, isolationRatio);
                return true;
            }

            return false;
        } catch (Throwable t) {
            LOGGER.debug("[AntiVoid] Exception while analyzing chunk packet", t);
            return false;
        }
    }

    /**
     * Analyzes a BlockUpdateS2CPacket to determine if the update is suspicious/scrambled.
     *
     * This is a placeholder method for custom filtering logic.
     * Replace the return statement with your own detection algorithm.
     *
     * @param packet The BlockUpdateS2CPacket to analyze
     * @return true if the block update is detected as suspicious, false otherwise
     */
    private boolean isScrambledBlockUpdate(BlockUpdateS2CPacket packet) {
        // Placeholder: Custom detection logic should be implemented here
        // Examples of things you could check:
        // - Block state validity
        // - Position consistency
        // - Update frequency patterns
        // - Block type anomalies

        return false;
    }
}

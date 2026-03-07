package io.arona74.crlayers;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.enums.DoubleBlockHalf;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.state.property.Properties;
import net.minecraft.text.Text;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/**
 * Simplified chunk-based layer generator using distance-from-edge algorithm
 */
public class LayerGenerator {
    private final ServerWorld world;
    private final BlockMappingRegistry mappingRegistry;
    private final PlantMappingRegistry plantMappingRegistry;
    private final PlantDataStorage plantDataStorage;

    // Async generation tracking
    private static volatile CompletableFuture<Integer> currentAsyncGeneration = null;
    private static volatile boolean cancelRequested = false;
    
    public LayerGenerator(ServerWorld world) {
        this.world = world;
        this.mappingRegistry = new BlockMappingRegistry();
        this.plantMappingRegistry = new PlantMappingRegistry();
        Path worldDir = world.getServer().getSavePath(WorldSavePath.ROOT);
        this.plantDataStorage = new PlantDataStorage(worldDir);
    }
    
    /**
     * Generate layers in a chunk radius around center position
     */
    public int generateLayers(BlockPos center, int chunkRadius, boolean replacePlants) {
        CRLayers.LOGGER.info("Starting layer generation - Mode: {}, Max Distance: {}", 
            LayerConfig.MODE, LayerConfig.MAX_LAYER_DISTANCE);
        
        ChunkPos centerChunk = new ChunkPos(center);
        Set<ChunkPos> chunksToProcess = new HashSet<>();
        
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkPos chunkPos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                if (world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
                    chunksToProcess.add(chunkPos);
                }
            }
        }
        
        CRLayers.LOGGER.info("Processing {} loaded chunks", chunksToProcess.size());
        if (chunksToProcess.isEmpty()) {
            return 0;
        }
        
        // PHASE 1: Collect ALL surface heights (including holes) for edge detection
        Map<BlockPos, Integer> allSurfaceHeights = new HashMap<>();
        for (ChunkPos chunkPos : chunksToProcess) {
            collectAllSurfaceHeights(chunkPos, allSurfaceHeights);
        }
        
        CRLayers.LOGGER.info("Collected {} total surface positions for edge detection", allSurfaceHeights.size());
        
        // PHASE 2: Identify edges using complete heightmap
        Map<BlockPos, Integer> edges = identifyEdges(allSurfaceHeights);
        CRLayers.LOGGER.info("Identified {} edge positions", edges.size());
        
        if (edges.isEmpty()) {
            CRLayers.LOGGER.warn("No edges found");
            return 0;
        }
        
        // PHASE 3: Collect VALID placement positions (with filtering)
        Map<BlockPos, Integer> validSurfaceHeights = new HashMap<>();
        Map<BlockPos, Block> surfaceBlocks = new HashMap<>();
        
        for (ChunkPos chunkPos : chunksToProcess) {
            collectValidSurfaceData(chunkPos, validSurfaceHeights, surfaceBlocks);
        }
        
        CRLayers.LOGGER.info("Collected {} valid surface positions for layer placement", validSurfaceHeights.size());
        
        if (validSurfaceHeights.isEmpty()) {
            return 0;
        }
        
        // PHASE 4: Calculate layer values using FULL heightmap for distances
        // but only for VALID positions
        Map<BlockPos, Integer> layerValues = calculateLayerValues(
            validSurfaceHeights,  // Only calculate for valid positions
            allSurfaceHeights,    // But use full heightmap for distance calculations
            edges,
            chunksToProcess,
            new HashMap<>()       // No existing layers for batch processing
        );
        CRLayers.LOGGER.info("Calculated {} positions with layers", layerValues.size());
        
        // PHASE 5: Place layer blocks
        int blocksGenerated = placeAllLayers(layerValues, surfaceBlocks, replacePlants);
        
        if (replacePlants && blocksGenerated > 0) {
            plantDataStorage.save();
        }
        
        CRLayers.LOGGER.info("Generation complete! Generated {} blocks", blocksGenerated);
        return blocksGenerated;
    }

    /**
     * Cancel any ongoing async layer generation
     * @return true if there was an operation to cancel, false otherwise
     */
    public static boolean cancelAsyncGeneration() {
        if (currentAsyncGeneration != null && !currentAsyncGeneration.isDone()) {
            cancelRequested = true;
            currentAsyncGeneration.cancel(true);
            currentAsyncGeneration = null;
            return true;
        }
        return false;
    }

    /**
     * Check if an async generation is currently running
     */
    public static boolean isAsyncGenerationRunning() {
        return currentAsyncGeneration != null && !currentAsyncGeneration.isDone();
    }

    /**
     * Generate layers asynchronously to avoid freezing the server
     * @return CompletableFuture that completes when generation is done, returns number of blocks generated
     */
    public CompletableFuture<Integer> generateLayersAsync(BlockPos center, int chunkRadius, boolean replacePlants, net.minecraft.server.command.ServerCommandSource source) {
        // Check if already running
        if (isAsyncGenerationRunning()) {
            CRLayers.LOGGER.warn("Async generation already in progress!");
            return CompletableFuture.completedFuture(0);
        }

        CRLayers.LOGGER.info("Starting ASYNC layer generation - Mode: {}, Max Distance: {}",
            LayerConfig.MODE, LayerConfig.MAX_LAYER_DISTANCE);

        cancelRequested = false;

        long totalStartTime = System.currentTimeMillis();
        long phaseStartTime = totalStartTime;

        // PHASE 1-3: Collect all world data on MAIN THREAD (must be synchronous for thread safety)
        ChunkPos centerChunk = new ChunkPos(center);
        Set<ChunkPos> chunksToProcess = new HashSet<>();

        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkPos chunkPos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                if (world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
                    chunksToProcess.add(chunkPos);
                }
            }
        }

        CRLayers.LOGGER.info("Processing {} loaded chunks", chunksToProcess.size());
        if (chunksToProcess.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        if (source != null) {
            source.sendFeedback(() -> Text.literal(String.format("§7[1/5] Collecting data from %d chunks...", chunksToProcess.size())), false);
        }

        // Collect surface heights on main thread
        phaseStartTime = System.currentTimeMillis();
        Map<BlockPos, Integer> allSurfaceHeights = new HashMap<>();
        for (ChunkPos chunkPos : chunksToProcess) {
            collectAllSurfaceHeights(chunkPos, allSurfaceHeights);
        }
        long phase1Time = System.currentTimeMillis() - phaseStartTime;
        CRLayers.LOGGER.info("Collected {} surface positions in {}ms", allSurfaceHeights.size(), phase1Time);

        if (source != null) {
            final long p1Time = phase1Time;
            source.sendFeedback(() -> Text.literal(String.format("§7[2/5] Collected %d surface positions §8(%dms)", allSurfaceHeights.size(), p1Time)), false);
        }

        // Identify edges (no world access, just data processing)
        phaseStartTime = System.currentTimeMillis();
        Map<BlockPos, Integer> edges = identifyEdges(allSurfaceHeights);
        long phase2Time = System.currentTimeMillis() - phaseStartTime;
        CRLayers.LOGGER.info("Identified {} edges in {}ms", edges.size(), phase2Time);

        if (source != null) {
            final long p2Time = phase2Time;
            source.sendFeedback(() -> Text.literal(String.format("§7[3/5] Identified %d edges §8(%dms)", edges.size(), p2Time)), false);
        }

        if (edges.isEmpty()) {
            CRLayers.LOGGER.warn("No edges found");
            return CompletableFuture.completedFuture(0);
        }

        // Collect valid surface data on main thread
        phaseStartTime = System.currentTimeMillis();
        Map<BlockPos, Integer> validSurfaceHeights = new HashMap<>();
        Map<BlockPos, Block> surfaceBlocks = new HashMap<>();
        for (ChunkPos chunkPos : chunksToProcess) {
            collectValidSurfaceData(chunkPos, validSurfaceHeights, surfaceBlocks);
        }
        long phase3Time = System.currentTimeMillis() - phaseStartTime;
        CRLayers.LOGGER.info("Collected {} valid positions in {}ms", validSurfaceHeights.size(), phase3Time);

        if (source != null) {
            final long p3Time = phase3Time;
            source.sendFeedback(() -> Text.literal(String.format("§7[4/5] Collected %d valid positions §8(%dms)", validSurfaceHeights.size(), p3Time)), false);
        }

        if (validSurfaceHeights.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        // PHASE 4: Now do the CALCULATION async (CPU-intensive, no world access)
        final long calcStartTime = System.currentTimeMillis();
        CompletableFuture<Integer> future = CompletableFuture.supplyAsync(() -> {
            if (cancelRequested) {
                CRLayers.LOGGER.info("Async generation cancelled before calculation");
                return new AsyncGenerationResult(new HashMap<>(), new HashMap<>());
            }

            Map<BlockPos, Integer> layerValues = calculateLayerValues(
                validSurfaceHeights,
                allSurfaceHeights,
                edges,
                chunksToProcess,
                new HashMap<>()
            );
            long phase4Time = System.currentTimeMillis() - calcStartTime;
            CRLayers.LOGGER.info("Calculated {} layer positions in {}ms", layerValues.size(), phase4Time);

            // Send progress update on main thread
            if (source != null) {
                final long p4Time = phase4Time;
                world.getServer().execute(() ->
                    source.sendFeedback(() -> Text.literal(String.format("§7[5/5] Calculated %d positions §8(%dms) §7- placing blocks...", layerValues.size(), p4Time)), false)
                );
            }

            return new AsyncGenerationResult(layerValues, surfaceBlocks);

        }).thenApplyAsync(result -> {
            // PHASE 5: Place blocks on main server thread
            if (result.layerValues.isEmpty()) {
                return 0;
            }

            // Place all blocks (we're already on main thread via thenApplyAsync)
            int blocksPlaced = 0;

            for (Map.Entry<BlockPos, Integer> entry : result.layerValues.entrySet()) {
                BlockPos surfacePos = entry.getKey();
                int layerCount = entry.getValue();
                Block surfaceBlock = result.surfaceBlocks.get(surfacePos);

                if (surfaceBlock == null) continue;

                Block layerBlock = mappingRegistry.getLayerBlock(surfaceBlock, layerCount);
                if (layerBlock == null) continue;

                BlockPos layerPos = surfacePos.up();
                BlockState existingState = world.getBlockState(layerPos);
                Block existingBlock = existingState.getBlock();

                // Handle plant replacement
                if (replacePlants && !existingState.isAir()) {

                    if (plantMappingRegistry.isConquestPlant(existingBlock)) {
                        continue;
                    }

                    if (plantMappingRegistry.isReplaceablePlant(existingBlock)) {
                        boolean isTallPlant = isTallPlant(existingBlock);

                        if (isTallPlant) {
                            BlockPos upperPos = layerPos.up();
                            BlockState upperState = world.getBlockState(upperPos);
                            Block upperPlantBlock = upperState.getBlock();
                            plantDataStorage.storePlant(upperPos, upperPlantBlock);
                        }

                        plantDataStorage.storePlant(layerPos, existingBlock);
                        if (isTallPlant) {
                            plantDataStorage.storeTallPlant(layerPos, true);
                        }

                        BlockState layerState = layerBlock.getDefaultState();
                        if (layerState.contains(Properties.LAYERS)) {
                            layerState = layerState.with(Properties.LAYERS, layerCount);
                        }
                        // Check if we should waterlog
                        if (layerState.contains(Properties.WATERLOGGED) && existingState.getBlock() == Blocks.WATER) {
                            layerState = layerState.with(Properties.WATERLOGGED, true);
                        }

                        world.setBlockState(layerPos, layerState, 3);
                        blocksPlaced++;

                        if (isTallPlant) {
                            BlockPos upperPos = layerPos.up();
                            world.setBlockState(upperPos, Blocks.AIR.getDefaultState(), 3);
                        }
                    }
                } else if (existingState.isAir() || existingState.getBlock() == Blocks.WATER) {
                    BlockState layerState = layerBlock.getDefaultState();
                    if (layerState.contains(Properties.LAYERS)) {
                        layerState = layerState.with(Properties.LAYERS, layerCount);
                    }
                    // Check if we should waterlog
                    if (layerState.contains(Properties.WATERLOGGED) && existingState.getBlock() == Blocks.WATER) {
                        layerState = layerState.with(Properties.WATERLOGGED, true);
                    }

                    world.setBlockState(layerPos, layerState, 3);
                    blocksPlaced++;
                }
            }

            // Save plant data
            if (replacePlants && blocksPlaced > 0) {
                plantDataStorage.save();
            }

            long totalTime = System.currentTimeMillis() - totalStartTime;
            CRLayers.LOGGER.info("Async generation complete! Generated {} blocks in {}ms", blocksPlaced, totalTime);

            // Send completion message to chat
            if (source != null) {
                final int blocks = blocksPlaced;
                final long time = totalTime;
                source.sendFeedback(() -> Text.literal(String.format("§aAsync generation complete! Generated %d blocks §7(total: %dms)", blocks, time)), true);
            }

            return blocksPlaced;
        }, world.getServer()).whenComplete((result, throwable) -> {
            // Clear the tracking when done (success or failure)
            currentAsyncGeneration = null;
            cancelRequested = false;
        });

        // Store and return the future
        currentAsyncGeneration = future;
        return future;
    }

    /**
     * Helper class to hold async generation results
     */
    private static class AsyncGenerationResult {
        final Map<BlockPos, Integer> layerValues;
        final Map<BlockPos, Block> surfaceBlocks;

        AsyncGenerationResult(Map<BlockPos, Integer> layerValues, Map<BlockPos, Block> surfaceBlocks) {
            this.layerValues = layerValues;
            this.surfaceBlocks = surfaceBlocks;
        }
    }

    /**
     * Scan neighboring chunks for existing layer blocks
     * This allows smoothing to see layers from already-processed chunks
     */
    private Map<BlockPos, Integer> scanExistingLayers(Set<ChunkPos> chunksToAnalyze,
                                                       ChunkPos centerChunk,
                                                       Map<BlockPos, Integer> surfaceHeights) {
        Map<BlockPos, Integer> existingLayers = new HashMap<>();

        for (Map.Entry<BlockPos, Integer> entry : surfaceHeights.entrySet()) {
            BlockPos surfacePos = entry.getKey();
            ChunkPos posChunk = new ChunkPos(surfacePos);

            // Only scan neighboring chunks, not the center chunk (we're recalculating it)
            if (posChunk.equals(centerChunk)) continue;
            if (!chunksToAnalyze.contains(posChunk)) continue;

            // Check for a layer block above the surface
            BlockPos layerPos = surfacePos.up();
            BlockState layerState = world.getBlockState(layerPos);

            if (layerState.isAir()) continue;

            // Check if it's a layer block with LAYERS property
            if (layerState.contains(Properties.LAYERS)) {
                int layers = layerState.get(Properties.LAYERS);
                existingLayers.put(surfacePos, layers);
                if (existingLayers.size() <= 3) {
                    CRLayers.LOGGER.info("  Found existing layer at {} (Y={}) with value {}", surfacePos, surfacePos.getY(), layers);
                }
            }
        }

        CRLayers.LOGGER.info("Found {} existing layers in neighboring chunks (center chunk: {})", existingLayers.size(), centerChunk);
        return existingLayers;
    }

    /**
     * Collect ALL surface heights without filtering (for edge detection)
     */
    private void collectAllSurfaceHeights(ChunkPos chunkPos, Map<BlockPos, Integer> allHeights) {
        int foundInChunk = 0;
        int nullSurface = 0;
        Set<Integer> uniqueYLevels = new HashSet<>();
        
        for (int x = chunkPos.getStartX(); x <= chunkPos.getStartX() + 15; x++) {
            for (int z = chunkPos.getStartZ(); z <= chunkPos.getStartZ() + 15; z++) {
                BlockPos columnPos = new BlockPos(x, 0, z);
                BlockPos surfacePos = findSurfaceBlock(columnPos);
                
                if (surfacePos != null) {
                    allHeights.put(surfacePos, surfacePos.getY());
                    uniqueYLevels.add(surfacePos.getY());
                    foundInChunk++;

                    // Debug: log underwater positions
                    if (surfacePos.getY() < 63 && foundInChunk <= 3) {
                        BlockState surfaceState = world.getBlockState(surfacePos);
                        CRLayers.LOGGER.info("  Found underwater surface at {} Y={} block={}",
                            surfacePos, surfacePos.getY(), surfaceState.getBlock());
                    }
                } else {
                    nullSurface++;
                }
            }
        }
        
        CRLayers.LOGGER.info("Chunk {}: found {} surfaces ({} null), Y levels: {} (range: {} to {})", 
            chunkPos, foundInChunk, nullSurface, uniqueYLevels.size(),
            uniqueYLevels.isEmpty() ? 0 : Collections.min(uniqueYLevels),
            uniqueYLevels.isEmpty() ? 0 : Collections.max(uniqueYLevels));
    }

    /**
     * Collect VALID surface data with filtering (for layer placement)
     */
    private void collectValidSurfaceData(ChunkPos chunkPos, 
                                        Map<BlockPos, Integer> surfaceHeights, 
                                        Map<BlockPos, Block> surfaceBlocks) {
        for (int x = chunkPos.getStartX(); x <= chunkPos.getStartX() + 15; x++) {
            for (int z = chunkPos.getStartZ(); z <= chunkPos.getStartZ() + 15; z++) {
                BlockPos columnPos = new BlockPos(x, 0, z);
                BlockPos surfacePos = findSurfaceBlock(columnPos);
                
                if (surfacePos == null) continue;
                
                BlockState surfaceState = world.getBlockState(surfacePos);
                Block surfaceBlock = surfaceState.getBlock();
                
                // Apply filters for valid placement
                if (!canGenerateLayersOn(surfaceBlock)) continue;
                // Allow generation in water - waterlogging will be handled during placement

                surfaceHeights.put(surfacePos, surfacePos.getY());
                surfaceBlocks.put(surfacePos, surfaceBlock);
            }
        }
    }
    
    /**
     * Process a single chunk with neighbors
     */
    public int processChunk(ChunkPos chunkPos, boolean replacePlants) {
        if (!world.isChunkLoaded(chunkPos.x, chunkPos.z)) {
            return 0;
        }
        
        // Get neighbors for proper edge detection
        Set<ChunkPos> chunksToAnalyze = new HashSet<>();
        chunksToAnalyze.add(chunkPos);
        
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                ChunkPos neighbor = new ChunkPos(chunkPos.x + dx, chunkPos.z + dz);
                if (world.isChunkLoaded(neighbor.x, neighbor.z)) {
                    chunksToAnalyze.add(neighbor);
                }
            }
        }
        
        // Collect ALL surface heights (unfiltered) for edge detection
        Map<BlockPos, Integer> allSurfaceHeights = new HashMap<>();
        for (ChunkPos chunk : chunksToAnalyze) {
            collectAllSurfaceHeights(chunk, allSurfaceHeights);
        }
        
        // Identify edges across all analyzed chunks
        Map<BlockPos, Integer> edges = identifyEdges(allSurfaceHeights);
        
        // Collect valid surface data (filtered) for placement
        Map<BlockPos, Integer> validSurfaceHeights = new HashMap<>();
        Map<BlockPos, Block> surfaceBlocks = new HashMap<>();
        for (ChunkPos chunk : chunksToAnalyze) {
            collectValidSurfaceData(chunk, validSurfaceHeights, surfaceBlocks);
        }
        
        // Scan neighboring chunks for existing layers (to enable smoothing across chunks)
        Map<BlockPos, Integer> existingLayers = scanExistingLayers(chunksToAnalyze, chunkPos, allSurfaceHeights);

        // Calculate layers only for center chunk
        // Pass BOTH validSurfaceHeights (where to place) and allSurfaceHeights (for distances)
        // Also pass existingLayers so smoothing can see neighbor values during calculation
        Set<ChunkPos> centerOnly = new HashSet<>();
        centerOnly.add(chunkPos);
        Map<BlockPos, Integer> layerValues = calculateLayerValues(
            validSurfaceHeights,   // Only calculate for valid positions
            allSurfaceHeights,     // But use full heightmap for distance calculations
            edges,
            centerOnly,
            existingLayers         // Pre-existing layers from neighbors
        );
        
        // Place layers
        int blocksGenerated = placeAllLayers(layerValues, surfaceBlocks, replacePlants);
        
        if (replacePlants && blocksGenerated > 0) {
            plantDataStorage.save();
        }
        
        return blocksGenerated;
    }
    
    /**
     * Identify edge positions - only HIGHER blocks at terrain changes
     * An edge is a block that has at least one neighbor LOWER than itself
     */
    private Map<BlockPos, Integer> identifyEdges(Map<BlockPos, Integer> surfaceHeights) {
        Map<BlockPos, Integer> edges = new HashMap<>();
        
        CRLayers.LOGGER.info("Starting edge detection on {} surface positions", surfaceHeights.size());
        
        // Create X,Z -> BlockPos lookup map for fast neighbor access
        Map<String, BlockPos> xzLookup = new HashMap<>();
        for (BlockPos pos : surfaceHeights.keySet()) {
            String key = pos.getX() + "," + pos.getZ();
            xzLookup.put(key, pos);
        }
        
        int edgesFound = 0;
        int positionsWithNeighbors = 0;
        int heightDifferencesFound = 0;
        int maxHeightDiffSeen = 0;
        
        for (Map.Entry<BlockPos, Integer> entry : surfaceHeights.entrySet()) {
            BlockPos pos = entry.getKey();
            int height = entry.getValue();
            
            // Check all 8 neighbors (cardinals + diagonals)
            int[] offsets = {-1, 0, 1};
            boolean hasLowerNeighbor = false;
            int validNeighborsChecked = 0;
            
            for (int dx : offsets) {
                for (int dz : offsets) {
                    if (dx == 0 && dz == 0) continue; // Skip self
                    
                    // Look up neighbor by X,Z coordinates
                    String neighborKey = (pos.getX() + dx) + "," + (pos.getZ() + dz);
                    BlockPos neighborPos = xzLookup.get(neighborKey);
                    
                    if (neighborPos == null) {
                        continue; // No surface block at this X,Z
                    }
                    
                    Integer neighborHeight = surfaceHeights.get(neighborPos);
                    if (neighborHeight == null) continue;
                    
                    validNeighborsChecked++;
                    int heightDiff = height - neighborHeight;
                    
                    // Track statistics
                    if (heightDiff != 0) {
                        heightDifferencesFound++;
                        maxHeightDiffSeen = Math.max(maxHeightDiffSeen, Math.abs(heightDiff));
                        
                        if (edgesFound == 0 && heightDifferencesFound <= 5) {
                            CRLayers.LOGGER.info("Height diff: {} (Y={}) vs {} (Y={}) = diff {}",
                                pos, height, neighborPos, neighborHeight, heightDiff);
                        }
                    }
                    
                    // If neighbor is LOWER by threshold or more, this block is an edge
                    if (heightDiff >= LayerConfig.EDGE_HEIGHT_THRESHOLD) {
                        hasLowerNeighbor = true;
                        
                        if (edgesFound < 10) {
                            CRLayers.LOGGER.info("EDGE at {} (Y={}) - neighbor {} lower (Y={}, diff={})", 
                                pos, height, neighborPos, neighborHeight, heightDiff);
                        }
                        break;
                    }
                }
                if (hasLowerNeighbor) break;
            }
            
            if (validNeighborsChecked > 0) {
                positionsWithNeighbors++;
            }

            if (hasLowerNeighbor) {
                edges.put(pos, height);
                edgesFound++;
            }
        }
        
        CRLayers.LOGGER.info("Edge detection: {} positions with neighbors, {} height diffs (max: {}), {} edges found", 
            positionsWithNeighbors, heightDifferencesFound, maxHeightDiffSeen, edges.size());
        
        return edges;
    }
    
    /**
     * Calculate layer values using per-Y-level processing with spreading and smoothing
     */
    private Map<BlockPos, Integer> calculateLayerValues(Map<BlockPos, Integer> validPositions,
                                                        Map<BlockPos, Integer> fullHeightmap,
                                                        Map<BlockPos, Integer> edges,
                                                        Set<ChunkPos> targetChunks,
                                                        Map<BlockPos, Integer> existingLayers) {

        // Initialize with existing layers from neighbors (for smoothing across chunks)
        Map<BlockPos, Integer> layerValues = new HashMap<>(existingLayers);

        // Get all unique Y levels
        Set<Integer> allYLevels = new HashSet<>(fullHeightmap.values());
        List<Integer> sortedYLevels = new ArrayList<>(allYLevels);
        Collections.sort(sortedYLevels, Collections.reverseOrder()); // Highest to lowest

        if (sortedYLevels.isEmpty()) {
            CRLayers.LOGGER.info("No Y levels to process");
            return layerValues;
        }

        // Allow processing even with 1 Y level if we have existing layers to spread
        if (sortedYLevels.size() == 1 && existingLayers.isEmpty()) {
            CRLayers.LOGGER.info("Only 1 Y level and no existing layers - skipping");
            return layerValues;
        }

        CRLayers.LOGGER.info("Processing {} Y levels (from {} to {}), Mode: {}",
            sortedYLevels.size(),
            sortedYLevels.get(0),
            sortedYLevels.get(sortedYLevels.size()-1),
            LayerConfig.MODE);
        
        // Create XZ lookup for full heightmap
        Map<String, Integer> xzToHeight = new HashMap<>();
        for (Map.Entry<BlockPos, Integer> entry : fullHeightmap.entrySet()) {
            String key = entry.getKey().getX() + "," + entry.getKey().getZ();
            xzToHeight.put(key, entry.getValue());
        }
        
        // Track E blocks from previous (higher) Y level
        Set<BlockPos> previousEBlocks = new HashSet<>();
        
        // Process each Y level from highest to lowest
        for (int i = 0; i < sortedYLevels.size(); i++) {
            int currentY = sortedYLevels.get(i);

            // Step 1: Classify blocks at this Y level
            Set<BlockPos> hBlocks = new HashSet<>();
            Set<BlockPos> eBlocks = new HashSet<>();
            Set<BlockPos> lBlocks = new HashSet<>();

            classifyBlocksAtYLevel(currentY, fullHeightmap, xzToHeight, validPositions,
                targetChunks, previousEBlocks, hBlocks, eBlocks, lBlocks);

            CRLayers.LOGGER.info("Y={}: H={}, E={}, L={}", currentY, hBlocks.size(), eBlocks.size(), lBlocks.size());

            // Always process all Y levels (including lowest)
            CRLayers.LOGGER.info("Processing Y level {}", currentY);

            // STEP 1: Top generation - apply inverse gradients for E-to-E paths (if enabled)
            // This creates hills on flat plateaus at any Y-level
            if (LayerConfig.TOP_GENERATION && !eBlocks.isEmpty() && !lBlocks.isEmpty()) {
                spreadTopGenerationLayers(currentY, eBlocks, lBlocks, layerValues);
            }

            // STEP 2: Normal generation - spread from H blocks (if they exist)
            // This creates the standard downward gradient from higher terrain
            if (!hBlocks.isEmpty()) {
                // Determine effective max distance based on mode
                int effectiveMaxDistance = LayerConfig.MAX_LAYER_DISTANCE;
                if (LayerConfig.MODE == LayerConfig.GenerationMode.EXTENDED) {
                    effectiveMaxDistance = LayerConfig.MAX_LAYER_DISTANCE * 2;
                } else if (LayerConfig.MODE == LayerConfig.GenerationMode.EXTREME) {
                    effectiveMaxDistance = LayerConfig.MAX_LAYER_DISTANCE * 3;
                }

                spreadLayersFromHBlocks(currentY, hBlocks, lBlocks, eBlocks, layerValues,
                    effectiveMaxDistance);
            }

            // STEP 3: Smoothing passes (apply to all generated layers)
            for (int cycle = 0; cycle < LayerConfig.SMOOTHING_CYCLES; cycle++) {
                smoothingPass(currentY, lBlocks, hBlocks, eBlocks, layerValues);
            }

            // Remember E blocks for next (lower) Y level
            previousEBlocks = eBlocks;
        }
        
        return layerValues;
    }

    /**
     * Classify all blocks at given Y level into H, E, or L blocks
     * H blocks are inherited from E blocks of the Y level above
     * E blocks have lower neighbors OR missing neighbors (holes/edges)
     */
    private void classifyBlocksAtYLevel(int currentY, 
                                        Map<BlockPos, Integer> fullHeightmap,
                                        Map<String, Integer> xzToHeight,
                                        Map<BlockPos, Integer> validPositions,
                                        Set<ChunkPos> targetChunks,
                                        Set<BlockPos> previousEBlocks,
                                        Set<BlockPos> hBlocks,
                                        Set<BlockPos> eBlocks,
                                        Set<BlockPos> lBlocks) {
        
        // First, inherit H blocks from previous Y level's E blocks
        for (BlockPos prevE : previousEBlocks) {
            // The position directly below an E block becomes an H block
            BlockPos hPos = new BlockPos(prevE.getX(), currentY, prevE.getZ());
            
            // Check if there's terrain at or above current Y at this X,Z
            String key = hPos.getX() + "," + hPos.getZ();
            Integer surfaceHeight = xzToHeight.get(key);
            
            // If there's terrain at this X,Z and surface is at or above currentY
            // then there must be a block at currentY (it's inside/below the terrain)
            if (surfaceHeight != null && surfaceHeight >= currentY) {
                ChunkPos posChunk = new ChunkPos(hPos);
                if (targetChunks.contains(posChunk)) {
                    hBlocks.add(hPos);
                    CRLayers.LOGGER.info("H block at {} (inherited from E at Y={})", hPos, prevE.getY());
                }
            }
        }
        
        // Find all positions at this Y level
        for (Map.Entry<BlockPos, Integer> entry : fullHeightmap.entrySet()) {
            if (entry.getValue() != currentY) continue;
            
            BlockPos pos = entry.getKey();
            
            // Only process positions in target chunks
            ChunkPos posChunk = new ChunkPos(pos);
            if (!targetChunks.contains(posChunk)) continue;
            
            // Skip if already classified as H
            if (hBlocks.contains(pos)) continue;
            
            // Check ALL 8 neighbors (orthogonal + diagonal)
            boolean hasLowerNeighbor = false;
            boolean hasMissingNeighbor = false;
            
            int[] offsets = {-1, 0, 1};
            for (int dx : offsets) {
                for (int dz : offsets) {
                    if (dx == 0 && dz == 0) continue;
                    
                    String key = (pos.getX() + dx) + "," + (pos.getZ() + dz);
                    Integer neighborHeight = xzToHeight.get(key);
                    
                    if (neighborHeight == null) {
                        // Missing neighbor = hole/edge
                        hasMissingNeighbor = true;
                    } else if (neighborHeight < currentY) {
                        hasLowerNeighbor = true;
                    }
                }
            }
            
            // E block if has lower OR missing neighbor (including diagonals)
            if (hasLowerNeighbor || hasMissingNeighbor) {
                eBlocks.add(pos);
            } else {
                // Only add to L blocks if it's a valid position
                if (validPositions.containsKey(pos)) {
                    lBlocks.add(pos);
                }
            }
        }

        // Also check neighboring chunks for E blocks so smoothing can see them
        for (Map.Entry<BlockPos, Integer> entry : fullHeightmap.entrySet()) {
            if (entry.getValue() != currentY) continue;

            BlockPos pos = entry.getKey();
            ChunkPos posChunk = new ChunkPos(pos);

            // Only process positions NOT in target chunks (neighboring chunks only)
            if (targetChunks.contains(posChunk)) continue;

            // Skip if already classified
            if (hBlocks.contains(pos) || eBlocks.contains(pos)) continue;

            // Check if this position has lower or missing neighbors
            boolean hasLowerNeighbor = false;
            boolean hasMissingNeighbor = false;

            int[] offsets = {-1, 0, 1};
            for (int dx : offsets) {
                for (int dz : offsets) {
                    if (dx == 0 && dz == 0) continue;

                    String key = (pos.getX() + dx) + "," + (pos.getZ() + dz);
                    Integer neighborHeight = xzToHeight.get(key);

                    if (neighborHeight == null) {
                        hasMissingNeighbor = true;
                    } else if (neighborHeight < currentY) {
                        hasLowerNeighbor = true;
                    }
                }
            }

            // Add to E blocks if it's an edge (for neighbor detection during smoothing)
            if (hasLowerNeighbor || hasMissingNeighbor) {
                eBlocks.add(pos);
            }
        }
    }

    /**
     * Single smoothing pass
     * Only smooths L blocks that have at least two cardinal neighbors that are H, E, or have a layer value.
     * Smoothing calculation and max neighbor comparison consider only cardinal neighbors.
     */
    private void smoothingPass(int currentY,
                            Set<BlockPos> lBlocks,
                            Set<BlockPos> hBlocks,
                            Set<BlockPos> eBlocks,
                            Map<BlockPos, Integer> layerValues) {

        Map<BlockPos, Integer> newValues = new HashMap<>();

        // Check if this is a flat area (no H or E blocks)
        boolean isFlatArea = hBlocks.isEmpty() && eBlocks.isEmpty();

        // Removed verbose logging for each Y level

        int[][] cardinalOffsets = {
            { 1, 0 },  // East
            { -1, 0 }, // West
            { 0, 1 },  // South
            { 0, -1 }  // North
        };

        int debugCount = 0;
        for (BlockPos lBlock : lBlocks) {
            boolean isDebugPos = isFlatArea && debugCount < 3;
            if (isFlatArea) debugCount++; // Increment for every L block in flat areas

            // Get existing value (from spreading phase)
            Integer existingValue = layerValues.get(lBlock);

            int sum = 0;
            int count = 0;
            int maxCardinalNeighborValue = 0;
            boolean hasEBlockNeighbor = false;

            // Only consider the 4 cardinal neighbors
            for (int[] offset : cardinalOffsets) {
                BlockPos neighbor = new BlockPos(lBlock.getX() + offset[0], currentY, lBlock.getZ() + offset[1]);

                if (hBlocks.contains(neighbor)) {
                    sum += 8;
                    count++;
                    maxCardinalNeighborValue = Math.max(maxCardinalNeighborValue, 8);
                } else if (eBlocks.contains(neighbor)) {
                    sum += 0;
                    count++;
                    hasEBlockNeighbor = true;
                } else if (layerValues.containsKey(neighbor)) {
                    int neighborValue = layerValues.get(neighbor);
                    sum += neighborValue;
                    count++;
                    maxCardinalNeighborValue = Math.max(maxCardinalNeighborValue, neighborValue);
                }
            }

            if (isDebugPos) {
                CRLayers.LOGGER.info("  DEBUG pos {}: count={}, maxValue={}, existingValue={}",
                    lBlock, count, maxCardinalNeighborValue, existingValue);
            }

            // For flat areas, allow smoothing with just 1 neighbor to enable spreading
            // For normal terrain, require at least 2 neighbors to prevent artifacts
            int requiredNeighbors = isFlatArea ? 1 : 2;
            if (count < requiredNeighbors) {
                if (isDebugPos) {
                    CRLayers.LOGGER.info("    Skipped - not enough neighbors (need {}, have {})", requiredNeighbors, count);
                }
                continue;
            }

            // Only smooth if max cardinal neighbor value is at least 2
            if (maxCardinalNeighborValue >= 2) {
                if (isDebugPos) {
                    CRLayers.LOGGER.info("    Smoothing - avg will be calculated from sum={}, count={}", sum, count);
                }
                double average = (double) sum / count;
                int value;

                if (LayerConfig.SMOOTHING_ROUNDING_MODE == LayerConfig.RoundingMode.UP) {
                    value = (int) Math.ceil(average);
                } else if (LayerConfig.SMOOTHING_ROUNDING_MODE == LayerConfig.RoundingMode.NEAREST) {
                    value = (int) Math.round(average);
                } else { // DOWN
                    value = (int) Math.floor(average);
                }

                // Clamp to valid range 1-7
                value = Math.max(1, Math.min(7, value));

                // Ensure smoothed value is strictly less than max cardinal neighbor
                if (value >= maxCardinalNeighborValue) {
                    value = maxCardinalNeighborValue - 1;
                    if (value < 1) value = 1; // safety clamp
                }

                // Apply smoothing based on priority mode
                if (LayerConfig.SMOOTHING_PRIORITY == LayerConfig.SmoothingPriority.UP) {
                    // UP: Only apply smoothing if it INCREASES the value (preserves extended gradients)
                    if (existingValue == null || value > existingValue) {
                        newValues.put(lBlock, value);
                    }
                } else {
                    // DOWN: Hybrid approach
                    if (hasEBlockNeighbor) {
                        // Near edges: Traditional smoothing (always apply)
                        newValues.put(lBlock, value);
                    } else {
                        // Away from edges: Preserve gradients (only apply if higher, like UP mode)
                        if (existingValue == null || value > existingValue) {
                            newValues.put(lBlock, value);
                        }
                    }
                }
            } else {
                if (isDebugPos) {
                    CRLayers.LOGGER.info("    Skipped - maxCardinalNeighborValue too low ({})", maxCardinalNeighborValue);
                }
            }
        }

        // Apply new values
        layerValues.putAll(newValues);
    }

    /**
     * Spread layers from all H blocks in 4 cardinal directions
     */
    private void spreadLayersFromHBlocks(int currentY,
                                        Set<BlockPos> hBlocks,
                                        Set<BlockPos> lBlocks,
                                        Set<BlockPos> eBlocks,
                                        Map<BlockPos, Integer> layerValues,
                                        int maxDistance) {

        // 4 directions: N, E, S, W
        int[][] directions = {
            {0, -1},  // N
            {1, 0},   // E
            {0, 1},   // S
            {-1, 0},  // W
        };

        // Track path length statistics
        Map<Integer, Integer> pathLengthCounts = new HashMap<>();
        int totalPaths = 0;
        int pathsWithLayers = 0;

        for (BlockPos hBlock : hBlocks) {
            for (int dirIdx = 0; dirIdx < directions.length; dirIdx++) {
                int[] dir = directions[dirIdx];

                // Walk in this direction and collect L blocks
                List<BlockPos> pathLBlocks = new ArrayList<>();

                for (int step = 1; step <= maxDistance; step++) {
                    BlockPos checkPos = new BlockPos(
                        hBlock.getX() + dir[0] * step,
                        currentY,
                        hBlock.getZ() + dir[1] * step
                    );

                    // Stop if we hit H or E block
                    if (hBlocks.contains(checkPos) || eBlocks.contains(checkPos)) {
                        break;
                    }

                    // Stop if not an L block
                    if (!lBlocks.contains(checkPos)) {
                        break;
                    }

                    pathLBlocks.add(checkPos);
                }

                totalPaths++;

                // Apply gradient based on available blocks
                if (!pathLBlocks.isEmpty()) {
                    int pathLength = pathLBlocks.size();
                    pathLengthCounts.put(pathLength, pathLengthCounts.getOrDefault(pathLength, 0) + 1);
                    pathsWithLayers++;
                    applyGradient(pathLBlocks, layerValues);
                }
            }
        }

        // Log path statistics
        if (pathsWithLayers > 0) {
            CRLayers.LOGGER.info("Y={}: Processed {} paths ({} with layers). Path lengths: {}",
                currentY, totalPaths, pathsWithLayers, formatPathStats(pathLengthCounts));
        }
    }

    /**
     * Spread inverse gradient layers from E blocks for top generation mode
     * Creates small hills between E blocks on the highest Y-level
     */
    private void spreadTopGenerationLayers(int currentY,
                                           Set<BlockPos> eBlocks,
                                           Set<BlockPos> lBlocks,
                                           Map<BlockPos, Integer> layerValues) {

        // 4 cardinal directions: N, E, S, W
        int[][] directions = {
            {0, -1},  // N
            {1, 0},   // E
            {0, 1},   // S
            {-1, 0},  // W
        };

        // Track path statistics
        Map<Integer, Integer> pathLengthCounts = new HashMap<>();
        int totalPaths = 0;
        int pathsWithHills = 0;

        for (BlockPos eBlock : eBlocks) {
            for (int[] dir : directions) {
                // Walk in this direction and collect L blocks until hitting another E block
                List<BlockPos> pathLBlocks = new ArrayList<>();
                boolean foundOtherEBlock = false;

                for (int step = 1; step <= 100; step++) { // Max 100 blocks search
                    BlockPos checkPos = new BlockPos(
                        eBlock.getX() + dir[0] * step,
                        currentY,
                        eBlock.getZ() + dir[1] * step
                    );

                    // Found another E block - valid path!
                    if (eBlocks.contains(checkPos)) {
                        foundOtherEBlock = true;
                        break;
                    }

                    // If not an L block, stop searching
                    if (!lBlocks.contains(checkPos)) {
                        break;
                    }

                    pathLBlocks.add(checkPos);
                }

                totalPaths++;

                // Only apply inverse gradient if we found a complete E-to-E path
                if (foundOtherEBlock && !pathLBlocks.isEmpty()) {
                    int pathLength = pathLBlocks.size();
                    pathLengthCounts.put(pathLength, pathLengthCounts.getOrDefault(pathLength, 0) + 1);
                    pathsWithHills++;
                    applyInverseGradient(pathLBlocks, layerValues);
                }
            }
        }

        // Log statistics
        if (pathsWithHills > 0) {
            CRLayers.LOGGER.info("Y={}: TOP GENERATION - Processed {} E-to-E paths ({} with hills). Path lengths: {}",
                currentY, totalPaths, pathsWithHills, formatPathStats(pathLengthCounts));
        } else {
            CRLayers.LOGGER.info("Y={}: TOP GENERATION - No valid E-to-E paths found", currentY);
        }
    }

    private String formatPathStats(Map<Integer, Integer> pathLengthCounts) {
        StringBuilder sb = new StringBuilder();
        pathLengthCounts.entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .forEach(entry -> {
                if (sb.length() > 0) sb.append(", ");
                sb.append(entry.getKey()).append("x").append(entry.getValue());
            });
        return sb.toString();
    }

    /**
     * Apply gradient to a path of L blocks
     */
    private void applyGradient(List<BlockPos> path, Map<BlockPos, Integer> layerValues) {
        int availableBlocks = path.size();
        int[] gradient = getGradient(availableBlocks);

        for (int i = 0; i < path.size() && i < gradient.length; i++) {
            BlockPos pos = path.get(i);
            int newValue = gradient[i];

            // Take max of existing and new value
            Integer existing = layerValues.get(pos);
            if (existing == null || newValue > existing) {
                layerValues.put(pos, newValue);
            }
        }
    }

    /**
     * Apply inverse gradient to a path of L blocks (for top generation mode)
     * Creates a hill effect with low values at edges and high values in the middle
     */
    private void applyInverseGradient(List<BlockPos> path, Map<BlockPos, Integer> layerValues) {
        int availableBlocks = path.size();
        int[] gradient = getInverseGradient(availableBlocks);

        for (int i = 0; i < path.size() && i < gradient.length; i++) {
            BlockPos pos = path.get(i);
            int newValue = gradient[i];

            // Take max of existing and new value (when multiple paths overlap)
            Integer existing = layerValues.get(pos);
            if (existing == null || newValue > existing) {
                layerValues.put(pos, newValue);
            }
        }
    }

    private int[] getGradient(int space) {
        // EXTREME mode: use extreme gradients with triple repeated values
        if (LayerConfig.MODE == LayerConfig.GenerationMode.EXTREME) {
            return getExtremeNormalGradient(space);
        }
        // EXTENDED mode: use extended gradients with repeated values
        if (LayerConfig.MODE == LayerConfig.GenerationMode.EXTENDED) {
            return getExtendedNormalGradient(space);
        }
        // BASIC mode: linear gradients
        if (LayerConfig.MODE == LayerConfig.GenerationMode.BASIC) {
            return getNormalGradient(space);
        }
        return new int[0];
    }

    /**
     * Get inverse gradient for top generation mode
     * Selects appropriate gradient based on generation mode
     */
    private int[] getInverseGradient(int space) {
        if (LayerConfig.MODE == LayerConfig.GenerationMode.EXTREME) {
            return getInverseExtremeGradient(space);
        }
        if (LayerConfig.MODE == LayerConfig.GenerationMode.EXTENDED) {
            return getInverseExtendedGradient(space);
        }
        // BASIC mode
        return getInverseBasicGradient(space);
    }

    /**
     * EXTREME inverse gradient for top generation
     * Creates very wide, very gradual hills with repeated values (up to 7)
     */
    private int[] getInverseExtremeGradient(int space) {
        // Symmetric hill patterns with repeated values reaching up to 7
        if (space >= 21) {
            return new int[]{1, 1, 2, 2, 3, 3, 4, 4, 5, 6, 7, 6, 5, 4, 4, 3, 3, 2, 2, 1, 1};
        } else if (space >= 20) {
            return new int[]{1, 1, 2, 2, 3, 3, 4, 5, 6, 7, 6, 5, 4, 3, 3, 2, 2, 1, 1, 1};
        } else if (space >= 19) {
            return new int[]{1, 1, 2, 2, 3, 3, 4, 5, 6, 7, 6, 5, 4, 3, 3, 2, 2, 1, 1};
        } else if (space >= 18) {
            return new int[]{1, 1, 2, 2, 3, 4, 5, 6, 7, 6, 5, 4, 3, 2, 2, 1, 1, 1};
        } else if (space >= 17) {
            return new int[]{1, 1, 2, 2, 3, 4, 5, 6, 7, 6, 5, 4, 3, 2, 2, 1, 1};
        } else if (space >= 16) {
            return new int[]{1, 1, 2, 3, 4, 5, 6, 7, 6, 5, 4, 3, 2, 1, 1, 1};
        } else if (space >= 15) {
            return new int[]{1, 1, 2, 3, 4, 5, 6, 7, 6, 5, 4, 3, 2, 1, 1};
        } else {
            // Fall back to extended for smaller spaces
            return getInverseExtendedGradient(space);
        }
    }

    /**
     * EXTENDED inverse gradient for top generation
     * Creates wider, more gradual hills with repeated values (up to 7)
     */
    private int[] getInverseExtendedGradient(int space) {
        // Symmetric hill patterns with repeated values reaching up to 7
        if (space >= 14) {
            return new int[]{1, 2, 3, 4, 5, 6, 7, 7, 6, 5, 4, 3, 2, 1};
        } else if (space >= 13) {
            return new int[]{1, 2, 3, 4, 5, 6, 7, 6, 5, 4, 3, 2, 1};
        } else if (space >= 12) {
            return new int[]{1, 2, 3, 4, 5, 6, 6, 5, 4, 3, 2, 1};
        } else if (space >= 11) {
            return new int[]{1, 2, 3, 4, 5, 6, 5, 4, 3, 2, 1};
        } else if (space >= 10) {
            return new int[]{1, 2, 3, 4, 5, 5, 4, 3, 2, 1};
        } else if (space >= 9) {
            return new int[]{1, 2, 3, 4, 5, 4, 3, 2, 1};
        } else if (space >= 8) {
            return new int[]{1, 2, 3, 5, 5, 3, 2, 1};
        } else {
            // Fall back to basic for smaller spaces
            return getInverseBasicGradient(space);
        }
    }

    /**
     * BASIC inverse gradient for top generation
     * Creates a symmetric hill: low at edges, high in the middle (up to 7)
     */
    private int[] getInverseBasicGradient(int space) {
        // Symmetric hill patterns reaching up to 7
        if (space >= 7) {
            return new int[]{1, 3, 5, 7, 5, 3, 1};
        } else if (space == 6) {
            return new int[]{1, 3, 5, 5, 3, 1};
        } else if (space == 5) {
            return new int[]{1, 3, 5, 3, 1};
        } else if (space == 4) {
            return new int[]{2, 5, 5, 2};
        } else if (space == 3) {
            return new int[]{2, 5, 2};
        } else if (space == 2) {
            return new int[]{4, 4};
        } else if (space == 1) {
            return new int[]{4};
        }
        return new int[0];
    }

    /**
     * EXTREME mode gradients with triple repeated values
     * Creates very gradual transitions: 7,7,7,6,6,6,5,5,5,4,4,4,3,3,3,2,2,2,1,1,1
     */
    private int[] getExtremeNormalGradient(int space) {
        // Extreme gradients with triple repetition for very long distances (15-21 blocks)
        if (space >= 21) {
            return new int[]{7, 7, 7, 6, 6, 6, 5, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 2, 1, 1, 1};
        } else if (space >= 20) {
            return new int[]{7, 7, 7, 6, 6, 6, 5, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 1, 1, 1};
        } else if (space >= 19) {
            return new int[]{7, 7, 7, 6, 6, 6, 5, 5, 5, 4, 4, 3, 3, 3, 2, 2, 1, 1, 1};
        } else if (space >= 18) {
            return new int[]{7, 7, 6, 6, 6, 5, 5, 5, 4, 4, 4, 3, 3, 3, 2, 2, 1, 1};
        } else if (space >= 17) {
            return new int[]{7, 7, 6, 6, 6, 5, 5, 5, 4, 4, 3, 3, 3, 2, 2, 1, 1};
        } else if (space >= 16) {
            return new int[]{7, 7, 6, 6, 5, 5, 5, 4, 4, 4, 3, 3, 2, 2, 1, 1};
        } else if (space >= 15) {
            return new int[]{7, 7, 6, 6, 5, 5, 4, 4, 4, 3, 3, 2, 2, 1, 1};
        }
        // For space <= 14, fall back to extended gradients
        else {
            return getExtendedNormalGradient(space);
        }
    }

    /**
     * EXTENDED mode gradients with repeated values
     * Creates gradual transitions: 7,7,6,6,5,5,4,4,3,3,2,2,1,1
     */
    private int[] getExtendedNormalGradient(int space) {
        // Extended gradients with repetition for longer distances
        if (space >= 14) {
            return new int[]{7, 7, 6, 6, 5, 5, 4, 4, 3, 3, 2, 2, 1, 1};
        } else if (space >= 13) {
            return new int[]{7, 7, 6, 6, 5, 4, 4, 3, 3, 2, 2, 1, 1};
        } else if (space >= 12) {
            return new int[]{7, 7, 6, 6, 5, 4, 4, 3, 2, 2, 1, 1};
        } else if (space >= 11) {
            return new int[]{7, 6, 6, 5, 4, 4, 3, 2, 2, 1, 1};
        } else if (space >= 10) {
            return new int[]{7, 6, 6, 5, 4, 4, 3, 2, 2, 1};
        } else if (space >= 9) {
            return new int[]{7, 6, 6, 5, 4, 4, 3, 2, 1};
        } else if (space >= 8) {
            return new int[]{7, 6, 5, 4, 4, 3, 2, 1};
        }
        // For space <= 7, fall back to basic gradients
        else {
            return getNormalGradient(space);
        }
    }

    private int[] getNormalGradient(int space) {
        // BASIC mode: linear gradients
        if (space >= 7) {
            return new int[]{7, 6, 5, 4, 3, 2, 1};
        } else if (space == 6) {
            return new int[]{7, 6, 5, 3, 2, 1};
        } else if (space == 5) {
            return new int[]{6, 5, 4, 2, 1};
        } else if (space == 4) {
            return new int[]{7, 5, 3, 1};
        } else if (space == 3) {
            return new int[]{6, 4, 2};
        } else if (space == 2) {
            return new int[]{5, 2};
        } else if (space == 1) {
            return new int[]{4};
        }
        return new int[0];
    }
    
    /**
     * Place all layer blocks
     */
    private int placeAllLayers(Map<BlockPos, Integer> layerValues, 
                              Map<BlockPos, Block> surfaceBlocks, 
                              boolean replacePlants) {
        int blocksGenerated = 0;
        int plantsReplaced = 0;
        
        for (Map.Entry<BlockPos, Integer> entry : layerValues.entrySet()) {
            BlockPos surfacePos = entry.getKey();
            int layerCount = entry.getValue();
            Block surfaceBlock = surfaceBlocks.get(surfacePos);
            
            if (surfaceBlock == null) continue;
            
            Block layerBlock = mappingRegistry.getLayerBlock(surfaceBlock, layerCount);
            if (layerBlock == null) continue;
            
            BlockPos layerPos = surfacePos.up();
            BlockState existingState = world.getBlockState(layerPos);
            Block existingBlock = existingState.getBlock();

            // Handle plant replacement
            if (replacePlants && !existingState.isAir()) {
                
                if (plantMappingRegistry.isConquestPlant(existingBlock)) {
                    continue; // Skip existing conquest plants
                }
                
                if (plantMappingRegistry.isReplaceablePlant(existingBlock)) {
                    boolean isTallPlant = isTallPlant(existingBlock);
                    
                    if (isTallPlant) {
                        BlockPos upperPos = layerPos.up();
                        BlockState upperState = world.getBlockState(upperPos);
                        Block upperPlantBlock = upperState.getBlock();
                        plantDataStorage.storePlant(upperPos, upperPlantBlock);
                    }
                    
                    plantDataStorage.storePlant(layerPos, existingBlock);
                    if (isTallPlant) {
                        plantDataStorage.storeTallPlant(layerPos, true);
                    }
                    
                    // Place layer
                    BlockState layerState = layerBlock.getDefaultState();
                    if (layerState.contains(Properties.LAYERS)) {
                        layerState = layerState.with(Properties.LAYERS, Math.min(layerCount, 8));
                    }
                    // Check if we should waterlog
                    if (layerState.contains(Properties.WATERLOGGED) && existingState.getBlock() == Blocks.WATER) {
                        layerState = layerState.with(Properties.WATERLOGGED, true);
                    }
                    world.setBlockState(layerPos, layerState);

                    // Place Conquest plant on top - matching the layer height beneath it
                    Block conquestPlant = plantMappingRegistry.getConquestPlant(existingBlock);
                    if (conquestPlant != null) {
                        BlockPos plantPos = layerPos.up();
                        
                        // Get the layer count we just placed (the block beneath the plant)
                        int plantLayerCount = layerCount;
                        
                        if (isTallPlant && isTallPlant(conquestPlant)) {
                            BlockState lowerState = conquestPlant.getDefaultState();
                            if (lowerState.contains(Properties.DOUBLE_BLOCK_HALF)) {
                                lowerState = lowerState.with(Properties.DOUBLE_BLOCK_HALF, 
                                    DoubleBlockHalf.LOWER);
                            }
                            if (lowerState.contains(Properties.LAYERS)) {
                                lowerState = lowerState.with(Properties.LAYERS, Math.min(plantLayerCount, 8));
                            }
                            world.setBlockState(plantPos, lowerState);
                            
                            BlockPos upperPlantPos = plantPos.up();
                            if (world.getBlockState(upperPlantPos).isAir()) {
                                BlockState upperState = conquestPlant.getDefaultState();
                                if (upperState.contains(Properties.DOUBLE_BLOCK_HALF)) {
                                    upperState = upperState.with(Properties.DOUBLE_BLOCK_HALF,
                                        DoubleBlockHalf.UPPER);
                                }
                                if (upperState.contains(Properties.LAYERS)) {
                                    upperState = upperState.with(Properties.LAYERS, Math.min(plantLayerCount, 8));
                                }
                                world.setBlockState(upperPlantPos, upperState);
                            }
                        } else {
                            if (world.getBlockState(plantPos).isAir()) {
                                BlockState plantState = conquestPlant.getDefaultState();
                                if (plantState.contains(Properties.LAYERS)) {
                                    plantState = plantState.with(Properties.LAYERS, Math.min(plantLayerCount, 8));
                                }
                                world.setBlockState(plantPos, plantState);
                            }
                        }
                        plantsReplaced++;
                    }
                    
                    blocksGenerated++;
                    continue;
                }
            }
            
            // Normal placement
            if (existingState.isAir() || existingState.isReplaceable() || existingState.getBlock() == Blocks.WATER) {
                BlockState layerState = layerBlock.getDefaultState();

                if (layerState.contains(Properties.LAYERS)) {
                    layerState = layerState.with(Properties.LAYERS, Math.min(layerCount, 8));
                }
                // Check if we should waterlog
                if (layerState.contains(Properties.WATERLOGGED) && existingState.getBlock() == Blocks.WATER) {
                    layerState = layerState.with(Properties.WATERLOGGED, true);
                }

                world.setBlockState(layerPos, layerState);
                blocksGenerated++;
            }
        }
        
        if (plantsReplaced > 0) {
            CRLayers.LOGGER.info("Replaced {} plants with Conquest variants", plantsReplaced);
        }
        
        return blocksGenerated;
    }
    
    /**
     * Remove layers in chunk radius
     */
    public int removeLayers(BlockPos center, int chunkRadius, boolean restorePlants) {
        ChunkPos centerChunk = new ChunkPos(center);
        int blocksRemoved = 0;
        int plantsRestored = 0;
        
        for (int dx = -chunkRadius; dx <= chunkRadius; dx++) {
            for (int dz = -chunkRadius; dz <= chunkRadius; dz++) {
                ChunkPos chunkPos = new ChunkPos(centerChunk.x + dx, centerChunk.z + dz);
                
                if (!world.isChunkLoaded(chunkPos.x, chunkPos.z)) continue;
                
                for (int x = chunkPos.getStartX(); x <= chunkPos.getStartX() + 15; x++) {
                    for (int z = chunkPos.getStartZ(); z <= chunkPos.getStartZ() + 15; z++) {
                        BlockPos columnPos = new BlockPos(x, 0, z);
                        BlockPos surfacePos = findSurfaceBlock(columnPos);
                        
                        if (surfacePos == null) continue;
                        
                        BlockPos layerPos = surfacePos.up();
                        BlockState layerState = world.getBlockState(layerPos);
                        Block layerBlock = layerState.getBlock();
                        
                        if (isLayerBlock(layerBlock)) {
                            BlockPos plantPos = layerPos.up();
                            BlockState plantState = world.getBlockState(plantPos);
                            Block plantBlock = plantState.getBlock();
                            
                            if (restorePlants) {
                                if (plantMappingRegistry.isConquestPlant(plantBlock)) {
                                    if (isTallPlant(plantBlock)) {
                                        BlockPos upperPos = plantPos.up();
                                        world.setBlockState(upperPos, Blocks.AIR.getDefaultState());
                                    }
                                    world.setBlockState(plantPos, Blocks.AIR.getDefaultState());
                                }
                                
                                if (plantDataStorage.hasPlantData(layerPos)) {
                                    Block originalPlant = plantDataStorage.getPlant(layerPos);
                                    boolean wasTallPlant = plantDataStorage.isTallPlant(layerPos);
                                    
                                    if (originalPlant != null) {
                                        if (wasTallPlant && isTallPlant(originalPlant)) {
                                            BlockState lowerState = originalPlant.getDefaultState();
                                            if (lowerState.contains(Properties.DOUBLE_BLOCK_HALF)) {
                                                lowerState = lowerState.with(Properties.DOUBLE_BLOCK_HALF,
                                                    DoubleBlockHalf.LOWER);
                                            }
                                            world.setBlockState(layerPos, lowerState);
                                            
                                            BlockPos upperPos = layerPos.up();
                                            Block upperPlant = plantDataStorage.getPlant(upperPos);
                                            if (upperPlant != null) {
                                                BlockState upperState = upperPlant.getDefaultState();
                                                if (upperState.contains(Properties.DOUBLE_BLOCK_HALF)) {
                                                    upperState = upperState.with(Properties.DOUBLE_BLOCK_HALF,
                                                        DoubleBlockHalf.UPPER);
                                                }
                                                world.setBlockState(upperPos, upperState);
                                                plantDataStorage.removePlant(upperPos);
                                            }
                                        } else {
                                            world.setBlockState(layerPos, originalPlant.getDefaultState());
                                        }
                                        
                                        plantDataStorage.removePlant(layerPos);
                                        plantDataStorage.removeTallPlantFlag(layerPos);
                                        plantsRestored++;
                                        blocksRemoved++;
                                        continue;
                                    }
                                }
                            }
                            
                            world.setBlockState(layerPos, Blocks.AIR.getDefaultState());
                            blocksRemoved++;
                        }
                    }
                }
            }
        }
        
        if (restorePlants && plantsRestored > 0) {
            plantDataStorage.save();
            CRLayers.LOGGER.info("Restored {} vanilla plants", plantsRestored);
        }
        
        CRLayers.LOGGER.info("Removed {} layer blocks", blocksRemoved);
        return blocksRemoved;
    }
    
    // ==================== Helper Methods ====================
    
    private boolean isLayerBlock(Block block) {
        if (block == Blocks.SNOW) return true;
        String blockId = net.minecraft.registry.Registries.BLOCK.getId(block).toString();
        return blockId.contains("layer") || blockId.contains("slab");
    }
    
    private boolean isTallPlant(Block block) {
        return block == Blocks.TALL_GRASS ||
               block == Blocks.LARGE_FERN ||
               block == Blocks.SUNFLOWER ||
               block == Blocks.LILAC ||
               block == Blocks.ROSE_BUSH ||
               block == Blocks.PEONY ||
               block == Blocks.TALL_SEAGRASS ||
               block.getDefaultState().contains(Properties.DOUBLE_BLOCK_HALF);
    }
    
    private BlockPos findSurfaceBlock(BlockPos pos) {
        // Start from ocean floor heightmap (includes underwater terrain)
        int surfaceY = world.getTopY(Heightmap.Type.OCEAN_FLOOR, pos.getX(), pos.getZ());
        BlockPos surfacePos = new BlockPos(pos.getX(), surfaceY, pos.getZ());
        
        // Walk down to find actual solid terrain (skip air, plants, leaves)
        while (surfacePos.getY() > world.getBottomY()) {
            BlockState state = world.getBlockState(surfacePos);
            Block block = state.getBlock();
            
            // Skip air
            if (state.isAir()) {
                surfacePos = surfacePos.down();
                continue;
            }

            // Skip water to find solid ground underneath
            if (block == Blocks.WATER) {
                surfacePos = surfacePos.down();
                continue;
            }

            // Skip leaves
            if (block.getDefaultState().isIn(net.minecraft.registry.tag.BlockTags.LEAVES)) {
                surfacePos = surfacePos.down();
                continue;
            }

            // Skip logs (wood blocks)
            if (block.getDefaultState().isIn(net.minecraft.registry.tag.BlockTags.LOGS)) {
                surfacePos = surfacePos.down();
                continue;
            }

            // Skip mushroom blocks (big mushroom structures)
            if (block == Blocks.BROWN_MUSHROOM_BLOCK ||
                block == Blocks.RED_MUSHROOM_BLOCK ||
                block == Blocks.MUSHROOM_STEM) {
                surfacePos = surfacePos.down();
                continue;
            }

            // Skip plants and flowers
            if (block.getDefaultState().isIn(net.minecraft.registry.tag.BlockTags.FLOWERS) ||
                block.getDefaultState().isIn(net.minecraft.registry.tag.BlockTags.SAPLINGS) ||
                block == Blocks.TALL_GRASS ||
                block == Blocks.GRASS ||
                block == Blocks.TALL_SEAGRASS ||
                block == Blocks.SEAGRASS ||
                block == Blocks.FERN ||
                block == Blocks.LARGE_FERN ||
                block == Blocks.DEAD_BUSH) {
                surfacePos = surfacePos.down();
                continue;
            }

            // Accept dirt_path as valid surface (even though it's not a full cube)
            if (block == Blocks.DIRT_PATH) {
                return surfacePos;
            }

            // Skip non-full blocks (slabs, stairs, etc.)
            if (!state.isFullCube(world, surfacePos)) {
                surfacePos = surfacePos.down();
                continue;
            }

            // Found solid surface block
            return surfacePos;
        }
        
        return null;
    }
    
    private boolean canGenerateLayersOn(Block block) {
        // Check if the block has a mapping in the config file
        return mappingRegistry.hasMapping(block);
    }

    /**
     * Export debug data showing Y levels and layer values in a grid
     * @param center Center position
     * @param radius Radius in blocks (not chunks)
     * @return Path to exported file
     */
    public String debugExport(BlockPos center, int radius) {
        StringBuilder logOutput = new StringBuilder();
        StringBuilder fileOutput = new StringBuilder();

        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;

        // Collect ALL surface heights from a LARGER area (radius + 3 blocks buffer)
        // This ensures edge detection works correctly at visualization boundaries
        int buffer = 3;
        Map<BlockPos, Integer> allSurfaceHeights = new HashMap<>();

        for (int x = minX - buffer; x <= maxX + buffer; x++) {
            for (int z = minZ - buffer; z <= maxZ + buffer; z++) {
                BlockPos columnPos = new BlockPos(x, 0, z);
                BlockPos surfacePos = findSurfaceBlock(columnPos);

                if (surfacePos != null) {
                    allSurfaceHeights.put(surfacePos, surfacePos.getY());
                }
            }
        }
        
        // Collect VALID surface heights (filtered)
        Map<BlockPos, Integer> validSurfaceHeights = new HashMap<>();
        Map<BlockPos, Block> surfaceBlocks = new HashMap<>();
        
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                BlockPos columnPos = new BlockPos(x, 0, z);
                BlockPos surfacePos = findSurfaceBlock(columnPos);
                
                if (surfacePos != null) {
                    BlockState surfaceState = world.getBlockState(surfacePos);
                    Block surfaceBlock = surfaceState.getBlock();
                    
                    if (canGenerateLayersOn(surfaceBlock)) {
                        validSurfaceHeights.put(surfacePos, surfacePos.getY());
                        surfaceBlocks.put(surfacePos, surfaceBlock);
                    }
                }
            }
        }
        
        // Collect ACTUAL layers currently in world
        Map<BlockPos, Integer> actualLayers = new HashMap<>();
        for (Map.Entry<BlockPos, Integer> entry : validSurfaceHeights.entrySet()) {
            BlockPos surfacePos = entry.getKey();
            BlockPos layerPos = surfacePos.up();
            BlockState layerState = world.getBlockState(layerPos);
            
            if (isLayerBlock(layerState.getBlock())) {
                if (layerState.contains(Properties.LAYERS)) {
                    actualLayers.put(surfacePos, layerState.get(Properties.LAYERS));
                } else {
                    actualLayers.put(surfacePos, 1);
                }
            }
        }
        
        // Get all unique Y levels and sort
        Set<Integer> allYLevels = new HashSet<>(allSurfaceHeights.values());
        List<Integer> sortedYLevels = new ArrayList<>(allYLevels);
        Collections.sort(sortedYLevels, Collections.reverseOrder());
        
        // Build header
        String header = String.format("Debug Export - Center: %s, Radius: %d blocks\n", center, radius);
        header += String.format("Area: X=%d to %d, Z=%d to %d\n", minX, maxX, minZ, maxZ);
        header += String.format("Mode: %s, Max Distance: %d\n", LayerConfig.MODE, LayerConfig.MAX_LAYER_DISTANCE);
        header += String.format("Smoothing Cycles: %d, Rounding: %s\n\n", LayerConfig.SMOOTHING_CYCLES, LayerConfig.SMOOTHING_ROUNDING_MODE);
        
        logOutput.append(header);
        fileOutput.append(header);
        
        // Show Y levels
        logOutput.append("=== Y LEVELS FOUND ===\n");
        fileOutput.append("=== Y LEVELS FOUND ===\n");
        for (int y : sortedYLevels) {
            int count = 0;
            for (int height : allSurfaceHeights.values()) {
                if (height == y) count++;
            }
            String line = String.format("Y=%d: %d blocks\n", y, count);
            logOutput.append(line);
            fileOutput.append(line);
        }
        logOutput.append("\n");
        fileOutput.append("\n");
        
        // Calculate what layers WOULD be placed with current algorithm
        // Process chunks sequentially like actual generation to match behavior
        Set<ChunkPos> allChunksInArea = new HashSet<>();
        for (Map.Entry<BlockPos, Integer> entry : allSurfaceHeights.entrySet()) {
            allChunksInArea.add(new ChunkPos(entry.getKey()));
        }

        Map<BlockPos, Integer> edges = identifyEdges(allSurfaceHeights);
        Map<BlockPos, Integer> calculatedLayers = new HashMap<>();

        // Process each chunk sequentially (like actual generation)
        for (ChunkPos chunkPos : allChunksInArea) {
            Set<ChunkPos> singleChunk = new HashSet<>();
            singleChunk.add(chunkPos);

            // Scan for existing layers from neighbors (including already-processed chunks in this debug run)
            Map<BlockPos, Integer> existingLayers = scanExistingLayers(allChunksInArea, chunkPos, allSurfaceHeights);

            // Also include layers we've already calculated in previous iterations
            existingLayers.putAll(calculatedLayers);

            Map<BlockPos, Integer> chunkLayers = calculateLayerValues(
                validSurfaceHeights,
                allSurfaceHeights,
                edges,
                singleChunk,
                existingLayers
            );

            // Add this chunk's layers to the overall result
            calculatedLayers.putAll(chunkLayers);
        }

        // Use allChunksInArea for classification (all chunks in visualization area)
        Set<ChunkPos> targetChunks = allChunksInArea;
        
        // Create XZ lookup for heightmap
        Map<String, Integer> xzToHeight = new HashMap<>();
        for (Map.Entry<BlockPos, Integer> entry : allSurfaceHeights.entrySet()) {
            String key = entry.getKey().getX() + "," + entry.getKey().getZ();
            xzToHeight.put(key, entry.getValue());
        }
        
        // Track E blocks from previous Y level (for H block inheritance)
        Set<BlockPos> previousEBlocks = new HashSet<>();
        
        // Build output for each Y level
        for (int currentY : sortedYLevels) {
            logOutput.append(String.format("=== Y LEVEL %d ===\n\n", currentY));
            fileOutput.append(String.format("=== Y LEVEL %d ===\n\n", currentY));
            
            // Classify blocks at this Y level using same logic as generation
            Set<BlockPos> hBlocks = new HashSet<>();
            Set<BlockPos> eBlocks = new HashSet<>();
            Set<BlockPos> lBlocks = new HashSet<>();
            
            classifyBlocksAtYLevel(currentY, allSurfaceHeights, xzToHeight, validSurfaceHeights, 
                targetChunks, previousEBlocks, hBlocks, eBlocks, lBlocks);
            
            // Create position lookup for this Y level
            Map<String, BlockPos> xzLookup = new HashMap<>();
            for (Map.Entry<BlockPos, Integer> entry : allSurfaceHeights.entrySet()) {
                if (entry.getValue() == currentY) {
                    String key = entry.getKey().getX() + "," + entry.getKey().getZ();
                    xzLookup.put(key, entry.getKey());
                }
            }
            
            if (xzLookup.isEmpty()) {
                previousEBlocks = eBlocks;
                continue;
            }
            
            // Actual layers at this Y
            String actualMatrix = buildMatrix(
                String.format("ACTUAL LAYERS (Y=%d) - H=Higher Edge, E=Lower Edge, 0-7=Layers", currentY), 
                minX, maxX, minZ, maxZ,
                (x, z) -> {
                    String key = x + "," + z;
                    BlockPos pos = xzLookup.get(key);
                    if (pos == null) return " --";
                    
                    if (hBlocks.contains(pos)) return " H ";
                    if (eBlocks.contains(pos)) return " E ";
                    
                    Integer layers = actualLayers.get(pos);
                    if (layers == null) return " 0 ";
                    return String.format(" %d ", layers);
                });
            
            logOutput.append(actualMatrix).append("\n");
            fileOutput.append(actualMatrix).append("\n");
            
            // Calculated layers at this Y
            String calculatedMatrix = buildMatrix(
                String.format("CALCULATED LAYERS (Y=%d) - H=Higher Edge, E=Lower Edge, 0-7=Layers", currentY), 
                minX, maxX, minZ, maxZ,
                (x, z) -> {
                    String key = x + "," + z;
                    BlockPos pos = xzLookup.get(key);
                    if (pos == null) return " --";
                    
                    if (hBlocks.contains(pos)) return " H ";
                    if (eBlocks.contains(pos)) return " E ";
                    
                    Integer layers = calculatedLayers.get(pos);
                    if (layers == null) return " 0 ";
                    return String.format(" %d ", layers);
                });
            
            logOutput.append(calculatedMatrix).append("\n\n");
            fileOutput.append(calculatedMatrix).append("\n\n");
            
            // Remember E blocks for next (lower) Y level
            previousEBlocks = eBlocks;
        }
        
        // Log to console
        CRLayers.LOGGER.info("\n" + logOutput.toString());
        
        // Write to file
        try {
            Path worldDir = world.getServer().getSavePath(WorldSavePath.ROOT);
            Path debugFile = worldDir.resolve("crlayers_debug.txt");
            
            java.nio.file.Files.writeString(debugFile, fileOutput.toString());
            
            return debugFile.toString();
        } catch (Exception e) {
            CRLayers.LOGGER.error("Failed to write debug file", e);
            return "ERROR: " + e.getMessage();
        }
    }

    /**
     * Build a grid matrix string
     */
    private String buildMatrix(String title, int minX, int maxX, int minZ, int maxZ, 
                            java.util.function.BiFunction<Integer, Integer, String> cellProvider) {
        StringBuilder sb = new StringBuilder();
        
        sb.append("=== ").append(title).append(" ===\n");
        
        // Header row (X coordinates)
        sb.append("     ");
        for (int x = minX; x <= maxX; x++) {
            sb.append(String.format("%4d", x));
        }
        sb.append("\n");
        
        // Data rows
        for (int z = minZ; z <= maxZ; z++) {
            sb.append(String.format("%4d ", z));
            for (int x = minX; x <= maxX; x++) {
                sb.append(cellProvider.apply(x, z));
            }
            sb.append("\n");
        }
        
        return sb.toString();
    }
}
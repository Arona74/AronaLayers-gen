package io.arona74.crlayers;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

public class LayerGeneratorCommand {
    
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        // Main generation command (always handles plants)
        dispatcher.register(CommandManager.literal("generateLayers")
            .requires(source -> source.hasPermissionLevel(2))
            .then(CommandManager.argument("chunkRadius", IntegerArgumentType.integer(1, 32))
                .executes(LayerGeneratorCommand::executeWithRadius))
            .executes(LayerGeneratorCommand::executeDefault)
        );
        
        // Remove layers command (always restores plants)
        dispatcher.register(CommandManager.literal("removeLayers")
            .requires(source -> source.hasPermissionLevel(2))
            .then(CommandManager.argument("chunkRadius", IntegerArgumentType.integer(1, 32))
                .executes(LayerGeneratorCommand::executeRemoveWithRadius))
            .executes(LayerGeneratorCommand::executeRemoveDefault)
        );

        // Async generation command (doesn't freeze server)
        dispatcher.register(CommandManager.literal("generateLayersAsync")
            .requires(source -> source.hasPermissionLevel(2))
            .then(CommandManager.argument("chunkRadius", IntegerArgumentType.integer(1, 32))
                .executes(LayerGeneratorCommand::executeAsyncWithRadius))
            .executes(LayerGeneratorCommand::executeAsyncDefault)
        );

        // Cancel async generation command
        dispatcher.register(CommandManager.literal("cancelLayerGeneration")
            .requires(source -> source.hasPermissionLevel(2))
            .executes(LayerGeneratorCommand::executeCancel)
        );

        // Configuration commands
        dispatcher.register(CommandManager.literal("layerConfig")
            .requires(source -> source.hasPermissionLevel(2))
            
            .then(CommandManager.literal("show")
                .executes(LayerGeneratorCommand::showConfig))
            
            .then(CommandManager.literal("mode")
                .then(CommandManager.argument("mode", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        builder.suggest("basic");
                        builder.suggest("extended");
                        builder.suggest("extreme");
                        return builder.buildFuture();
                    })
                    .executes(LayerGeneratorCommand::setMode)))
            
            .then(CommandManager.literal("distance")
                .then(CommandManager.argument("blocks", IntegerArgumentType.integer(3, 25))
                    .executes(LayerGeneratorCommand::setDistance)))
            
            .then(CommandManager.literal("edgeThreshold")
                .then(CommandManager.argument("blocks", IntegerArgumentType.integer(1, 5))
                    .executes(LayerGeneratorCommand::setEdgeThreshold)))
            
            .then(CommandManager.literal("smoothingCycles")
                .then(CommandManager.argument("cycles", IntegerArgumentType.integer(0, 20))
                    .executes(LayerGeneratorCommand::setSmoothingCycles)))
            
            .then(CommandManager.literal("roundingMode")
                .then(CommandManager.argument("mode", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        builder.suggest("up");
                        builder.suggest("down");
                        builder.suggest("nearest");
                        return builder.buildFuture();
                    })
                    .executes(LayerGeneratorCommand::setRoundingMode)))

            .then(CommandManager.literal("smoothingPriority")
                .then(CommandManager.argument("priority", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        builder.suggest("up");
                        builder.suggest("down");
                        return builder.buildFuture();
                    })
                    .executes(LayerGeneratorCommand::setSmoothingPriority)))

            .then(CommandManager.literal("topGeneration")
                .then(CommandManager.argument("enabled", BoolArgumentType.bool())
                    .executes(LayerGeneratorCommand::setTopGeneration)))

            .then(CommandManager.literal("reload")
                .executes(LayerGeneratorCommand::reloadConfig))

            .then(CommandManager.literal("preset")
                .then(CommandManager.argument("preset", StringArgumentType.word())
                    .suggests((context, builder) -> {
                        builder.suggest("basic");
                        builder.suggest("extended");
                        builder.suggest("extreme");
                        return builder.buildFuture();
                    })
                    .executes(LayerGeneratorCommand::applyPreset)))
        );

        // Debug log & file command
        dispatcher.register(CommandManager.literal("debugLayers")
            .requires(source -> source.hasPermissionLevel(2))
            .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 10))
                .executes(LayerGeneratorCommand::executeDebug))
            .executes(context -> executeDebug(context, 3))
        );
    }
    
    // ==================== Generation Commands ====================
    
    private static int executeDefault(CommandContext<ServerCommandSource> context) {
        return execute(context, 3, true); // Always handle plants
    }
    
    private static int executeWithRadius(CommandContext<ServerCommandSource> context) {
        int chunkRadius = IntegerArgumentType.getInteger(context, "chunkRadius");
        return execute(context, chunkRadius, true); // Always handle plants
    }
    
    private static int execute(CommandContext<ServerCommandSource> context, int chunkRadius, boolean replacePlants) {
        ServerCommandSource source = context.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("This command can only be used by players"));
            return 0;
        }
        
        BlockPos playerPos = player.getBlockPos();
        
        String configInfo = String.format("Mode: %s, Distance: %d blocks, Edge threshold: %d", 
            LayerConfig.MODE.name(),
            LayerConfig.MAX_LAYER_DISTANCE,
            LayerConfig.EDGE_HEIGHT_THRESHOLD);
        source.sendFeedback(() -> Text.literal("§7" + configInfo), false);
        
        int blockRadius = chunkRadius * 16;
        String message = String.format("§eGenerating layers in %d chunk radius (~%d blocks) with plant handling...", chunkRadius, blockRadius);
        source.sendFeedback(() -> Text.literal(message), false);
        
        LayerGenerator generator = new LayerGenerator(player.getServerWorld());
        
        try {
            long startTime = System.currentTimeMillis();
            int blocksGenerated = generator.generateLayers(playerPos, chunkRadius, replacePlants);
            long duration = System.currentTimeMillis() - startTime;
            
            if (blocksGenerated == 0) {
                source.sendFeedback(() -> Text.literal("§eNo layers generated. Possible reasons:"), false);
                source.sendFeedback(() -> Text.literal("§7- Terrain is completely flat (no height changes)"), false);
                source.sendFeedback(() -> Text.literal("§7- Standing on invalid blocks"), false);
                source.sendFeedback(() -> Text.literal("§7- Near water"), false);
                return 1;
            }
            
            source.sendFeedback(() -> Text.literal(
                String.format("§aGenerated %d layer blocks! §7(took %dms)", blocksGenerated, duration)), 
                true);
            return 1;
            
        } catch (Exception e) {
            CRLayers.LOGGER.error("Error generating layers", e);
            source.sendError(Text.literal("§cError: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== Async Generation Commands ====================

    private static int executeAsyncDefault(CommandContext<ServerCommandSource> context) {
        return executeAsync(context, 3, true);
    }

    private static int executeAsyncWithRadius(CommandContext<ServerCommandSource> context) {
        int chunkRadius = IntegerArgumentType.getInteger(context, "chunkRadius");
        return executeAsync(context, chunkRadius, true);
    }

    private static int executeAsync(CommandContext<ServerCommandSource> context, int chunkRadius, boolean replacePlants) {
        ServerCommandSource source = context.getSource();

        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("This command can only be used by players"));
            return 0;
        }

        BlockPos playerPos = player.getBlockPos();

        String configInfo = String.format("Mode: %s, Distance: %d blocks",
            LayerConfig.MODE.name(),
            LayerConfig.MAX_LAYER_DISTANCE);
        source.sendFeedback(() -> Text.literal("§7" + configInfo), false);

        int blockRadius = chunkRadius * 16;
        String message = String.format("§eStarting ASYNC generation in %d chunk radius (~%d blocks)...", chunkRadius, blockRadius);
        source.sendFeedback(() -> Text.literal(message), false);
        source.sendFeedback(() -> Text.literal("§7Generation running in background - server won't freeze!"), false);

        LayerGenerator generator = new LayerGenerator(player.getServerWorld());

        try {
            long startTime = System.currentTimeMillis();

            generator.generateLayersAsync(playerPos, chunkRadius, replacePlants, source)
                .thenAccept(blocksGenerated -> {
                    long duration = System.currentTimeMillis() - startTime;

                    if (blocksGenerated == 0) {
                        source.sendFeedback(() -> Text.literal("§eNo layers generated."), false);
                    } else {
                        source.sendFeedback(() -> Text.literal(
                            String.format("§aAsync generation complete! Generated %d blocks §7(took %dms)",
                            blocksGenerated, duration)), true);
                    }
                })
                .exceptionally(e -> {
                    CRLayers.LOGGER.error("Error in async generation", e);
                    source.sendError(Text.literal("§cAsync generation error: " + e.getMessage()));
                    return null;
                });

            source.sendFeedback(() -> Text.literal("§aGeneration started! You can continue playing."), false);
            return 1;

        } catch (Exception e) {
            CRLayers.LOGGER.error("Error starting async generation", e);
            source.sendError(Text.literal("§cError: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== Cancel Command ====================

    private static int executeCancel(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        try {
            if (LayerGenerator.cancelAsyncGeneration()) {
                source.sendFeedback(() -> Text.literal("§eAsync layer generation cancelled!"), false);
                return 1;
            } else {
                source.sendFeedback(() -> Text.literal("§7No async generation is currently running."), false);
                return 0;
            }
        } catch (Exception e) {
            CRLayers.LOGGER.error("Error cancelling generation", e);
            source.sendError(Text.literal("§cError cancelling generation: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== Removal Commands ====================

    private static int executeRemoveDefault(CommandContext<ServerCommandSource> context) {
        return executeRemove(context, 3, true); // Always restore plants
    }
    
    private static int executeRemoveWithRadius(CommandContext<ServerCommandSource> context) {
        int chunkRadius = IntegerArgumentType.getInteger(context, "chunkRadius");
        return executeRemove(context, chunkRadius, true); // Always restore plants
    }
    
    private static int executeRemove(CommandContext<ServerCommandSource> context, int chunkRadius, boolean restorePlants) {
        ServerCommandSource source = context.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("This command can only be used by players"));
            return 0;
        }
        
        BlockPos playerPos = player.getBlockPos();
        
        int blockRadius = chunkRadius * 16;
        String message = String.format("§eRemoving layers in %d chunk radius (~%d blocks) and restoring plants...", chunkRadius, blockRadius);
        source.sendFeedback(() -> Text.literal(message), false);
        
        LayerGenerator generator = new LayerGenerator(player.getServerWorld());
        
        try {
            long startTime = System.currentTimeMillis();
            int blocksRemoved = generator.removeLayers(playerPos, chunkRadius, restorePlants);
            long duration = System.currentTimeMillis() - startTime;
            
            source.sendFeedback(() -> Text.literal(
                String.format("§aRemoved %d layer blocks! §7(took %dms)", blocksRemoved, duration)), 
                true);
            return 1;
            
        } catch (Exception e) {
            CRLayers.LOGGER.error("Error removing layers", e);
            source.sendError(Text.literal("§cError: " + e.getMessage()));
            return 0;
        }
    }
    
    // ==================== Configuration Commands ====================
    
    private static int showConfig(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        source.sendFeedback(() -> Text.literal("§6=== Layer Configuration ==="), false);
        source.sendFeedback(() -> Text.literal(String.format(
            "§eMode: §f%s", LayerConfig.MODE.name())), false);
        source.sendFeedback(() -> Text.literal(String.format(
            "§eMax Distance: §f%d blocks", LayerConfig.MAX_LAYER_DISTANCE)), false);
        source.sendFeedback(() -> Text.literal(String.format(
            "§eEdge Threshold: §f%d blocks", LayerConfig.EDGE_HEIGHT_THRESHOLD)), false);
        source.sendFeedback(() -> Text.literal(String.format(
            "§eSmoothing Cycles: §f%d", LayerConfig.SMOOTHING_CYCLES)), false);
        source.sendFeedback(() -> Text.literal(String.format(
            "§eRounding Mode: §f%s", LayerConfig.SMOOTHING_ROUNDING_MODE.name())), false);
        source.sendFeedback(() -> Text.literal(String.format(
            "§eSmoothing Priority: §f%s", LayerConfig.SMOOTHING_PRIORITY.name())), false);

        String topGenStatus = LayerConfig.TOP_GENERATION ? "§aENABLED" : "§cDISABLED";
        source.sendFeedback(() -> Text.literal(String.format(
            "§eTop Generation: %s", topGenStatus)), false);

        String modeDesc = switch (LayerConfig.MODE) {
            case BASIC -> "§7Linear gradient up to 7: 7→6→5→4→3→2→1";
            case EXTENDED -> "§7Gradual steps up to 14: 7,7→6,6→5,5→4,4→3,3→2,2→1,1";
            case EXTREME -> "§7Very gradual steps up to 21: 7,7,7→6,6,6→5,5,5→4,4,4→3,3,3→2,2,2→1,1,1";
        };
        source.sendFeedback(() -> Text.literal(modeDesc), false);

        return 1;
    }
    
    private static int setMode(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String modeName = StringArgumentType.getString(context, "mode").toLowerCase();
        
        LayerConfig.GenerationMode mode = switch (modeName) {
            case "basic" -> LayerConfig.GenerationMode.BASIC;
            case "extended" -> LayerConfig.GenerationMode.EXTENDED;
            case "extreme" -> LayerConfig.GenerationMode.EXTREME;
            default -> {
                source.sendError(Text.literal("§cUnknown mode. Use: basic, extended, or extreme"));
                yield null;
            }
        };
        
        if (mode == null) return 0;

        LayerConfig.MODE = mode;
        LayerConfig.save();
        source.sendFeedback(() -> Text.literal(
            String.format("§aSet mode to: §f%s", mode.name())), true);

        return 1;
    }
    
    private static int setDistance(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        int value = IntegerArgumentType.getInteger(context, "blocks");

        LayerConfig.MAX_LAYER_DISTANCE = value;
        LayerConfig.save();
        source.sendFeedback(() -> Text.literal(
            String.format("§aSet max layer distance to: §f%d blocks", value)), true);

        return 1;
    }
    
    private static int setEdgeThreshold(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        int value = IntegerArgumentType.getInteger(context, "blocks");

        LayerConfig.EDGE_HEIGHT_THRESHOLD = value;
        LayerConfig.save();
        source.sendFeedback(() -> Text.literal(
            String.format("§aSet edge threshold to: §f%d blocks", value)), true);

        return 1;
    }
    
    private static int setSmoothingCycles(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        int value = IntegerArgumentType.getInteger(context, "cycles");

        LayerConfig.SMOOTHING_CYCLES = value;
        LayerConfig.save();
        source.sendFeedback(() -> Text.literal(
            String.format("§aSet smoothing cycles to: §f%d", value)), true);

        return 1;
    }
    
    private static int setRoundingMode(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String modeName = StringArgumentType.getString(context, "mode").toLowerCase();
        
        LayerConfig.RoundingMode mode = switch (modeName) {
            case "up" -> LayerConfig.RoundingMode.UP;
            case "down" -> LayerConfig.RoundingMode.DOWN;
            case "nearest" -> LayerConfig.RoundingMode.NEAREST;
            default -> {
                source.sendError(Text.literal("§cUnknown rounding mode. Use: up, down, or nearest"));
                yield null;
            }
        };
        
        if (mode == null) return 0;

        LayerConfig.SMOOTHING_ROUNDING_MODE = mode;
        LayerConfig.save();
        source.sendFeedback(() -> Text.literal(
            String.format("§aSet rounding mode to: §f%s", mode.name())), true);

        return 1;
    }

    private static int setSmoothingPriority(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String priorityName = StringArgumentType.getString(context, "priority").toLowerCase();

        LayerConfig.SmoothingPriority priority = switch (priorityName) {
            case "up" -> LayerConfig.SmoothingPriority.UP;
            case "down" -> LayerConfig.SmoothingPriority.DOWN;
            default -> {
                source.sendError(Text.literal("§cUnknown smoothing priority. Use: up or down"));
                yield null;
            }
        };

        if (priority == null) return 0;

        LayerConfig.SMOOTHING_PRIORITY = priority;
        LayerConfig.save();
        source.sendFeedback(() -> Text.literal(
            String.format("§aSet smoothing priority to: §f%s", priority.name())), true);

        return 1;
    }

    private static int setTopGeneration(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        boolean enabled = BoolArgumentType.getBool(context, "enabled");

        LayerConfig.TOP_GENERATION = enabled;
        LayerConfig.save();

        String status = enabled ? "§aENABLED" : "§cDISABLED";
        source.sendFeedback(() -> Text.literal(
            String.format("§aTop generation mode: %s", status)), true);

        if (enabled) {
            source.sendFeedback(() -> Text.literal(
                "§7The highest Y-level will now generate inverse gradient hills between edges"), false);
        } else {
            source.sendFeedback(() -> Text.literal(
                "§7The highest Y-level will be skipped (default behavior)"), false);
        }

        return 1;
    }

    private static int reloadConfig(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();

        try {
            LayerConfig.reload();
            source.sendFeedback(() -> Text.literal("§aConfiguration reloaded from file!"), true);
            source.sendFeedback(() -> Text.literal(String.format(
                "§eMode: §f%s §7| §eDistance: §f%d §7| §eSmoothing: §f%d cycles",
                LayerConfig.MODE.name(), LayerConfig.MAX_LAYER_DISTANCE, LayerConfig.SMOOTHING_CYCLES)), false);
            return 1;
        } catch (Exception e) {
            CRLayers.LOGGER.error("Error reloading config", e);
            source.sendError(Text.literal("§cError reloading config: " + e.getMessage()));
            return 0;
        }
    }

    private static int applyPreset(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String presetName = StringArgumentType.getString(context, "preset").toLowerCase();

        try {
            switch (presetName) {
                case "basic" -> {
                    LayerConfig.MODE = LayerConfig.GenerationMode.BASIC;
                    LayerConfig.MAX_LAYER_DISTANCE = 7;
                    LayerConfig.EDGE_HEIGHT_THRESHOLD = 1;
                    LayerConfig.SMOOTHING_CYCLES = 6;
                    LayerConfig.SMOOTHING_ROUNDING_MODE = LayerConfig.RoundingMode.DOWN;
                    LayerConfig.SMOOTHING_PRIORITY = LayerConfig.SmoothingPriority.DOWN;
                }
                case "extended" -> {
                    LayerConfig.MODE = LayerConfig.GenerationMode.EXTENDED;
                    LayerConfig.MAX_LAYER_DISTANCE = 14;
                    LayerConfig.EDGE_HEIGHT_THRESHOLD = 1;
                    LayerConfig.SMOOTHING_CYCLES = 13;
                    LayerConfig.SMOOTHING_ROUNDING_MODE = LayerConfig.RoundingMode.DOWN;
                    LayerConfig.SMOOTHING_PRIORITY = LayerConfig.SmoothingPriority.DOWN;
                }
                case "extreme" -> {
                    LayerConfig.MODE = LayerConfig.GenerationMode.EXTREME;
                    LayerConfig.MAX_LAYER_DISTANCE = 21;
                    LayerConfig.EDGE_HEIGHT_THRESHOLD = 1;
                    LayerConfig.SMOOTHING_CYCLES = 20;
                    LayerConfig.SMOOTHING_ROUNDING_MODE = LayerConfig.RoundingMode.DOWN;
                    LayerConfig.SMOOTHING_PRIORITY = LayerConfig.SmoothingPriority.DOWN;
                }
                default -> {
                    source.sendError(Text.literal("§cUnknown preset. Use: basic, extended, or extreme"));
                    return 0;
                }
            }

            LayerConfig.save();

            source.sendFeedback(() -> Text.literal(
                String.format("§aApplied preset: §f%s", presetName)), true);
            source.sendFeedback(() -> Text.literal(String.format(
                "§eMode: §f%s §7| §eDistance: §f%d §7| §eEdge Threshold: §f%d §7| §eSmoothing: §f%d cycles §7| §eRounding: §f%s",
                LayerConfig.MODE.name(), LayerConfig.MAX_LAYER_DISTANCE, LayerConfig.EDGE_HEIGHT_THRESHOLD,
                LayerConfig.SMOOTHING_CYCLES, LayerConfig.SMOOTHING_ROUNDING_MODE.name())), false);

            return 1;
        } catch (Exception e) {
            CRLayers.LOGGER.error("Error applying preset", e);
            source.sendError(Text.literal("§cError applying preset: " + e.getMessage()));
            return 0;
        }
    }

    // ==================== Debug Command ====================

    private static int executeDebug(CommandContext<ServerCommandSource> context) {
        int radius = IntegerArgumentType.getInteger(context, "radius");
        return executeDebug(context, radius);
    }

    private static int executeDebug(CommandContext<ServerCommandSource> context, int radius) {
        ServerCommandSource source = context.getSource();
        
        if (!(source.getEntity() instanceof ServerPlayerEntity player)) {
            source.sendError(Text.literal("This command can only be used by players"));
            return 0;
        }
        
        BlockPos center = player.getBlockPos();
        source.sendFeedback(() -> Text.literal(
            String.format("§eGenerating debug data for %dx%d area around %s...", 
                radius*2+1, radius*2+1, center)), false);
        
        LayerGenerator generator = new LayerGenerator(player.getServerWorld());
        String result = generator.debugExport(center, radius);
        
        source.sendFeedback(() -> Text.literal("§aDebug data exported to: §f" + result), true);
        
        return 1;
    }
}

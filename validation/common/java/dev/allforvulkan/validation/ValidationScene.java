package dev.allforvulkan.validation;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import java.util.concurrent.CompletableFuture;

/** Only called in an explicitly requested, freshly created AFV smoke world. */
public final class ValidationScene {
    private ValidationScene() {}
    public static CompletableFuture<Void> create(MinecraftServer server) {
        return CompletableFuture.runAsync(() -> {
            var level = server.overworld();
            level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, server);
            level.getGameRules().getRule(GameRules.RULE_RANDOMTICKING).set(0, server);
            level.setDayTime(1000);
            level.setWeatherParameters(120000, 0, false, false);
            for (int x = -7; x <= 7; x++) for (int z = 4; z <= 14; z++)
                level.setBlockAndUpdate(new BlockPos(x, -61, z), Blocks.SMOOTH_STONE.defaultBlockState());
            for (int x = -5; x <= -2; x++) for (int z = 7; z <= 10; z++) {
                level.setBlockAndUpdate(new BlockPos(x, -62, z), Blocks.STONE.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(x, -61, z), Blocks.WATER.defaultBlockState());
            }
            for (int y = -60; y <= -57; y++) {
                level.setBlockAndUpdate(new BlockPos(1, y, 8), Blocks.STONE_BRICKS.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(2, y, 8), Blocks.STONE_BRICKS.defaultBlockState());
            }
            for (int y = -60; y <= -58; y++) {
                level.setBlockAndUpdate(new BlockPos(-2, y, 5), Blocks.GLASS.defaultBlockState());
                level.setBlockAndUpdate(new BlockPos(4, y, 9), Blocks.OAK_LEAVES.defaultBlockState());
            }
            level.setBlockAndUpdate(new BlockPos(-1, -60, 6), Blocks.RED_STAINED_GLASS.defaultBlockState());
            level.setBlockAndUpdate(new BlockPos(4, -60, 6), Blocks.SEA_LANTERN.defaultBlockState());
            var cow = EntityType.COW.create(level);
            if (cow == null) throw new IllegalStateException("Validation cow could not be created");
            cow.moveTo(-4, -60, 12, 180, 0);
            cow.setNoAi(true);
            level.addFreshEntity(cow);
            level.addFreshEntity(new ItemEntity(level, -3, -59.5, 5, new ItemStack(Items.DIAMOND)));
            for (var player : server.getPlayerList().getPlayers())
                player.connection.teleport(.5, -60, .5, 0, 8);
            System.out.println("AFV_VALIDATION_SCENE_READY: stone shadows, glass, cutout leaves, water, emissive block, cow, item; time=1000");
        }, server);
    }
}

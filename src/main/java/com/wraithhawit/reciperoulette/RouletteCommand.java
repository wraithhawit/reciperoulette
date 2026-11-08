package com.wraithhawit.reciperoulette;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.server.commands.ReloadCommand;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.Random;

/**
 * {@code /reciperoulette} (op level 2):
 * <ul>
 *   <li>{@code seed}: show the current seed</li>
 *   <li>{@code seed <value>}: set the seed and reload</li>
 *   <li>{@code reroll}: pick a new random seed and reload</li>
 *   <li>{@code enable} / {@code disable}: turn the scramble on or off and reload</li>
 * </ul>
 * Every change is saved to the config first, so it survives restarts, then applied with the
 * same data pack reload {@code /reload} does.
 */
public final class RouletteCommand {
    private RouletteCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> d = event.getDispatcher();
        d.register(Commands.literal(RecipeRoulette.MOD_ID)
                .requires(s -> s.hasPermission(2))
                .then(Commands.literal("seed")
                        .executes(RouletteCommand::showSeed)
                        .then(Commands.argument("value", LongArgumentType.longArg())
                                .executes(c -> setSeed(c, LongArgumentType.getLong(c, "value")))))
                .then(Commands.literal("reroll").executes(c -> setSeed(c, randomSeed())))
                .then(Commands.literal("enable").executes(c -> setEnabled(c, true)))
                .then(Commands.literal("disable").executes(c -> setEnabled(c, false))));
    }

    private static long randomSeed() {
        long seed = 0L;
        Random random = new Random();
        while (seed == 0L) {
            seed = random.nextLong();
        }
        return seed;
    }

    private static Component seedText(long seed) {
        return Component.literal(Long.toString(seed)).withStyle(Style.EMPTY
                .withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, Long.toString(seed)))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to copy"))));
    }

    private static int showSeed(CommandContext<CommandSourceStack> c) {
        long seed = RouletteConfig.SEED.get();
        boolean on = RouletteConfig.ENABLED.get();
        c.getSource().sendSuccess(() -> Component.literal("Recipe Roulette seed: ").append(seedText(seed))
                .append(on ? "" : " (scramble is disabled)"), false);
        return 1;
    }

    private static int setSeed(CommandContext<CommandSourceStack> c, long seed) {
        if (seed == 0L) {
            c.getSource().sendFailure(Component.literal("Seed 0 means \"pick one\"; use /reciperoulette reroll instead."));
            return 0;
        }
        RouletteConfig.SEED.set(seed);
        RouletteConfig.SPEC.save();
        RecipeRoulette.LOGGER.info("[reciperoulette] seed set to {} by {}", seed, c.getSource().getTextName());
        c.getSource().sendSuccess(() -> Component.literal("Recipe Roulette seed is now ").append(seedText(seed))
                .append(". Reloading data packs to re-scramble; on a big pack this takes a while."), true);
        reload(c.getSource());
        return 1;
    }

    private static int setEnabled(CommandContext<CommandSourceStack> c, boolean enabled) {
        RouletteConfig.ENABLED.set(enabled);
        RouletteConfig.SPEC.save();
        RecipeRoulette.LOGGER.info("[reciperoulette] {} by {}", enabled ? "enabled" : "disabled",
                c.getSource().getTextName());
        c.getSource().sendSuccess(() -> Component.literal("Recipe Roulette " + (enabled ? "enabled" : "disabled")
                + ". Reloading data packs; on a big pack this takes a while."), true);
        reload(c.getSource());
        return 1;
    }

    private static void reload(CommandSourceStack source) {
        ReloadCommand.reloadPacks(source.getServer().getPackRepository().getSelectedIds(), source);
    }
}

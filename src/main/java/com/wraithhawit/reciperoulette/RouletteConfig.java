package com.wraithhawit.reciperoulette;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.List;

/**
 * Common config (config/reciperoulette-common.toml). Common rather than server config because
 * recipes load before a world's serverconfig folder is read. Changes apply on /reload.
 */
public final class RouletteConfig {
    public enum Pool {
        /** Shuffle among items some recipe already outputs: everything craftable stays craftable. */
        OUTPUTS,
        /** Shuffle among every registered item: recipes can make creative and uncraftable items. */
        ALL_ITEMS
    }

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.BooleanValue ENABLED;
    public static final ModConfigSpec.LongValue SEED;
    public static final ModConfigSpec.EnumValue<Pool> POOL;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_ITEMS;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> EXCLUDED_RECIPE_TYPES;
    public static final ModConfigSpec.IntValue SAMPLES_PER_TYPE;
    public static final ModConfigSpec.BooleanValue SCRAMBLE_TRADES;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();
        ENABLED = b.comment("Scramble recipe outputs. false leaves every recipe as the pack made it.")
                .define("enabled", true);
        SEED = b.comment("Seed for the scramble. Same pack + same seed = same scramble.",
                        "0 picks a random seed on the next load and writes it here.")
                .defineInRange("seed", 0L, Long.MIN_VALUE, Long.MAX_VALUE);
        POOL = b.comment("OUTPUTS: items only swap with other craftable items, so everything craftable stays craftable.",
                        "ALL_ITEMS: any registered item can come out of a recipe, creative items included.")
                .defineEnum("pool", Pool.OUTPUTS);
        EXCLUDED_ITEMS = b.comment("Item ids that are never moved and never become a result, e.g. \"minecraft:crafting_table\".")
                .defineListAllowEmpty("excludedItems", List.of(), () -> "", o -> o instanceof String);
        EXCLUDED_RECIPE_TYPES = b.comment("Recipe types left untouched, e.g. \"minecraft:stonecutting\".")
                .defineListAllowEmpty("excludedRecipeTypes", List.of(), () -> "", o -> o instanceof String);
        SAMPLES_PER_TYPE = b.comment("Write this many before/after JSON samples per recipe type to",
                        "logs/reciperoulette/samples.txt, to check what got scrambled. 0 turns it off.")
                .defineInRange("samplesPerType", 1, 0, 100);
        SCRAMBLE_TRADES = b.comment("Also swap what villagers and wandering traders sell, with the same swap as recipes",
                        "(an emerald trade that sold bread sells whatever bread recipes now make).",
                        "Only trades made after this is on change: a villager keeps the trades it already has.",
                        "Takes effect right away, no reload needed.")
                .define("scrambleTrades", false);
        SPEC = b.build();
    }

    private RouletteConfig() {
    }
}

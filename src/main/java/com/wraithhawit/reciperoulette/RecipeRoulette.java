package com.wraithhawit.reciperoulette;

import com.mojang.logging.LogUtils;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;

import org.slf4j.Logger;

/**
 * Scrambles every recipe's output. The work happens in {@code RecipeManagerMixin}, which hands
 * the freshly parsed recipes to {@link RouletteEngine} on every datapack load and /reload.
 */
@Mod(RecipeRoulette.MOD_ID)
public final class RecipeRoulette {
    public static final String MOD_ID = "reciperoulette";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RecipeRoulette(ModContainer container) {
        container.registerConfig(ModConfig.Type.COMMON, RouletteConfig.SPEC);
        NeoForge.EVENT_BUS.addListener(RouletteCommand::register);
        LOGGER.info("[reciperoulette] loaded {}", container.getModInfo().getVersion());
    }
}

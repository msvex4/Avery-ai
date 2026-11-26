package com.avery.ai.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

/** Кнопка «Настройки» у мода в списке модов (Mod Menu). */
public class AveryModMenu implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
			return parent -> null; // без Cloth Config окна настроек нет, остаётся файл avery.json
		}
		return AveryConfigScreen::create;
	}
}

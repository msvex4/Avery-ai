package com.avery.ai.mixin;

import com.avery.ai.AveryMod;
import com.mojang.authlib.GameProfile;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Подменяет скин Эвери на тот, что лежит в моде:
 * assets/avery/textures/skin/avery.png (64x64).
 * Чтобы сменить скин, достаточно заменить этот PNG (или подложить его ресурспаком).
 */
@Mixin(PlayerListEntry.class)
public abstract class PlayerListEntryMixin {
	@Shadow
	public abstract GameProfile getProfile();

	@Inject(method = "getSkinTexture", at = @At("HEAD"), cancellable = true)
	private void avery$skin(CallbackInfoReturnable<Identifier> cir) {
		if (AveryMod.BOT_UUID.equals(getProfile().getId())) {
			cir.setReturnValue(AveryMod.SKIN);
		}
	}

	/** Обычная («широкая», Steve) модель рук. Если у скина тонкие руки, поменяй на "slim". */
	@Inject(method = "getModel", at = @At("HEAD"), cancellable = true)
	private void avery$model(CallbackInfoReturnable<String> cir) {
		if (AveryMod.BOT_UUID.equals(getProfile().getId())) {
			cir.setReturnValue("default");
		}
	}
}

package net.nuggetmc.tplus.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.nuggetmc.tplus.bot.Bot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bots that were added to the player list ({@code /bot settings addplayerlist true}) would otherwise be written to
 * {@code playerdata/} on every autosave, leaving a file per random bot UUID behind.
 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void terminatorplus$skipBots(ServerPlayer player, CallbackInfo ci) {
        if (player instanceof Bot) {
            ci.cancel();
        }
    }
}

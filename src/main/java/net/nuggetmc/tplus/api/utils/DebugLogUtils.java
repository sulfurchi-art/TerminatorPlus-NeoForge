package net.nuggetmc.tplus.api.utils;

import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import net.nuggetmc.tplus.api.Terminator;
import org.slf4j.Logger;

import java.util.Arrays;

public class DebugLogUtils {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String PREFIX = ChatFormatting.YELLOW + "[DEBUG] " + ChatFormatting.RESET;

    /**
     * Logs to the console and every online operator.
     */
    public static void log(Object... objects) {
        String[] values = fromStringArray(objects);
        String message = PREFIX + String.join(" ", values);

        MinecraftServer server = ServerLifecycleHooks.getCurrentServer();

        if (server == null) {
            LOGGER.info(ChatUtils.stripColor(message));
            return;
        }

        Component component = ChatUtils.legacy(message);
        server.sendSystemMessage(component);

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!(player instanceof Terminator) && server.getPlayerList().isOp(player.getGameProfile())) {
                player.sendSystemMessage(component);
            }
        }
    }

    public static String[] fromStringArray(Object[] objects) {
        return Arrays.stream(objects).map(String::valueOf).toArray(String[]::new);
    }
}

package net.nuggetmc.tplus.api;

import javax.annotation.Nullable;

/**
 * Entry point for other mods. The bot manager only exists while a server is running.
 */
public class TerminatorPlusAPI {
    @Nullable
    private static BotManager botManager;

    @Nullable
    public static BotManager getBotManager() {
        return botManager;
    }

    public static void setBotManager(@Nullable BotManager botManager) {
        TerminatorPlusAPI.botManager = botManager;
    }
}

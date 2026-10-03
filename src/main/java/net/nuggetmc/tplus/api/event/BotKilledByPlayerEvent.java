package net.nuggetmc.tplus.api.event;

import net.minecraft.world.entity.player.Player;
import net.nuggetmc.tplus.api.Terminator;

public class BotKilledByPlayerEvent {

    // eventually also call this event for deaths from other damage causes within combat time
    // (like hitting the ground too hard)

    private final Terminator bot;
    private final Player player;

    public BotKilledByPlayerEvent(Terminator bot, Player player) {
        this.bot = bot;
        this.player = player;
    }

    public Terminator getBot() {
        return bot;
    }

    public Player getPlayer() {
        return player;
    }
}

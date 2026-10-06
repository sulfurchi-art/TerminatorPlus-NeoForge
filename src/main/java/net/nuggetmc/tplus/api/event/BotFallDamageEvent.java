package net.nuggetmc.tplus.api.event;

import net.minecraft.core.BlockPos;
import net.nuggetmc.tplus.api.Terminator;

import java.util.List;

public class BotFallDamageEvent {

    private final Terminator bot;
    private final List<BlockPos> standingOn;

    private boolean cancelled;

    public BotFallDamageEvent(Terminator bot, List<BlockPos> standingOn) {
        this.bot = bot;
        this.standingOn = standingOn;
    }

    public Terminator getBot() {
        return bot;
    }

    public List<BlockPos> getStandingOn() {
        return standingOn;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void setCancelled(boolean cancelled) {
        this.cancelled = cancelled;
    }
}

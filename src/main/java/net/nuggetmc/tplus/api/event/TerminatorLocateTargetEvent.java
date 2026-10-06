package net.nuggetmc.tplus.api.event;

import net.minecraft.world.entity.LivingEntity;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;
import net.nuggetmc.tplus.api.Terminator;

import javax.annotation.Nullable;

/**
 * Posted on {@code NeoForge.EVENT_BUS} every time a bot looks for a target.
 * Listeners can swap the target, or cancel the event to leave the bot without one.
 */
public class TerminatorLocateTargetEvent extends Event implements ICancellableEvent {

    private final Terminator terminator;
    @Nullable
    private LivingEntity target;

    public TerminatorLocateTargetEvent(Terminator terminator, @Nullable LivingEntity target) {
        this.terminator = terminator;
        this.target = target;
    }

    public Terminator getTerminator() {
        return terminator;
    }

    @Nullable
    public LivingEntity getTarget() {
        return target;
    }

    public void setTarget(@Nullable LivingEntity target) {
        this.target = target;
    }
}

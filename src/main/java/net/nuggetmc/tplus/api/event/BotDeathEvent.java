package net.nuggetmc.tplus.api.event;

import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.nuggetmc.tplus.api.Terminator;

import java.util.Collection;

/**
 * Fired (to the bot agent) when a bot dies and its drops are about to be spawned.
 * The drops collection is live: clearing it means nothing is dropped.
 */
public class BotDeathEvent {

    private final Terminator bot;
    private final DamageSource source;
    private final Collection<ItemEntity> drops;

    public BotDeathEvent(Terminator bot, DamageSource source, Collection<ItemEntity> drops) {
        this.bot = bot;
        this.source = source;
        this.drops = drops;
    }

    public Terminator getBot() {
        return bot;
    }

    public DamageSource getDamageSource() {
        return source;
    }

    public Collection<ItemEntity> getDrops() {
        return drops;
    }
}

package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Toggles for the abilities added on top of the legacy agent ({@code /bot settings ability}).
 * Item based abilities only ever kick in when a bot actually carries the items.
 */
public class SkillSettings {

    /**
     * Max distance (in blocks) at which targets are picked up. 0 = no limit (anything loaded in the same dimension).
     */
    public double targetRange = 0;

    /**
     * What bots build pillars, bridges and clutches with ({@code /bot settings buildblock}). Bots never run out of it.
     */
    public Block buildBlock = Blocks.COBBLESTONE;
    public int hardness = 7;

    private final Map<String, Boolean> abilities = new LinkedHashMap<>();

    public SkillSettings() {
        abilities.put("pathfinding", true);  // detours when stuck
        abilities.put("climbing", true);     // ladders/vines, pillaring over walls, climbing out of water
        abilities.put("elytra", true);       // elytra + firework rockets
        abilities.put("mace", true);         // mace smash dives
        abilities.put("pearls", true);       // ender pearls
        abilities.put("windcharges", true);  // wind charge launches for mace smashes
        abilities.put("bow", true);          // bow and arrows at range
        abilities.put("totems", true);       // a new totem in the off hand as soon as one pops
        abilities.put("retaliate", true);    // fight back against whoever hit the bot
        abilities.put("recovery", true);
        abilities.put("teamwork", true);
        abilities.put("criticals", true);
    }

    public Map<String, Boolean> all() {
        return abilities;
    }

    public boolean has(String name) {
        return abilities.containsKey(name.toLowerCase(Locale.ROOT));
    }

    public boolean set(String name, boolean value) {
        String key = name.toLowerCase(Locale.ROOT);
        if (!abilities.containsKey(key)) return false;
        abilities.put(key, value);
        return true;
    }

    @Nullable
    public Boolean get(String name) {
        return abilities.get(name.toLowerCase(Locale.ROOT));
    }

    public boolean pathfinding() {
        return abilities.get("pathfinding");
    }

    public boolean climbing() {
        return abilities.get("climbing");
    }

    public boolean elytra() {
        return abilities.get("elytra");
    }

    public boolean mace() {
        return abilities.get("mace");
    }

    public boolean pearls() {
        return abilities.get("pearls");
    }

    public boolean windCharges() {
        return abilities.get("windcharges");
    }

    public boolean bow() {
        return abilities.get("bow");
    }

    public boolean totems() {
        return abilities.get("totems");
    }

    public boolean retaliate() {
        return abilities.get("retaliate");
    }
}

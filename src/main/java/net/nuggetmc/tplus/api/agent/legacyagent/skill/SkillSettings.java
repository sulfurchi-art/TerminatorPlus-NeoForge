package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;

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
    public boolean defaultEquipment = true;
    public String defaultGear = "vanilla";
    public String chatter = "mild";
    private final Map<Integer, Hardness.Tuning> profiles = new LinkedHashMap<>();
    public Hardness profile(int level) { return new Hardness(level, profiles.getOrDefault(level, Hardness.defaults(level))); }

    private final Map<String, Boolean> abilities = new LinkedHashMap<>();

    public SkillSettings() {
        abilities.put("pathfinding", true);  // detours when stuck
        abilities.put("climbing", true);     // ladders/vines, pillaring over walls, climbing out of water
        abilities.put("elytra", true);       // elytra + firework rockets
        abilities.put("mace", true);         // mace smash dives
        abilities.put("pearls", true);       // ender pearls
        abilities.put("windcharges", true);  // wind charge launches for mace smashes
        abilities.put("bow", true);          // bow and arrows at range
        abilities.put("guns", true);         // optional SBW firearms, native ammo/reload and difficulty-based controls
        abilities.put("vehicles", true);     // boarding, seat assignments and native ground controls
        abilities.put("vehicleweapons", true);
        abilities.put("drones", true);
        abilities.put("c4", true);
        abilities.put("missiledefense", true); // native missile alerts, emergency flight and enclosed shelter
        abilities.put("helicopters", true);  // native helicopter pilot inputs
        abilities.put("totems", true);       // a new totem in the off hand as soon as one pops
        abilities.put("retaliate", true);    // fight back against whoever hit the bot
        abilities.put("recovery", true);
        abilities.put("teamwork", true);
        abilities.put("criticals", true);
        abilities.put("deception", true);    // level ten feints
        abilities.put("interception", true); // level ten enemy pearl forecasts
        abilities.put("shield", true);       // native defensive shield timing at levels eight and above
    }

    public Map<String, Boolean> all() {
        return abilities;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Hardness", hardness);
        tag.putDouble("TargetRange", targetRange);
        tag.putString("BuildBlock", BuiltInRegistries.BLOCK.getKey(buildBlock).toString());
        tag.putBoolean("DefaultEquipment", defaultEquipment);
        tag.putString("DefaultGear", defaultGear);
        tag.putString("Chatter", chatter);
        CompoundTag toggles = new CompoundTag();
        abilities.forEach(toggles::putBoolean);
        tag.put("Abilities", toggles);
        CompoundTag tuning = new CompoundTag();
        for (int i = 1; i <= 10; i++) tuning.put(Integer.toString(i), profile(i).tuning().save());
        tag.put("Profiles", tuning);
        return tag;
    }

    public void load(CompoundTag tag) {
        Map<Integer, Hardness.Tuning> checkedProfiles = new LinkedHashMap<>();
        CompoundTag tuning = tag.getCompound("Profiles");
        for (int i = 1; i <= 10; i++) checkedProfiles.put(i, Hardness.Tuning.load(tuning.getCompound(Integer.toString(i)), Hardness.defaults(i)));
        int difficulty = tag.contains("Hardness") ? tag.getInt("Hardness") : 7;
        new Hardness(difficulty);
        double range = tag.getDouble("TargetRange");
        if (!Double.isFinite(range) || range < 0) throw new IllegalArgumentException("TargetRange must be finite and >= 0");
        String mode = tag.contains("Chatter") ? tag.getString("Chatter") : "mild";
        if (!java.util.Set.of("off", "mild", "spicy").contains(mode)) throw new IllegalArgumentException("Chatter must be off/mild/spicy");
        Block material = Blocks.COBBLESTONE;
        if (tag.contains("BuildBlock")) {
            ResourceLocation id = ResourceLocation.tryParse(tag.getString("BuildBlock"));
            if (id == null || !BuiltInRegistries.BLOCK.containsKey(id) || BuiltInRegistries.BLOCK.get(id) == Blocks.AIR)
                throw new IllegalArgumentException("Unknown BuildBlock");
            material = BuiltInRegistries.BLOCK.get(id);
            if (!material.defaultBlockState().isCollisionShapeFullBlock(net.minecraft.world.level.EmptyBlockGetter.INSTANCE, net.minecraft.core.BlockPos.ZERO))
                throw new IllegalArgumentException("BuildBlock must be a full block");
        }
        String gear = tag.contains("DefaultGear") ? tag.getString("DefaultGear") : "vanilla";
        if (!java.util.Set.of("vanilla", "warfare").contains(gear)) throw new IllegalArgumentException("DefaultGear must be vanilla/warfare");
        defaultGear = gear;
        hardness = difficulty;
        targetRange = range;
        buildBlock = material;
        defaultEquipment = !tag.contains("DefaultEquipment") || tag.getBoolean("DefaultEquipment");
        chatter = mode;
        profiles.clear(); profiles.putAll(checkedProfiles);
        CompoundTag toggles = tag.getCompound("Abilities");
        abilities.replaceAll((key, value) -> toggles.contains(key) ? toggles.getBoolean(key) : value);
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

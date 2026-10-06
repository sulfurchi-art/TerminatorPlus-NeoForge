package net.nuggetmc.tplus.bot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.phys.AABB;
import net.nuggetmc.tplus.api.agent.legacyagent.EnumTargetGoal;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.SkillSettings;
import net.nuggetmc.tplus.utils.SnbtConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** Global defaults, never player privileges or bot entity saves. Reload validates before committing. */
public final class BotSettingsStore {
    private final Path file = SnbtConfig.path("settings.snbt");
    private final BotManagerImpl manager;
    private final LegacyAgent agent;

    public BotSettingsStore(BotManagerImpl manager) {
        this.manager = manager;
        this.agent = (LegacyAgent) manager.getAgent();
    }

    public void load() throws IOException {
        if (!Files.isRegularFile(file)) { save(); return; }
        CompoundTag root = SnbtConfig.read(file);
        SkillSettings checked = new SkillSettings();
        checked.load(root.getCompound("Skills"));
        EnumTargetGoal goal = root.contains("Goal") ? EnumTargetGoal.valueOf(root.getString("Goal")) : agent.getTargetType();
        AABB bounds = null;
        double wx = 0, wy = 0, wz = 0;
        if (root.contains("Region")) {
            CompoundTag region = root.getCompound("Region");
            double[] values = new double[9];
            String[] keys = {"MinX", "MinY", "MinZ", "MaxX", "MaxY", "MaxZ", "WeightX", "WeightY", "WeightZ"};
            for (int i = 0; i < keys.length; i++) {
                values[i] = region.getDouble(keys[i]);
                if (!Double.isFinite(values[i])) throw new IllegalArgumentException("Region values must be finite");
            }
            wx = values[6]; wy = values[7]; wz = values[8];
            if (wx < 0 || wy < 0 || wz < 0) throw new IllegalArgumentException("Region weights must be >= 0");
            bounds = new AABB(values[0], values[1], values[2], values[3], values[4], values[5]);
        }
        agent.getSkills().clear();
        agent.getSkillSettings().load(checked.save());
        agent.setTargetType(goal);
        agent.setRegion(bounds, wx, wy, wz);
        manager.setMobTarget(root.getBoolean("MobTarget"));
        manager.setAddToPlayerList(root.getBoolean("AddPlayerList"));
        manager.joinMessages = root.getBoolean("JoinMessages");
        agent.offsets = !root.contains("Offsets") || root.getBoolean("Offsets");
        for (var bot : manager.fetch()) {
            Bot b = (Bot) bot;
            b.cancelRecoveryItem();
            b.setHardnessOverride(b.getHardnessOverride());
        }
    }

    public void save() throws IOException {
        CompoundTag root = new CompoundTag();
        root.putInt("Version", 1);
        root.put("Skills", agent.getSkillSettings().save());
        root.putString("Goal", agent.getTargetType().name());
        root.putBoolean("MobTarget", manager.isMobTarget());
        root.putBoolean("AddPlayerList", manager.addToPlayerList());
        root.putBoolean("JoinMessages", manager.joinMessages);
        root.putBoolean("Offsets", agent.offsets);
        AABB bounds = agent.getRegion();
        if (bounds != null) {
            CompoundTag region = new CompoundTag();
            region.putDouble("MinX", bounds.minX); region.putDouble("MinY", bounds.minY); region.putDouble("MinZ", bounds.minZ);
            region.putDouble("MaxX", bounds.maxX); region.putDouble("MaxY", bounds.maxY); region.putDouble("MaxZ", bounds.maxZ);
            region.putDouble("WeightX", agent.getRegionWeightX()); region.putDouble("WeightY", agent.getRegionWeightY()); region.putDouble("WeightZ", agent.getRegionWeightZ());
            root.put("Region", region);
        }
        SnbtConfig.write(file, root);
    }
}

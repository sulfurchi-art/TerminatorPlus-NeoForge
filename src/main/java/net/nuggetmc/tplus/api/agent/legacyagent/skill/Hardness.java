package net.nuggetmc.tplus.api.agent.legacyagent.skill;

/** Decision quality, never an extra movement or damage multiplier. Seven is the legacy baseline. */
public record Hardness(int level, Tuning tuning) {
    public record Tuning(int reactionTicks, int attackInterval, int skillInterval, double retreatHealth, double resumeHealth, int pressureHits) {
        public Tuning {
            if (reactionTicks < 0 || reactionTicks > 200 || attackInterval < 1 || attackInterval > 200 || skillInterval < 1 || skillInterval > 200
                    || !Double.isFinite(retreatHealth) || !Double.isFinite(resumeHealth) || retreatHealth < 0 || retreatHealth > 1
                    || resumeHealth < retreatHealth || resumeHealth > 1 || pressureHits < 1 || pressureHits > 20)
                throw new IllegalArgumentException("Invalid difficulty tuning");
        }
        public net.minecraft.nbt.CompoundTag save() {
            var tag = new net.minecraft.nbt.CompoundTag();
            tag.putInt("ReactionTicks", reactionTicks); tag.putInt("AttackInterval", attackInterval); tag.putInt("SkillInterval", skillInterval);
            tag.putDouble("RetreatHealth", retreatHealth); tag.putDouble("ResumeHealth", resumeHealth); tag.putInt("PressureHits", pressureHits);
            return tag;
        }
        public static Tuning load(net.minecraft.nbt.CompoundTag tag, Tuning fallback) {
            return new Tuning(tag.contains("ReactionTicks") ? tag.getInt("ReactionTicks") : fallback.reactionTicks(),
                    tag.contains("AttackInterval") ? tag.getInt("AttackInterval") : fallback.attackInterval(),
                    tag.contains("SkillInterval") ? tag.getInt("SkillInterval") : fallback.skillInterval(),
                    tag.contains("RetreatHealth") ? tag.getDouble("RetreatHealth") : fallback.retreatHealth(),
                    tag.contains("ResumeHealth") ? tag.getDouble("ResumeHealth") : fallback.resumeHealth(),
                    tag.contains("PressureHits") ? tag.getInt("PressureHits") : fallback.pressureHits());
        }
    }
    public Hardness(int level) { this(level, defaults(level)); }
    public static Tuning defaults(int level) {
        return new Tuning(level >= 7 ? 0 : (7 - level) * 5, level >= 7 ? 3 : 8 + (7 - level) * 5,
                level >= 7 ? 5 : 5 + (7 - level) * 10, level >= 10 ? 0.6 : level == 9 ? 0.5 : 0.4,
                level >= 10 ? 0.9 : level == 9 ? 0.8 : 0.7, level >= 10 ? 2 : 3);
    }
    public Hardness {
        if (level < 1 || level > 10) throw new IllegalArgumentException("AI hardness must be 1..10");
        java.util.Objects.requireNonNull(tuning);
    }

    public boolean tactical() { return level > 7; }
    public boolean infiniteSupplies() { return level == 10; }
    public boolean passiveRegeneration() { return level <= 7 || level == 10; }
    public boolean limitedPerception() { return level == 8 || level == 9; }
    public int reactionTicks() { return tuning.reactionTicks(); }
    public int attackInterval() { return tuning.attackInterval(); }
    public int skillInterval() { return tuning.skillInterval(); }
    public int buffCount() { return Math.max(0, level - 7); }
    public double retreatHealth() { return tuning.retreatHealth(); }
    public double resumeHealth() { return tuning.resumeHealth(); }
    public int pressureHits() { return tuning.pressureHits(); }
    public boolean allows(String skill) {
        return level >= switch (skill) {
            case "totems", "retaliate" -> 3;
            case "pathfinding", "climbing", "bow" -> 4;
            case "pearls", "elytra" -> 5;
            case "mace", "windcharges" -> 6;
            case "vehicles" -> 7;
            case "vehicleweapons" -> 4;
            case "helicopters" -> 9;
            case "deception", "interception" -> 10;
            case "shield" -> 8;
            default -> 1;
        };
    }
}

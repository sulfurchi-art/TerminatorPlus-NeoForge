package net.nuggetmc.tplus.api.agent.legacyagent.skill;

/** Decision quality, never an extra movement or damage multiplier. Seven is the legacy baseline. */
public record Hardness(int level) {
    public Hardness {
        if (level < 1 || level > 10) throw new IllegalArgumentException("AI hardness must be 1..10");
    }

    public boolean tactical() { return level > 7; }
    public int reactionTicks() { return level >= 7 ? 0 : (7 - level) * 5; }
    public int attackInterval() { return level >= 7 ? 3 : 8 + (7 - level) * 5; }
    public int skillInterval() { return level >= 7 ? 5 : 5 + (7 - level) * 10; }
    public int buffCount() { return Math.max(0, level - 7); }
    public double retreatHealth() { return level >= 10 ? 0.6 : level == 9 ? 0.5 : 0.4; }
    public double resumeHealth() { return level >= 10 ? 0.9 : level == 9 ? 0.8 : 0.7; }
    public int pressureHits() { return level >= 10 ? 2 : 3; }
    public boolean allows(String skill) {
        return level >= switch (skill) {
            case "totems", "retaliate" -> 3;
            case "pathfinding", "climbing", "bow" -> 4;
            case "pearls", "elytra" -> 5;
            case "mace", "windcharges" -> 6;
            default -> 1;
        };
    }
}

package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import net.nuggetmc.tplus.utils.SnbtConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Observed attempts and confirmed outcomes only, bounded and persisted per world. */
public final class OpponentLearning {
    public enum Move { MELEE, SHIELD_BREAK, BOW, MACE, PEARL }
    public record Result(int attempts, int hits) {}
    private final Path file;
    private final Map<UUID, Map<Move, Result>> players = new LinkedHashMap<>();

    public OpponentLearning(MinecraftServer server) {
        file = server.getWorldPath(LevelResource.ROOT).resolve("data/terminatorplus-opponents.snbt");
        try { load(); } catch (IOException | IllegalArgumentException e) { com.mojang.logging.LogUtils.getLogger().error("Cannot load opponent learning", e); }
    }

    public Result result(UUID player, Move move) { return players.getOrDefault(player, Map.of()).getOrDefault(move, new Result(0, 0)); }
    public double weight(UUID player, Move move) {
        Result r = result(player, move);
        return 0.6 + 1.4 * (r.hits() + 2.0) / (r.attempts() + 4.0);
    }
    public void observe(UUID player, Move move, boolean hit) {
        if (!players.containsKey(player) && players.size() >= 512) players.remove(players.keySet().iterator().next());
        Result previous = result(player, move);
        int attempts = previous.attempts(), hits = previous.hits();
        if (attempts >= 100) { attempts /= 2; hits /= 2; }
        players.computeIfAbsent(player, ignored -> new java.util.EnumMap<>(Move.class)).put(move, new Result(attempts + 1, hits + (hit ? 1 : 0)));
    }
    public void save() throws IOException {
        CompoundTag root = new CompoundTag();
        players.forEach((uuid, moves) -> {
            CompoundTag entry = new CompoundTag();
            moves.forEach((move, result) -> {
                CompoundTag value = new CompoundTag(); value.putInt("Attempts", result.attempts()); value.putInt("Hits", result.hits()); entry.put(move.name(), value);
            });
            root.put(uuid.toString(), entry);
        });
        SnbtConfig.write(file, root);
    }
    private void load() throws IOException {
        if (!Files.isRegularFile(file)) return;
        CompoundTag root = SnbtConfig.read(file);
        for (String key : root.getAllKeys()) {
            if (players.size() >= 512) break;
            UUID uuid = UUID.fromString(key);
            Map<Move, Result> moves = new java.util.EnumMap<>(Move.class);
            CompoundTag entry = root.getCompound(key);
            for (String name : entry.getAllKeys()) {
                CompoundTag value = entry.getCompound(name);
                int attempts = value.getInt("Attempts"), hits = value.getInt("Hits");
                if (attempts < 0 || attempts > 100 || hits < 0 || hits > attempts) throw new IllegalArgumentException("Invalid outcome counts");
                moves.put(Move.valueOf(name), new Result(attempts, hits));
            }
            players.put(uuid, moves);
        }
    }
}

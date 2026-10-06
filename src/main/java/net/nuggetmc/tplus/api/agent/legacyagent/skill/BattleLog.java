package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import com.google.gson.GsonBuilder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.agent.legacyagent.EnumTargetGoal;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.utils.BattleLogWriter;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** Main-thread observation and JSON serialization; only immutable lines enter the bounded I/O queue. */
public final class BattleLog implements AutoCloseable {
    private static final com.google.gson.Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
    private static final List<String> CATEGORIES = List.of("life", "damage", "tactics", "weapons", "vehicles", "drones", "c4", "missiles", "items", "snap", "summary");
    public record Config(boolean enabled, int snapshotTicks, Set<String> categories) {
        public static Config read(CompoundTag tag) {
            int interval = tag.contains("SnapshotTicks") ? tag.getInt("SnapshotTicks") : 40;
            if (interval < 1 || interval > 12000) throw new IllegalArgumentException("BattleLog.SnapshotTicks must be 1..12000");
            Set<String> enabled = new HashSet<>(); CompoundTag categories = tag.getCompound("Categories");
            for (String key : categories.getAllKeys()) if (!CATEGORIES.contains(key)) throw new IllegalArgumentException("Unknown BattleLog category: " + key);
            for (String key : CATEGORIES) if (!categories.contains(key) || categories.getBoolean(key)) enabled.add(key);
            return new Config(tag.getBoolean("Enabled"), interval, Set.copyOf(enabled));
        }
        public CompoundTag save() {
            CompoundTag tag = new CompoundTag(), cats = new CompoundTag();
            tag.putBoolean("Enabled", enabled); tag.putInt("SnapshotTicks", snapshotTicks);
            for (String key : CATEGORIES) cats.putBoolean(key, categories.contains(key));
            tag.put("Categories", cats); return tag;
        }
    }
    private static final class Watch {
        UUID target; String tactic, role, flight, crew;
        long historyDroppedAt = -1000;
        final Deque<Map<String, Object>> damage = new ArrayDeque<>();
    }
    private static final class Stats {
        String name; long kills, deaths, fire, hits; double dealt, taken;
        final Map<String, Long> counts = new TreeMap<>(), durations = new TreeMap<>();
        Map<String, Object> json() {
            Map<String, Object> average = new TreeMap<>();
            durations.forEach((k, v) -> average.put(k, (double) v / Math.max(1, counts.getOrDefault(k, 0L))));
            return data("bot", name, "kills", kills, "deaths", deaths, "damageDealt", dealt, "damageTaken", taken,
                    "fire", fire, "confirmedDamageEvents", hits, "counts", counts, "averageTicks", average);
        }
    }
    private final BotSkills skills;
    private final net.nuggetmc.tplus.api.BotManager manager;
    private Config config = Config.read(new CompoundTag());
    private BattleLogWriter writer;
    private final List<BattleLogWriter> retiring = new ArrayList<>();
    private final Map<UUID, Watch> watches = new HashMap<>();
    private final Map<UUID, Stats> stats = new LinkedHashMap<>();
    private final Map<UUID, Double> vehicleHealth = new HashMap<>();
    private final Map<UUID, Entity> trackedVehicles = new LinkedHashMap<>();
    private final Map<UUID, Bot> vehicleObservers = new HashMap<>();
    private final Map<UUID, Map<String, Float>> vehicleParts = new HashMap<>();
    private final Set<UUID> destroyed = new HashSet<>();
    private final Map<UUID, Integer> decoys = new HashMap<>();
    private EnumTargetGoal goal = EnumTargetGoal.NONE;
    private boolean match;
    private long started;
    BattleLog(BotSkills skills, net.nuggetmc.tplus.api.BotManager manager) { this.skills = skills; this.manager = manager; }
    public Config config() { return config; }
    public boolean enabled() { return config.enabled && writer != null && writer.error() == null; }
    public Path path() { return writer == null ? null : writer.path(); }
    public long dropped() { return writer == null ? 0 : writer.dropped(); }
    public String status() { return "BattleLog=" + enabled() + "; snapshotTicks=" + config.snapshotTicks + "; file=" + path()
            + (writer == null ? "" : "; queued=" + writer.queued() + "; written=" + writer.written() + "; dropped=" + writer.dropped() + "; error=" + writer.error()); }
    public void configure(Config next) {
        retiring.removeIf(BattleLogWriter::closed);
        if (config.enabled && !next.enabled) close();
        config = next;
        if (next.enabled && writer == null) {
            goal = ((net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent) manager.getAgent()).getTargetType();
            Path file = Path.of("logs", "terminatorplus", "battle-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss-SSS")) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".jsonl").toAbsolutePath();
            writer = new BattleLogWriter(file);
            emit("meta", "log_start", null, data("version", net.nuggetmc.tplus.TerminatorPlus.getVersion(), "config", next.save().toString(), "schema", 1));
            if (goal != EnumTargetGoal.NONE) begin();
        }
    }
    public void toggle(boolean enabled) { configure(new Config(enabled, config.snapshotTicks, config.categories)); }
    private long now() { return manager.getServer().getTickCount(); }
    public static Map<String, Object> data(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
    private static double tenth(double v) { return Math.round(v * 10) / 10.0; }
    public static List<Double> pos(Vec3 v) { return List.of(tenth(v.x), tenth(v.y), tenth(v.z)); }
    public Map<String, Object> identity(Entity entity) {
        if (entity == null) return null;
        Map<String, Object> out = data("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), "uuid", entity.getUUID().toString(), "name", entity.getName().getString(), "pos", pos(entity.position()));
        Entity vehicle = entity.getVehicle();
        if (vehicle != null) out.put("vehicle", vehicle(vehicle, entity));
        return out;
    }
    private Map<String, Object> vehicle(Entity entity, Entity occupant) {
        int seat = -1;
        if (skills.warfare().available() && skills.warfare().access().isVehicle(entity)) seat = skills.warfare().access().vessel(entity).seatIndex(occupant);
        return data("id", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString(), "uuid", entity.getUUID().toString().substring(0, 8), "fullUuid", entity.getUUID().toString(), "seat", seat);
    }
    public void event(String category, String type, Bot bot, Object... pairs) {
        if (!enabled()) return;
        Stats s = bot == null ? null : stat(bot);
        if (s != null) s.counts.merge(type, 1L, Long::sum);
        Map<String, Object> extra = data(pairs);
        if (s != null && extra.get("durationTicks") instanceof Number n) s.durations.merge(type, n.longValue(), Long::sum);
        emit(category, type, bot, extra);
    }
    private void emit(String category, String type, Bot bot, Map<String, Object> extra) {
        if (!enabled() || !category.equals("meta") && !config.categories.contains(category)) return;
        Map<String, Object> event = data("tick", now(), "time", LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS")), "type", type);
        if (bot != null) {
            event.putAll(data("bot", bot.getBotName(), "uuid", bot.getUUID().toString(), "team", bot.getTeam() == null ? null : bot.getTeam().getName(), "level", bot.hardness().level(), "pos", pos(bot.position()), "dimension", bot.level().dimension().location().toString()));
            if (bot.getVehicle() != null) event.put("vehicle", vehicle(bot.getVehicle(), bot));
        }
        event.putAll(extra); writer.offer(JSON.toJson(event));
    }
    private Stats stat(Bot bot) {
        if (stats.size() >= 4096 && !stats.containsKey(bot.getUUID())) stats.remove(stats.keySet().iterator().next());
        Stats s = stats.computeIfAbsent(bot.getUUID(), key -> new Stats()); s.name = bot.getBotName(); return s;
    }
    public void goal(EnumTargetGoal next) {
        EnumTargetGoal previous = goal; goal = next;
        if (previous != next && enabled()) {
            if (next == EnumTargetGoal.NONE) end("goal_none");
            else if (!match) begin();
        }
    }
    private void begin() {
        stats.clear(); match = true; started = now();
        event("meta", "match_start", null, "goal", goal.name());
    }
    private void end(String reason) {
        if (!match) return;
        event("meta", "match_end", null, "reason", reason, "durationTicks", now() - started);
        event("summary", "summary", null, "bots", stats.values().stream().map(Stats::json).toList(), "dropped", dropped()); match = false;
    }
    public void mark(String text) { event("meta", "mark", null, "text", text); }
    public void observe(Bot bot) {
        if (!enabled()) return;
        retiring.removeIf(BattleLogWriter::closed);
        stat(bot); Watch w = watches.get(bot.getUUID()); BotMemory mem = skills.memory(bot);
        if (w == null) {
            w = new Watch(); watches.put(bot.getUUID(), w);
            event("life", "spawn", bot, "preset", bot.equipmentLabel(), "equipment", bot.getInventory().items.stream().filter(i -> !i.isEmpty()).map(BattleLog::item).toList(),
                    "armor", java.util.stream.StreamSupport.stream(bot.getArmorSlots().spliterator(), false).map(BattleLog::item).toList());
        }
        LivingEntity target = skills.currentTarget(bot); UUID tid = target == null ? null : target.getUUID();
        if (!Objects.equals(tid, w.target)) { w.target = tid; event("tactics", "target", bot, "target", identity(target), "distance", target == null ? null : tenth(bot.distanceTo(target)), "reason", "agent_" + goal.name()); }
        if (!mem.tactic.name().equals(w.tactic)) { w.tactic = mem.tactic.name(); event("tactics", "tactic", bot, "state", w.tactic); }
        if (!mem.teamRole.name().equals(w.role)) { w.role = mem.teamRole.name(); event("tactics", "role", bot, "state", w.role); }
        String flight = mem.flight + "/" + mem.plan;
        if (!flight.equals(w.flight)) { w.flight = flight; event("items", "flight", bot, "state", flight); }
        String crew = skills.warfare().crew().telemetry(bot);
        if (!Objects.equals(crew, w.crew)) { w.crew = crew; event("vehicles", "nav", bot, "state", crew); }
        if (now() % config.snapshotTicks == 0) event("snap", "snap", bot, "health", bot.getHealth(), "target", identity(target), "tactic", w.tactic, "role", w.role);
    }
    public void tick() {
        if (!enabled()) return;
        for (var terminator : skills.bots()) if (terminator instanceof Bot bot && bot.getVehicle() != null && skills.warfare().isVehicle(bot.getVehicle())) {
            Entity e = bot.getVehicle();
            if (trackedVehicles.size() >= 1024 && !trackedVehicles.containsKey(e.getUUID())) removeVehicle(trackedVehicles.keySet().iterator().next());
            trackedVehicles.put(e.getUUID(), e); vehicleObservers.put(e.getUUID(), bot);
        }
        for (UUID id : List.copyOf(trackedVehicles.keySet())) {
            Entity e = trackedVehicles.get(id); Bot bot = vehicleObservers.get(id);
            if (!skills.warfare().isVehicle(e)) { removeVehicle(id); continue; }
            var v = skills.warfare().access().vessel(e);
            Double previous = vehicleHealth.put(id, (double) v.health());
            Map<String, Float> parts = v.partHealth(), oldParts = vehicleParts.put(id, parts);
            if (previous != null && (previous > v.health() || oldParts != null && parts.entrySet().stream().anyMatch(p -> p.getValue() < oldParts.getOrDefault(p.getKey(), p.getValue()))))
                event("vehicles", "vehicle_damage", bot, "vehicle", vehicle(e, bot), "health", v.health(), "maxHealth", v.maxHealth(), "damage", previous - v.health(), "parts", parts);
            if (v.wreck() && destroyed.add(id)) event("vehicles", "vehicle_destroyed", bot, "vehicle", vehicle(e, bot), "health", v.health());
            Integer old = decoys.put(id, v.decoys());
            if (old != null && old > v.decoys()) event("missiles", "decoy", bot, "vehicle", vehicle(e, bot), "count", old - v.decoys(), "remaining", v.decoys());
            if (now() % config.snapshotTicks == 0 && !e.getPassengers().isEmpty()) event("snap", "vehicle_snap", bot, "vehicle", vehicle(e, bot), "pos", pos(e.position()), "health", v.health(), "maxHealth", v.maxHealth(), "parts", parts, "energy", v.energy(), "maxEnergy", v.maxEnergy(), "crew", e.getPassengers().stream().map(this::identity).toList());
            if (e.isRemoved() || e.getPassengers().isEmpty()) removeVehicle(id);
        }
    }
    private void removeVehicle(UUID id) {
        trackedVehicles.remove(id); vehicleObservers.remove(id); vehicleHealth.remove(id); vehicleParts.remove(id); decoys.remove(id); destroyed.remove(id);
    }

    public static String itemId(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    public static String item(ItemStack stack) { return stack.isEmpty() ? "minecraft:air" : BuiltInRegistries.ITEM.getKey(stack.getItem()) + "*" + stack.getCount(); }
    private Map<String, Object> damageData(LivingEntity victim, DamageSource source, float value) {
        Entity attacker = source.getEntity(); String type = source.typeHolder().unwrapKey().map(k -> k.location().toString()).orElse(source.getMsgId());
        return data("attacker", identity(attacker), "victim", identity(victim), "value", value, "damageType", type,
                "weapon", attacker instanceof LivingEntity living ? itemId(living.getMainHandItem()) : null, "weaponSource", "attacker_current_hand",
                "projectile", source.getDirectEntity() == attacker ? null : identity(source.getDirectEntity()), "distance", attacker == null ? null : tenth(attacker.distanceTo(victim)), "headshot", type.contains("headshot"), "health", victim.getHealth());
    }
    public void damage(LivingEntity victim, DamageSource source, float value) {
        if (!enabled() || value <= 0) return;
        Bot bot = victim instanceof Bot b ? b : source.getEntity() instanceof Bot b ? b : null;
        if (bot == null) return;
        Map<String, Object> d = damageData(victim, source, value); d.put("tick", now());
        if (victim instanceof Bot b) {
            stat(b).taken += value; Watch w = watches.computeIfAbsent(b.getUUID(), k -> new Watch());
            w.damage.addLast(d);
            while (w.damage.size() > 128) { w.historyDroppedAt = now(); w.damage.removeFirst(); }
            while (!w.damage.isEmpty() && ((Number) w.damage.getFirst().get("tick")).longValue() < now() - 100) w.damage.removeFirst();
        }
        if (source.getEntity() instanceof Bot b) { stat(b).dealt += value; stat(b).hits++; }
        emit("damage", "damage", bot, d);
    }
    public Map<String, Object> deathContext(Bot bot, DamageSource source) { return enabled() ? damageData(bot, source, 0) : null; }
    public void death(Bot bot, DamageSource source, Map<String, Object> context) {
        if (!enabled() || context == null) return;
        stat(bot).deaths++; if (source.getEntity() instanceof Bot killer) stat(killer).kills++;
        Watch w = watches.get(bot.getUUID()); context.put("recentDamage", w == null ? List.of() : w.damage.stream().filter(d -> ((Number) d.get("tick")).longValue() >= now() - 100).toList());
        context.put("recentDamageTruncated", w != null && now() - w.historyDroppedAt <= 100);
        emit("life", "death", bot, context);
    }
    public void fire(Bot bot, String weapon, Entity target, String kind) {
        if (!enabled()) return;
        stat(bot).fire++; event("weapons", "fire", bot, "weapon", weapon, "kind", kind, "target", identity(target), "distance", target == null ? null : tenth(bot.distanceTo(target)), "hit", null);
    }
    public void forget(Bot bot) { watches.remove(bot.getUUID()); }
    @Override public void close() {
        if (writer == null) return;
        end("logging_off_or_server_stop"); event("meta", "log_end", null, "dropped", dropped());
        writer.close(); retiring.add(writer); writer = null;
        watches.clear(); stats.clear(); vehicleHealth.clear(); decoys.clear(); trackedVehicles.clear(); vehicleObservers.clear(); vehicleParts.clear(); destroyed.clear();
    }
    public void shutdown() {
        close();
        long deadline = System.nanoTime() + 2_000_000_000L;
        for (var sink : retiring) try { sink.awaitClosed(Math.max(1, (deadline - System.nanoTime()) / 1_000_000L)); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
    }
}

package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.utils.SnbtConfig;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.regex.Pattern;

/** Targeted speech with per-speaker, category and listener limits; unavailable facts are never invented. */
public final class ChatterSystem {
    public record Phrase(String text, @Nullable String personality, boolean spicy) {}
    public record Category(long cooldown, double probability, String via, List<Phrase> phrases) {}
    private static final Pattern HEADER = Pattern.compile("\\[([a-z0-9_.]+)](.*)");
    private static final Pattern SLOT = Pattern.compile("\\{([a-z_]+)}");
    private static final Set<String> PERSONAS = Set.of("分析", "嘲讽", "冷血", "队长", "阴人");
    private static final class Speaker {
        long last = -10000;
        final Map<String, Long> next = new HashMap<>();
        final Map<String, Deque<Phrase>> bags = new HashMap<>();
    }
    private final BotManager manager;
    private final SkillSettings settings;
    private Map<String, Category> categories = Map.of();
    private final Map<UUID, Speaker> speakers = new HashMap<>();
    private final Map<UUID, Long> listeners = new HashMap<>();

    public ChatterSystem(BotManager manager, SkillSettings settings) {
        this.manager = manager; this.settings = settings;
        try { reload(); } catch (IOException | IllegalArgumentException e) { com.mojang.logging.LogUtils.getLogger().error("Cannot load chatter files", e); }
    }
    public Map<String, Category> categories() { return categories; }
    public void reload() throws IOException {
        Map<String, Category> checked = new LinkedHashMap<>();
        for (String name : List.of("enemy.zh_cn.txt", "ally.zh_cn.txt")) {
            var path = SnbtConfig.path("chatter/" + name);
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                try (var input = ChatterSystem.class.getResourceAsStream("/chatter/" + name)) {
                    if (input == null) throw new IOException("Missing bundled chatter: " + name);
                    Files.copy(input, path);
                }
            }
            if (Files.size(path) > 4 * 1024 * 1024) throw new IOException("Chatter file exceeds 4 MiB");
            for (var entry : parse(Files.readString(path, StandardCharsets.UTF_8)).entrySet()) {
                if (checked.putIfAbsent(entry.getKey(), entry.getValue()) != null) throw new IllegalArgumentException("Duplicate category: " + entry.getKey());
            }
        }
        categories = Map.copyOf(checked);
        speakers.values().forEach(s -> s.bags.clear());
    }
    public static Map<String, Category> parse(String text) {
        Map<String, Category> result = new LinkedHashMap<>();
        String id = null, via = "chat";
        long cooldown = 600; double probability = 1;
        List<Phrase> phrases = new ArrayList<>();
        for (String line : text.split("\\R")) {
            line = line.strip();
            if (line.isEmpty() || line.startsWith("#")) continue;
            var header = HEADER.matcher(line.split("#", 2)[0].strip());
            if (header.matches()) {
                if (id != null) result.put(id, new Category(cooldown, probability, via, List.copyOf(phrases)));
                id = header.group(1);
                if (!id.startsWith("enemy.") && !id.startsWith("ally.")) throw new IllegalArgumentException("Invalid category: " + id);
                if (result.containsKey(id)) throw new IllegalArgumentException("Duplicate category: " + id);
                cooldown = 600; probability = 1; via = "chat"; phrases = new ArrayList<>();
                for (String parameter : header.group(2).strip().split("\\s+")) {
                    if (parameter.isEmpty()) continue;
                    String[] pair = parameter.split("=", 2);
                    if (pair.length != 2) throw new IllegalArgumentException("Invalid header parameter: " + parameter);
                    switch (pair[0]) {
                        case "cd" -> {
                            double seconds = Double.parseDouble(pair[1]);
                            if (!Double.isFinite(seconds) || seconds < 0 || seconds > 86400) throw new IllegalArgumentException("Invalid cooldown");
                            cooldown = Math.round(seconds * 20);
                        }
                        case "p" -> { probability = Double.parseDouble(pair[1]); if (!Double.isFinite(probability) || probability < 0 || probability > 1) throw new IllegalArgumentException("Invalid probability"); }
                        case "via" -> { via = pair[1]; if (!Set.of("chat", "title", "actionbar").contains(via)) throw new IllegalArgumentException("Invalid channel"); }
                        default -> throw new IllegalArgumentException("Unknown header parameter: " + pair[0]);
                    }
                }
            } else {
                if (id == null || line.startsWith("[")) throw new IllegalArgumentException("Phrase has no valid category");
                String personality = null; boolean spicy = false;
                while (line.startsWith("!") || line.startsWith("@")) {
                    if (line.startsWith("!")) { spicy = true; line = line.substring(1).stripLeading(); }
                    else {
                        String[] pair = line.split("\\s+", 2);
                        personality = pair[0].substring(1);
                        if (!PERSONAS.contains(personality)) throw new IllegalArgumentException("Unknown personality: " + personality);
                        line = pair.length == 2 ? pair[1] : "";
                    }
                }
                if (!line.isBlank()) phrases.add(new Phrase(line, personality, spicy));
            }
        }
        if (id != null) result.put(id, new Category(cooldown, probability, via, List.copyOf(phrases)));
        return Map.copyOf(result);
    }
    @Nullable public static String render(String phrase, Map<String, String> values) {
        var matcher = SLOT.matcher(phrase); StringBuilder text = new StringBuilder();
        while (matcher.find()) {
            String value = values.get(matcher.group(1));
            if (value == null) return null;
            matcher.appendReplacement(text, java.util.regex.Matcher.quoteReplacement(value));
        }
        matcher.appendTail(text);
        return text.indexOf("{") >= 0 || text.indexOf("}") >= 0 ? null : text.toString();
    }
    public boolean eligible(Bot bot, String category, ServerPlayer listener, @Nullable LivingEntity enemy) {
        if (listener instanceof Terminator || listener.level() != bot.level() || !listener.isAlive()) return false;
        if (category.startsWith("ally.")) return TacticalSkill.allied(bot, listener) && bot.distanceTo(listener) <= 48;
        if (category.equals("enemy.team_callout")) return !bot.isAlliedTo(listener) && bot.distanceTo(listener) <= 48;
        return listener == enemy && !bot.isAlliedTo(listener);
    }
    public List<UUID> say(Bot bot, String categoryId, @Nullable LivingEntity enemy, Map<String, String> facts) {
        Category category = categories.get(categoryId);
        if (bot.hardness().level() != 10 || settings.chatter.equals("off") || category == null) return List.of();
        long now = manager.getServer().getTickCount();
        Speaker speaker = speakers.computeIfAbsent(bot.getUUID(), ignored -> new Speaker());
        if (now - speaker.last < 120 || now < speaker.next.getOrDefault(categoryId, 0L)) return List.of();
        List<ServerPlayer> recipients = manager.getServer().getPlayerList().getPlayers().stream().filter(p -> eligible(bot, categoryId, p, enemy)
                && now - listeners.getOrDefault(p.getUUID(), -10000L) >= 40).toList();
        if (recipients.isEmpty() || bot.getRandom().nextDouble() > category.probability()) return List.of();
        List<UUID> delivered = new ArrayList<>();
        for (ServerPlayer recipient : recipients) {
            Map<String, String> values = values(bot, recipient, enemy); values.putAll(facts);
            String bagKey = categoryId + "/" + settings.chatter + "/" + bot.personality();
            Deque<Phrase> bag = speaker.bags.computeIfAbsent(bagKey, ignored -> new ArrayDeque<>());
            if (bag.isEmpty()) {
                List<Phrase> available = new ArrayList<>(category.phrases().stream().filter(p -> (!p.spicy() || settings.chatter.equals("spicy"))
                        && (p.personality() == null || p.personality().equals(bot.personality())) && render(p.text(), values) != null).toList());
                Collections.shuffle(available, new Random(bot.getRandom().nextLong())); bag.addAll(available);
            }
            String phrase = null;
            while (!bag.isEmpty() && phrase == null) phrase = render(bag.removeFirst().text(), values);
            if (phrase == null) continue;
            Component message = Component.literal("<").append(bot.getDisplayName()).append("> " + phrase);
            switch (category.via()) {
                case "title" -> { recipient.connection.send(new ClientboundSetTitlesAnimationPacket(5, 35, 10)); recipient.connection.send(new ClientboundSetTitleTextPacket(message)); }
                case "actionbar" -> recipient.displayClientMessage(message, true);
                default -> recipient.sendSystemMessage(message);
            }
            delivered.add(recipient.getUUID()); listeners.put(recipient.getUUID(), now);
        }
        if (!delivered.isEmpty()) { speaker.last = now; speaker.next.put(categoryId, now + category.cooldown()); }
        return List.copyOf(delivered);
    }
    private Map<String, String> values(Bot bot, ServerPlayer listener, @Nullable LivingEntity enemy) {
        Map<String, String> values = new HashMap<>();
        values.put("player", listener.getGameProfile().getName()); values.put("bot", bot.getBotName());
        values.put("distance", Integer.toString(Math.round(bot.distanceTo(listener)))); values.put("hp", Integer.toString(Math.round(listener.getHealth() / 2)));
        values.put("weapon", listener.getMainHandItem().getHoverName().getString());
        values.put("totems", Integer.toString(count(listener, Items.TOTEM_OF_UNDYING)));
        values.put("gapples", Integer.toString(count(listener, Items.GOLDEN_APPLE) + count(listener, Items.ENCHANTED_GOLDEN_APPLE)));
        values.put("my_totems", Integer.toString(count(bot, Items.TOTEM_OF_UNDYING))); values.put("bot_kills", Integer.toString(bot.getKills()));
        if (enemy != null) values.put("enemy", enemy.getName().getString());
        Vec3Direction.put(values, listener.getX() - bot.getX(), listener.getZ() - bot.getZ());
        return values;
    }
    private static int count(Player player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) count += stack.getCount();
        if (player.getOffhandItem().is(item)) count += player.getOffhandItem().getCount();
        // Slot zero is a display copy for bots; the reserved real food is in it only while consuming.
        if (player instanceof Bot bot && !bot.isConsuming() && bot.getMainHandItem().is(item)) count -= bot.getMainHandItem().getCount();
        return count;
    }
    private static final class Vec3Direction {
        static void put(Map<String, String> values, double x, double z) {
            String[] directions = {"东", "东南", "南", "西南", "西", "西北", "北", "东北"};
            values.put("direction", directions[Math.floorMod((int) Math.round(Math.atan2(z, x) * 4 / Math.PI), 8)]);
        }
    }
    public void forget(Terminator bot) { speakers.remove(bot.getEntity().getUUID()); }
    public void prune() {
        if (manager.getServer().getTickCount() % 500 != 0) return;
        Set<UUID> online = new HashSet<>();
        manager.getServer().getPlayerList().getPlayers().forEach(p -> online.add(p.getUUID()));
        listeners.keySet().retainAll(online);
    }
}

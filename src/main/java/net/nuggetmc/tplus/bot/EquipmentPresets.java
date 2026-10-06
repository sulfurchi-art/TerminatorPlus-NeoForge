package net.nuggetmc.tplus.bot;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;
import net.nuggetmc.tplus.utils.SnbtConfig;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Per-world equipment snapshots, including item components, enchantments and durability. */
public final class EquipmentPresets {
    public record Preset(CompoundTag inventory, CompoundTag weapon, CompoundTag profile) {
        public Preset(CompoundTag inventory, CompoundTag weapon) { this(inventory, weapon, new CompoundTag()); }
        public void apply(Bot bot) {
            apply(bot, true);
        }
        public void validateTeam(MinecraftServer server) {
            String team = profile.getString("Team");
            if (!team.isEmpty() && !team.equals("none") && server.getScoreboard().getPlayerTeam(team) == null)
                throw new IllegalArgumentException("预设队伍不存在：" + team);
        }
        public void apply(Bot bot, boolean applyTeam) {
            var scoreboard = bot.getServer().getScoreboard();
            String team = profile.getString("Team");
            if (applyTeam) validateTeam(bot.getServer());
            bot.prepareEquipmentPreset();
            bot.getInventory().load(inventory.getList("Items", Tag.TAG_COMPOUND).copy());
            ItemStack first = bot.getInventory().items.getFirst();
            bot.getInventory().items.set(0, ItemStack.EMPTY);
            if (!first.isEmpty()) {
                for (int i = 1; i < 36; i++) {
                    if (bot.getInventory().items.get(i).isEmpty()) { bot.getInventory().items.set(i, first); break; }
                }
            }
            bot.getInventory().selected = 0;
            ItemStack preferred = ItemStack.parseOptional(bot.registryAccess(), weapon);
            // Keep a reference to the real stack, so durability cannot resurrect a copied preset weapon.
            bot.setDefaultItem(bot.findItem(s -> ItemStack.isSameItemSameComponents(s, preferred)));
            bot.setItem(null);
            if (profile.contains("Hardness")) bot.setHardnessOverride(profile.getInt("Hardness"));
            if (profile.contains("Personality")) bot.setPersonality(profile.getString("Personality"));
            bot.profileAbilities().clear();
            CompoundTag toggles = profile.getCompound("Abilities");
            for (String key : toggles.getAllKeys()) bot.profileAbilities().put(key, toggles.getBoolean(key));
            if (applyTeam && profile.contains("Team")) {
                scoreboard.removePlayerFromTeam(bot.getScoreboardName());
                if (!team.equals("none") && !team.isEmpty()) scoreboard.addPlayerToTeam(bot.getScoreboardName(), scoreboard.getPlayerTeam(team));
            }
            bot.setShieldEnabled(bot.getOffhandItem().is(net.minecraft.world.item.Items.SHIELD));
        }
    }

    private final MinecraftServer server;
    private final Path file;
    private final Path textFile;
    private final Map<String, Preset> presets = new LinkedHashMap<>();
    @Nullable private String selected;

    public EquipmentPresets(MinecraftServer server) {
        this(server, server.getWorldPath(LevelResource.ROOT).resolve("data/terminatorplus-presets.dat"), SnbtConfig.path("loadouts.snbt"));
    }

    public EquipmentPresets(MinecraftServer server, Path legacyFile, Path textFile) {
        this.server = server;
        this.file = legacyFile;
        this.textFile = textFile;
        try { reload(); }
        catch (IOException e) { throw new IllegalStateException("Cannot read equipment presets", e); }
    }

    public void reload() throws IOException {
        boolean migrate = !Files.isRegularFile(textFile) && Files.isRegularFile(file);
        if (Files.isRegularFile(textFile) || migrate) {
                CompoundTag root = migrate ? NbtIo.readCompressed(file, NbtAccounter.create(16 * 1024 * 1024)) : SnbtConfig.read(textFile);
                CompoundTag entries = root.getCompound("Presets");
                Map<String, Preset> checked = new LinkedHashMap<>();
                for (String key : entries.getAllKeys()) {
                    if (!key.matches("[a-zA-Z0-9_-]{1,32}")) throw new IOException("Invalid preset name: " + key);
                    CompoundTag entry = entries.getCompound(key);
                    validateInventory(entry.getCompound("Inventory"));
                    CompoundTag profile = entry.getCompound("Profile").copy();
                    validateProfile(profile);
                    checked.put(key, new Preset(entry.getCompound("Inventory").copy(), entry.getCompound("Weapon").copy(), profile));
                }
                String value = root.getString("Selected");
                if (!value.isEmpty() && !checked.containsKey(value)) throw new IOException("Unknown selected preset: " + value);
                if (migrate) SnbtConfig.write(textFile, root);
                presets.clear();
                presets.putAll(checked);
                selected = value.isEmpty() ? null : value;
        } else write();
    }

    private void validateInventory(CompoundTag inventory) throws IOException {
        Set<Integer> used = new java.util.HashSet<>();
        int backpack = 0;
        for (Tag item : inventory.getList("Items", Tag.TAG_COMPOUND)) {
            CompoundTag stack = (CompoundTag) item;
            int slot = stack.getByte("Slot") & 255;
            if (!used.add(slot) || !(slot < 36 || slot >= 100 && slot <= 103 || slot == 150)) throw new IOException("Invalid or duplicate inventory slot");
            if (slot < 36) backpack++;
            if (ItemStack.parseOptional(server.registryAccess(), stack).isEmpty()) throw new IOException("Invalid item in preset slot " + slot);
        }
        if (backpack > 35) throw new IOException("Presets need one free backpack slot for the AI hand");
    }

    public static Preset capture(ServerPlayer player) {
        long used = player.getInventory().items.stream().filter(s -> !s.isEmpty()).count();
        if (used > 35) throw new IllegalArgumentException("请空出一个背包格：机器人第 0 格用于 AI 换工具。");
        CompoundTag inventory = new CompoundTag();
        inventory.put("Items", player.getInventory().save(new ListTag()));
        return new Preset(inventory, (CompoundTag) player.getMainHandItem().saveOptional(player.registryAccess()));
    }

    public Set<String> names() { return Set.copyOf(presets.keySet()); }
    @Nullable public Preset get(String name) { return presets.get(name); }
    @Nullable public Preset selected() { return selected == null ? null : get(selected); }
    @Nullable public String selectedName() { return selected; }

    private static void validateProfile(CompoundTag profile) {
        if (profile.contains("Hardness")) new net.nuggetmc.tplus.api.agent.legacyagent.skill.Hardness(profile.getInt("Hardness"));
        if (profile.contains("Personality") && !Set.of("分析", "嘲讽", "冷血", "队长", "阴人").contains(profile.getString("Personality"))) throw new IllegalArgumentException("未知人设");
        var settings = new net.nuggetmc.tplus.api.agent.legacyagent.skill.SkillSettings();
        for (String key : profile.getCompound("Abilities").getAllKeys()) if (!settings.has(key)) throw new IllegalArgumentException("未知能力：" + key);
    }

    public void profile(String name, String field, String value, @Nullable String enabled) throws IOException {
        Preset previous = get(name);
        if (previous == null) throw new IllegalArgumentException("找不到预设：" + name);
        CompoundTag profile = previous.profile().copy();
        switch (field.toLowerCase(java.util.Locale.ROOT)) {
            case "hardness" -> { if (value.equals("inherit")) profile.remove("Hardness"); else profile.putInt("Hardness", Integer.parseInt(value)); }
            case "team" -> profile.putString("Team", value);
            case "personality" -> profile.putString("Personality", value);
            case "skin" -> profile.putString("Skin", value);
            case "ability" -> {
                if (!Set.of("true", "false", "inherit").contains(enabled == null ? "" : enabled)) throw new IllegalArgumentException("能力值必须为 true/false/inherit");
                CompoundTag toggles = profile.getCompound("Abilities").copy();
                if (enabled.equals("inherit")) toggles.remove(value); else toggles.putBoolean(value, Boolean.parseBoolean(enabled));
                profile.put("Abilities", toggles);
            }
            default -> throw new IllegalArgumentException("支持 hardness/team/personality/skin/ability");
        }
        validateProfile(profile);
        presets.put(name, new Preset(previous.inventory(), previous.weapon(), profile));
        try { write(); } catch (IOException e) { presets.put(name, previous); throw e; }
    }

    public void save(String name, ServerPlayer player) throws IOException {
        if (!name.matches("[a-zA-Z0-9_-]{1,32}")) throw new IllegalArgumentException("预设名只能包含 1–32 位字母、数字、下划线或连字符。");
        Preset snapshot = capture(player);
        Preset previous = presets.put(name, snapshot);
        try { write(); } catch (IOException e) { if (previous == null) presets.remove(name); else presets.put(name, previous); throw e; }
    }

    public void select(@Nullable String name) throws IOException {
        if (name != null && !presets.containsKey(name)) throw new IllegalArgumentException("找不到装备预设：" + name);
        String previous = selected;
        selected = name;
        try { write(); } catch (IOException e) { selected = previous; throw e; }
    }

    public void delete(String name) throws IOException {
        Preset previous = presets.remove(name);
        if (previous == null) throw new IllegalArgumentException("找不到装备预设：" + name);
        String previousSelection = selected;
        if (name.equals(selected)) selected = null;
        try { write(); } catch (IOException e) { presets.put(name, previous); selected = previousSelection; throw e; }
    }

    private void write() throws IOException {
        CompoundTag root = new CompoundTag();
        CompoundTag entries = new CompoundTag();
        presets.forEach((name, preset) -> {
            CompoundTag entry = new CompoundTag();
            entry.put("Inventory", preset.inventory().copy());
            entry.put("Weapon", preset.weapon().copy());
            entry.put("Profile", preset.profile().copy());
            entries.put(name, entry);
        });
        root.put("Presets", entries);
        if (selected != null) root.putString("Selected", selected);
        SnbtConfig.write(textFile, root);
    }
}

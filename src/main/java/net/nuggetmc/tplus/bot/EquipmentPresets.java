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
    public record Preset(CompoundTag inventory, CompoundTag weapon) {
        public void apply(Bot bot) {
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
        }
    }

    private final MinecraftServer server;
    private final Path file;
    private final Map<String, Preset> presets = new LinkedHashMap<>();
    @Nullable private String selected;

    public EquipmentPresets(MinecraftServer server) {
        this.server = server;
        file = server.getWorldPath(LevelResource.ROOT).resolve("data/terminatorplus-presets.dat");
        if (Files.isRegularFile(file)) {
            try {
                CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.create(16 * 1024 * 1024));
                CompoundTag entries = root.getCompound("Presets");
                for (String key : entries.getAllKeys()) {
                    CompoundTag entry = entries.getCompound(key);
                    presets.put(key, new Preset(entry.getCompound("Inventory"), entry.getCompound("Weapon")));
                }
                String value = root.getString("Selected");
                if (presets.containsKey(value)) selected = value;
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read equipment presets: " + file, e);
            }
        }
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
            entries.put(name, entry);
        });
        root.put("Presets", entries);
        if (selected != null) root.putString("Selected", selected);
        Files.createDirectories(file.getParent());
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(root, temp);
        try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (java.nio.file.AtomicMoveNotSupportedException e) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
    }
}

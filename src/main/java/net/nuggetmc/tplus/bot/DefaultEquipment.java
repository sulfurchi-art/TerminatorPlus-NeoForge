package net.nuggetmc.tplus.bot;

import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.Enchantments;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;

/** Fallback kits only. Explicit equipment presets always take precedence. */
public final class DefaultEquipment {
    private DefaultEquipment() {}

    public static void apply(Bot bot, int level, String mode) {
        if (!java.util.Set.of("vanilla", "warfare").contains(mode)) throw new IllegalArgumentException("Gear must be vanilla/warfare");
        if (mode.equals("warfare") && ((LegacyAgent) net.nuggetmc.tplus.TerminatorPlus.getManager().getAgent()).getSkills().warfare().available())
            applyWarfare(bot, level);
        else apply(bot, level);
    }

    public static void apply(Bot bot, int level) {
        if (level < 1 || level > 10) throw new IllegalArgumentException("AI hardness must be 1–10");
        bot.prepareEquipmentPreset();
        bot.getInventory().clearContent();
        Item sword = level == 1 ? Items.WOODEN_SWORD : level == 2 ? Items.STONE_SWORD
                : level <= 4 ? Items.IRON_SWORD : level <= 6 ? Items.DIAMOND_SWORD : Items.NETHERITE_SWORD;
        Item[] armor = level <= 2
                ? new Item[]{Items.LEATHER_BOOTS, Items.LEATHER_LEGGINGS, Items.LEATHER_CHESTPLATE, Items.LEATHER_HELMET}
                : level <= 4 ? new Item[]{Items.IRON_BOOTS, Items.IRON_LEGGINGS, Items.IRON_CHESTPLATE, Items.IRON_HELMET}
                : level <= 6 ? new Item[]{Items.DIAMOND_BOOTS, Items.DIAMOND_LEGGINGS, Items.DIAMOND_CHESTPLATE, Items.DIAMOND_HELMET}
                : new Item[]{Items.NETHERITE_BOOTS, Items.NETHERITE_LEGGINGS, Items.NETHERITE_CHESTPLATE, Items.NETHERITE_HELMET};
        EquipmentSlot[] slots = {EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};
        for (int i = 0; i < slots.length; i++) bot.setItemSlot(slots[i], new ItemStack(armor[i]));
        bot.giveItem(new ItemStack(sword));
        bot.setDefaultItem(bot.findItem(s -> s.is(sword)));
        if (level >= 4) {
            bot.giveItem(new ItemStack(Items.BOW));
            bot.giveItem(new ItemStack(Items.ARROW, level >= 7 ? 64 : 32));
            bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        }
        if (level >= 5) {
            bot.giveItem(new ItemStack(Items.ENDER_PEARL, level >= 8 ? 16 : 8));
            bot.giveItem(new ItemStack(Items.ELYTRA));
            bot.giveItem(new ItemStack(Items.FIREWORK_ROCKET, 64));
        }
        if (level >= 6) {
            bot.giveItem(new ItemStack(Items.MACE));
            bot.giveItem(new ItemStack(Items.WIND_CHARGE, 32));
        }
        if (level >= 7) bot.giveItem(new ItemStack(Items.NETHERITE_AXE));
        if (level >= 8) {
            bot.giveItem(new ItemStack(Items.GOLDEN_APPLE, level == 8 ? 8 : 16));
            bot.giveItem(new ItemStack(Items.COOKED_BEEF, 32));
            for (int i = 0; i < (level == 8 ? 2 : 3); i++) bot.giveItem(new ItemStack(Items.TOTEM_OF_UNDYING));
        }
        if (level >= 9) {
            bot.giveItem(new ItemStack(Items.ENCHANTED_GOLDEN_APPLE, 2));
            bot.giveItem(new ItemStack(Items.CHORUS_FRUIT, 16));
        }
        if (level >= 8) {
            bot.giveItem(new ItemStack(Items.SHIELD));
            bot.setItemSlot(EquipmentSlot.OFFHAND, new ItemStack(Items.TOTEM_OF_UNDYING));
        }
        if (level == 10) {
            for (int i = 0; i < 5; i++) bot.giveItem(new ItemStack(Items.TOTEM_OF_UNDYING));
            bot.giveItem(new ItemStack(Items.ENDER_PEARL, 16)); bot.giveItem(new ItemStack(Items.FIREWORK_ROCKET, 64));
        }
        finish(bot, level, sword, level >= 4);
    }
    private static ItemStack sbw(String name, int amount) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath("superbwarfare", name);
        if (!BuiltInRegistries.ITEM.containsKey(id)) throw new IllegalArgumentException("Missing SBW item " + id);
        Item item = BuiltInRegistries.ITEM.get(id); return new ItemStack(item, Math.min(amount, item.getDefaultMaxStackSize()));
    }
    private static void applyWarfare(Bot bot, int level) {
        if (level < 1 || level > 10) throw new IllegalArgumentException("AI hardness must be 1–10");
        // Resolve the whole kit before replacing equipment, so a missing item never leaves a half-equipped bot.
        java.util.List<ItemStack> inventory = new java.util.ArrayList<>();
        ItemStack blade = level == 10 ? sbw("electric_baton", 1) : ItemStack.EMPTY;
        if (!blade.isEmpty()) { net.nuggetmc.tplus.compat.WarfareItems.chargeBaton(blade); inventory.add(blade); }
        String primary = switch (level) {
            case 1 -> "mp_443"; case 2 -> "glock_17"; case 3 -> "mp_5"; case 4 -> "ak_47";
            case 5 -> "m_4"; case 6 -> "hk_416"; case 7 -> "ak_12"; case 8 -> "qbz_191"; default -> "mk_14";
        };
        inventory.add(sbw(primary, 1));
        String ammo = level <= 3 ? "handgun_ammo" : "rifle_ammo";
        for (int i = 0; i < (level >= 8 ? 3 : level >= 5 ? 2 : 1); i++) inventory.add(sbw(ammo, level == 1 ? 16 : level == 2 ? 32 : 64));
        if (level >= 5) { inventory.add(sbw(level >= 8 ? "aa_12" : "m_870", 1)); inventory.add(sbw("shotgun_ammo", 32 + (level - 5) * 6)); }
        if (level >= 7) { inventory.add(sbw("rpg", 1)); inventory.add(sbw("rpg_rocket_standard", 2 + (level - 7) * 2)); }
        if (level >= 7) { inventory.add(sbw("steel_block", 64)); inventory.add(sbw("steel_block", 64)); }
        if (level >= 9) { inventory.add(sbw("awm", 1)); inventory.add(sbw("sniper_ammo", level == 10 ? 32 : 16)); }
        if (level >= 4) { inventory.add(sbw("medical_kit", level - 2)); inventory.add(sbw("crust", 8 + (level - 4) * 4)); }
        if (level >= 5) inventory.add(sbw("m18_smoke_grenade", level - 3));
        if (level >= 6) inventory.add(sbw("hand_grenade", level - 4));
        if (level >= 8) inventory.add(sbw("armor_plate", level - 5));
        if (level >= 9) { inventory.add(sbw("c4_bomb", level - 7)); inventory.add(sbw("detonator", 1)); }
        if (level >= 8) {
            inventory.add(sbw("drone", 1)); inventory.add(sbw("monitor", 1));
            inventory.add(sbw("grenade_40mm", level == 8 ? 4 : level == 9 ? 8 : 12));
        }
        ItemStack helmet = sbw(level <= 3 ? "ge_helmet_m_35" : level <= 7 ? "ru_helmet_6b47" : "us_helmet_pasgt", 1);
        ItemStack chest = level < 3 ? ItemStack.EMPTY : sbw(level <= 7 ? "ru_chest_6b43" : "us_chest_iotv", 1);
        bot.prepareEquipmentPreset(); bot.getInventory().clearContent();
        bot.setItemSlot(EquipmentSlot.HEAD, helmet); bot.setItemSlot(EquipmentSlot.CHEST, chest);
        bot.setItemSlot(EquipmentSlot.LEGS, ItemStack.EMPTY); bot.setItemSlot(EquipmentSlot.FEET, ItemStack.EMPTY);
        bot.setItemSlot(EquipmentSlot.OFFHAND, level >= 8 ? new ItemStack(Items.TOTEM_OF_UNDYING) : ItemStack.EMPTY);
        inventory.forEach(bot::giveItem);
        if (level >= 5) {
            bot.giveItem(new ItemStack(Items.ELYTRA)); bot.giveItem(new ItemStack(Items.FIREWORK_ROCKET, 32 + (level - 5) * 6));
            bot.giveItem(new ItemStack(Items.ENDER_PEARL, level >= 8 ? 16 : 8));
        }
        if (level >= 8) for (int i = 0; i < level - 6; i++) bot.giveItem(new ItemStack(Items.TOTEM_OF_UNDYING));
        finish(bot, level, blade.getItem(), false);
    }
    private static void finish(Bot bot, int level, Item blade, boolean shield) {
        for (ItemStack stack : bot.getInventory().items) enchantKit(bot, stack, level);
        for (EquipmentSlot slot : EquipmentSlot.values()) if (slot != EquipmentSlot.MAINHAND) enchantKit(bot, bot.getItemBySlot(slot), level);
        bot.setDefaultItem(bot.findItem(s -> s.is(blade)));
        bot.setShieldEnabled(shield); bot.setItem(null);
    }
    private static void enchant(Bot bot, ItemStack stack, ResourceKey<Enchantment> key, int value) {
        if (value > 0) stack.enchant(bot.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(key), value);
    }
    private static void enchantKit(Bot bot, ItemStack stack, int level) {
        if (stack.isEmpty() || level == 1) return;
        Item item = stack.getItem();
        int attack = (int) Math.ceil((level - 1) * 5.0 / 9), protect = (int) Math.ceil((level - 1) * 4.0 / 9);
        boolean durable = item instanceof net.minecraft.world.item.ArmorItem || item instanceof net.minecraft.world.item.TieredItem
                || stack.is(Items.MACE) || stack.is(Items.BOW) || stack.is(Items.ELYTRA) || stack.is(Items.SHIELD);
        if (durable) {
            enchant(bot, stack, Enchantments.UNBREAKING, level >= 3 ? (int) Math.ceil((level - 2) * 3.0 / 8) : 0);
            if (level >= 9 && !stack.is(Items.BOW)) enchant(bot, stack, Enchantments.MENDING, 1);
        }
        if (item instanceof net.minecraft.world.item.ArmorItem armor) {
            enchant(bot, stack, Enchantments.PROTECTION, protect);
            if (level >= 7) enchant(bot, stack, Enchantments.THORNS, level - 6 > 3 ? 3 : level - 6);
            if (armor.getEquipmentSlot() == EquipmentSlot.FEET) {
                enchant(bot, stack, Enchantments.FEATHER_FALLING, level >= 4 ? Math.min(4, level - 3) : 0);
                enchant(bot, stack, Enchantments.DEPTH_STRIDER, level >= 8 ? level - 7 : 0);
                enchant(bot, stack, Enchantments.SOUL_SPEED, level >= 9 ? level == 10 ? 3 : 1 : 0);
            } else if (armor.getEquipmentSlot() == EquipmentSlot.HEAD) {
                enchant(bot, stack, Enchantments.RESPIRATION, level >= 7 ? Math.min(3, level - 6) : 0);
                enchant(bot, stack, Enchantments.AQUA_AFFINITY, level >= 8 ? 1 : 0);
            } else if (armor.getEquipmentSlot() == EquipmentSlot.LEGS) enchant(bot, stack, Enchantments.SWIFT_SNEAK, level >= 8 ? level - 7 : 0);
        } else if (item instanceof net.minecraft.world.item.SwordItem || item instanceof net.minecraft.world.item.AxeItem) {
            enchant(bot, stack, Enchantments.SHARPNESS, attack);
            enchant(bot, stack, Enchantments.FIRE_ASPECT, level >= 6 && item instanceof net.minecraft.world.item.SwordItem ? level >= 9 ? 2 : 1 : 0);
            if (item instanceof net.minecraft.world.item.SwordItem) {
                enchant(bot, stack, Enchantments.KNOCKBACK, level >= 8 ? level == 10 ? 2 : 1 : 0);
                enchant(bot, stack, Enchantments.SWEEPING_EDGE, level >= 7 ? Math.min(3, level - 6) : 0);
                enchant(bot, stack, Enchantments.LOOTING, level >= 8 ? level - 7 : 0);
            }
        } else if (stack.is(Items.BOW)) {
            enchant(bot, stack, Enchantments.POWER, attack);
            enchant(bot, stack, Enchantments.PUNCH, level >= 8 ? level == 10 ? 2 : 1 : 0);
            enchant(bot, stack, Enchantments.FLAME, level >= 9 ? 1 : 0);
            enchant(bot, stack, Enchantments.INFINITY, level == 10 ? 1 : 0);
        } else if (stack.is(Items.MACE)) {
            enchant(bot, stack, Enchantments.DENSITY, attack);
            enchant(bot, stack, Enchantments.WIND_BURST, level >= 8 ? level - 7 : 0);
            enchant(bot, stack, Enchantments.FIRE_ASPECT, level >= 9 ? 2 : level >= 7 ? 1 : 0);
        }
    }

}

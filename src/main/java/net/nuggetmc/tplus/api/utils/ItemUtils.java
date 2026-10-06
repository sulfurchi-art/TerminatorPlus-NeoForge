package net.nuggetmc.tplus.api.utils;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.HashMap;
import java.util.Map;

public class ItemUtils {

    private static Map<Item, Double> legacyDamage;

    /**
     * Pre-1.9 style attack damage of the item a bot is "holding" (its default item).
     */
    public static double getLegacyAttackDamage(ItemStack item) {
        if (legacyDamage == null) {
            legacyDamage = createTable();
        }

        return legacyDamage.getOrDefault(item.getItem(), 0.25);
    }

    private static Map<Item, Double> createTable() {
        Map<Item, Double> map = new HashMap<>();

        put(map, 1, Items.WOODEN_SHOVEL, Items.GOLDEN_SHOVEL, Items.WOODEN_HOE, Items.GOLDEN_HOE, Items.STONE_HOE,
                Items.IRON_HOE, Items.DIAMOND_HOE, Items.NETHERITE_HOE);
        put(map, 2, Items.WOODEN_PICKAXE, Items.GOLDEN_PICKAXE, Items.STONE_SHOVEL);
        put(map, 3, Items.WOODEN_AXE, Items.GOLDEN_AXE, Items.STONE_PICKAXE, Items.IRON_SHOVEL);
        put(map, 4, Items.WOODEN_SWORD, Items.GOLDEN_SWORD, Items.STONE_AXE, Items.IRON_PICKAXE, Items.DIAMOND_SHOVEL);
        put(map, 5, Items.STONE_SWORD, Items.IRON_AXE, Items.DIAMOND_PICKAXE, Items.NETHERITE_SHOVEL);
        put(map, 6, Items.IRON_SWORD, Items.DIAMOND_AXE, Items.NETHERITE_PICKAXE, Items.MACE);
        put(map, 7, Items.DIAMOND_SWORD, Items.NETHERITE_AXE);
        put(map, 8, Items.NETHERITE_SWORD);

        return map;
    }

    private static void put(Map<Item, Double> map, double damage, Item... items) {
        for (Item item : items) {
            map.put(item, damage);
        }
    }
}

package net.nuggetmc.tplus.compat;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.nuggetmc.tplus.bot.Bot;

public final class WarfareItems {
    private WarfareItems() {}
    public static boolean is(ItemStack stack, String name) {
        return !stack.isEmpty() && stack.getItem().toString().equals("superbwarfare:" + name);
    }
    public static void chargeBaton(Bot bot, ItemStack stack) {
        if (bot.hardness().level() != 10 || !is(stack, "electric_baton")) return;
        chargeBaton(stack);
    }
    public static void chargeBaton(ItemStack stack) {
        if (!is(stack, "electric_baton")) return;
        var energy = stack.getCapability(Capabilities.EnergyStorage.ITEM);
        if (energy == null) return;
        energy.receiveEnergy(energy.getMaxEnergyStored() - energy.getEnergyStored(), false);
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> tag.putBoolean("Open", true));
    }
    public static void tickBatons(Bot bot) {
        for (int i = 1; i < bot.getInventory().items.size(); i++) chargeBaton(bot, bot.getInventory().items.get(i));
        chargeBaton(bot, bot.getMainHandItem());
    }
}

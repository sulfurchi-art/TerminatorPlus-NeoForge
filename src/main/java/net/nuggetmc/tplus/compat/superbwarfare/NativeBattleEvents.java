package net.nuggetmc.tplus.compat.superbwarfare;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.nuggetmc.tplus.TerminatorPlus;
import net.nuggetmc.tplus.api.agent.legacyagent.LegacyAgent;
import net.nuggetmc.tplus.bot.Bot;
import net.nuggetmc.tplus.api.agent.legacyagent.skill.BattleLog;

/** Register once, resolve the current server at dispatch, and observe native post-shot events. */
public final class NativeBattleEvents {
    private static boolean registered;
    private NativeBattleEvents() {}
    @SuppressWarnings({"unchecked", "rawtypes"})
    public static void register() throws ReflectiveOperationException {
        if (registered) return;
        Class<? extends Event> shot = (Class<? extends Event>) Class.forName("com.atsuishio.superbwarfare.api.event.ShootEvent$Post");
        var shooter = shot.getMethod("getShooter"); var data = shot.getMethod("getData");
        var stack = Class.forName("com.atsuishio.superbwarfare.data.gun.GunData").getField("stack");
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, (Class) shot, (java.util.function.Consumer<Event>) event -> {
            var manager = TerminatorPlus.getManager(); if (manager == null) return;
            var skills = ((LegacyAgent) manager.getAgent()).getSkills(); if (!skills.battleLog().enabled()) return;
            try {
                if (shooter.invoke(event) instanceof Bot bot) {
                    ItemStack weapon = (ItemStack) stack.get(data.invoke(event));
                    skills.battleLog().fire(bot, BattleLog.itemId(weapon), skills.currentTarget(bot), bot.isPassenger() ? "mounted" : "handheld");
                }
            } catch (ReflectiveOperationException e) { com.mojang.logging.LogUtils.getLogger().error("Cannot record native shot", e); }
        });
        Class<? extends Event> hit = (Class<? extends Event>) Class.forName("com.atsuishio.superbwarfare.api.event.ProjectileHitEvent$HitEntity");
        var owner = hit.getMethod("getOwner"); var target = hit.getMethod("getTarget"); var head = hit.getMethod("isHeadshot"); var projectile = hit.getMethod("getProjectile");
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, false, (Class) hit, (java.util.function.Consumer<Event>) event -> {
            var manager = TerminatorPlus.getManager(); if (manager == null) return;
            var log = ((LegacyAgent) manager.getAgent()).getSkills().battleLog(); if (!log.enabled()) return;
            try { if (owner.invoke(event) instanceof Bot bot) log.event("weapons", "projectile_hit", bot, "target", log.identity((Entity) target.invoke(event)), "projectile", log.identity((Entity) projectile.invoke(event)), "headshot", head.invoke(event)); }
            catch (ReflectiveOperationException e) { com.mojang.logging.LogUtils.getLogger().error("Cannot record native projectile hit", e); }
        });
        registered = true;
    }
}

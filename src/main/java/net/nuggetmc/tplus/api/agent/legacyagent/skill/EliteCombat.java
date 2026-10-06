package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.bot.Bot;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Level ten reads real combat state and selects legal actions; survival always interrupts plans. */
final class EliteCombat {
    private enum Action { MACE, BOW, PEARL, FEINT_DIVE, FEINT_RETREAT }
    private final BotSkills skills;
    EliteCombat(BotSkills skills) { this.skills = skills; }

    void read(Terminator bot, BotMemory mem, @Nullable LivingEntity target, long now) {
        if (skills.hardness(bot).level() != 10 || target == null) {
            mem.window = BotMemory.Window.NONE; mem.combo = BotMemory.Combo.NONE; mem.comboConfirmed = false;
            mem.allIn = false; mem.readTarget = -1; mem.enemyLandingUntil = 0; return;
        }
        String hand = BuiltInRegistries.ITEM.getKey(target.getMainHandItem().getItem()).toString();
        String offhand = BuiltInRegistries.ITEM.getKey(target.getOffhandItem().getItem()).toString();
        boolean same = mem.readTarget == target.getId();
        if (!same) { mem.enemyLandingUntil = 0; clearIntercept(mem); if (mem.feintTarget != target.getId()) cancelFeint(mem); }
        mem.window = target.isBlocking() ? BotMemory.Window.SHIELD
                : target.isUsingItem() && (target.getUseItem().getFoodProperties(target) != null || target.getUseItem().is(Items.POTION)) ? BotMemory.Window.EATING
                : same && (!hand.equals(mem.previousHand) || !offhand.equals(mem.previousOffhand)) ? BotMemory.Window.EQUIPMENT_SWAP
                : target.isFallFlying() && (!same || !mem.wasGliding) ? BotMemory.Window.TAKEOFF
                : same && mem.wasAirborne && target.onGround() || now < mem.enemyLandingUntil ? BotMemory.Window.LANDING
                : target instanceof Player p && p.getAttackStrengthScale(0.5F) < 0.55F ? BotMemory.Window.ATTACK_COOLDOWN : BotMemory.Window.NONE;
        int oldTotems = mem.enemyTotems;
        mem.enemyTotems = mem.enemyApples = 0;
        if (target instanceof Player p) {
            for (ItemStack stack : p.getInventory().items) {
                if (stack.is(Items.TOTEM_OF_UNDYING)) mem.enemyTotems += stack.getCount();
                if (stack.is(Items.GOLDEN_APPLE) || stack.is(Items.ENCHANTED_GOLDEN_APPLE)) mem.enemyApples += stack.getCount();
            }
            if (p.getOffhandItem().is(Items.TOTEM_OF_UNDYING)) mem.enemyTotems += p.getOffhandItem().getCount();
        }
        mem.allIn = target instanceof Player && mem.enemyTotems == 0
                && (same && (oldTotems > 0 || mem.allIn) || target.getHealth() / target.getMaxHealth() < 0.5);
        mem.readTarget = target.getId(); mem.previousHand = hand; mem.previousOffhand = offhand;
        mem.wasAirborne = !target.onGround(); mem.wasGliding = target.isFallFlying();
        if (!same || now > mem.comboUntil) { mem.combo = BotMemory.Combo.NONE; mem.comboConfirmed = false; }
        if (mem.combo == BotMemory.Combo.MACE_CRITICAL && !mem.comboConfirmed && now - mem.comboStarted > 5
                && !mem.maceDrop && mem.flight == BotMemory.Flight.NONE) mem.combo = BotMemory.Combo.NONE;
        if (mem.window == BotMemory.Window.SHIELD && !bot.findItem(s -> s.getItem() instanceof AxeItem).isEmpty()) {
            if (mem.combo != BotMemory.Combo.AXE_SWORD) mem.comboConfirmed = false;
            mem.combo = BotMemory.Combo.AXE_SWORD; mem.comboUntil = now + 120;
        }
    }

    ItemStack weapon(Bot bot, LivingEntity target, BotMemory mem) {
        if (target.isBlocking()) {
            ItemStack axe = bot.findItem(s -> s.getItem() instanceof AxeItem);
            if (!axe.isEmpty()) return axe;
        }
        if (mem.comboConfirmed && (mem.combo == BotMemory.Combo.AXE_SWORD || mem.combo == BotMemory.Combo.MACE_CRITICAL)) {
            ItemStack sword = bot.findItem(s -> s.getItem() instanceof SwordItem);
            if (!sword.isEmpty()) return sword;
        }
        return bot.getWeapon();
    }

    boolean tryStart(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        if (mem.combo == BotMemory.Combo.AXE_SWORD) return false;
        if (mem.combo == BotMemory.Combo.BOW_MACE && mem.comboConfirmed && mem.bowTicks == 0 && (target.getDeltaMovement().y > 0.08 || target.fallDistance > 0.5)) {
            if (skills.mace().tryStart(bot, mem, target, now)) { mem.combo = BotMemory.Combo.MACE_CRITICAL; mem.comboConfirmed = false; mem.comboStarted = now; mem.comboUntil = now + 200; return true; }
        }
        if (mem.combo == BotMemory.Combo.BOW_MACE && !mem.comboConfirmed && mem.bowTicks == 0 && now < mem.comboUntil) return false;
        if (mem.combo == BotMemory.Combo.MACE_CRITICAL && mem.comboConfirmed && bot.getLocation().distanceTo(target.position()) < 5) return false;
        if (mem.allIn && bot.getLocation().distanceTo(target.position()) < 7) return false;
        if (mem.window == BotMemory.Window.EATING || mem.window == BotMemory.Window.ATTACK_COOLDOWN
                || mem.window == BotMemory.Window.EQUIPMENT_SWAP || mem.window == BotMemory.Window.LANDING) {
            if (bot.getLocation().distanceTo(target.position()) < 7) return false;
        }
        if (now < mem.nextEliteDecision) return false;
        mem.nextEliteDecision = now + 10;
        List<Action> choices = new ArrayList<>(List.of(Action.MACE, Action.BOW, Action.PEARL));
        if (!mem.allIn && now >= mem.nextFeint && skills.enabled(bot, "deception")) {
            choices.add(Action.FEINT_DIVE); choices.add(Action.FEINT_RETREAT);
        }
        while (!choices.isEmpty()) {
            double total = choices.stream().mapToDouble(c -> weight(c, target, mem)).sum();
            double sample = bot.getEntity().getRandom().nextDouble() * total;
            Action chosen = choices.getLast();
            for (var c : choices) {
                sample -= weight(c, target, mem);
                if (sample <= 0) { chosen = c; break; }
            }
            choices.remove(chosen);
            boolean started = switch (chosen) {
                case MACE -> skills.mace().tryStart(bot, mem, target, now);
                case BOW -> skills.bow().tryStart(bot, mem, target, now);
                case PEARL -> skills.pearls().tryGapClose(bot, mem, target, now);
                case FEINT_DIVE -> tryFeint(bot, mem, target, true, now);
                case FEINT_RETREAT -> tryFeint(bot, mem, target, false, now);
            };
            if (started) {
                mem.combo = chosen == Action.BOW && bot.hasMace() && skills.enabled(bot, "mace") ? BotMemory.Combo.BOW_MACE
                        : chosen == Action.MACE ? BotMemory.Combo.MACE_CRITICAL : BotMemory.Combo.NONE;
                mem.comboConfirmed = false; mem.comboStarted = now;
                mem.comboUntil = now + 240;
                return true;
            }
        }
        return skills.pilot().tryTravel(bot, mem, target, now);
    }

    private double weight(Action action, LivingEntity target, BotMemory mem) {
        return switch (action) {
            case FEINT_DIVE, FEINT_RETREAT -> 0.35;
            default -> skills.learning().weight(target.getUUID(), OpponentLearning.Move.valueOf(action.name()))
                    * (action == Action.BOW && mem.window == BotMemory.Window.TAKEOFF ? 3 : 1);
        };
    }

    boolean tryFeint(Terminator bot, BotMemory mem, LivingEntity target, boolean dive, long now) {
        if (skills.hardness(bot).level() != 10 || !skills.enabled(bot, "deception") || mem.allIn || now < mem.nextFeint
                || mem.tactic != BotMemory.Tactic.FIGHT || mem.feint != BotMemory.Feint.NONE || mem.interceptPoint != null
                || !target.isAlive() || target.level() != bot.getBotLevel() || bot.getEntity().isAlliedTo(target)
                || !bot.isBotOnGround() || bot.isBotInWater() || bot.isGliding() || mem.flight != BotMemory.Flight.NONE
                || mem.maceDrop || mem.bowTicks > 0 || bot instanceof Bot b && b.isConsuming()
                || bot.getBotHealth() / bot.getEntity().getMaxHealth() < 0.85 || now - mem.lastDamage < 40) return false;
        if (bot instanceof Bot gunner && skills.warfare().readyToEngage(gunner, target) && gunner.position().distanceToSqr(target.position()) > 25) return false;
        double distance = bot.getLocation().distanceTo(target.position());
        if (distance < 3 || distance > 24) return false;
        if (dive) {
            if (!skills.enabled(bot, "mace") || !bot.hasMace() || !skills.pilot().attackOpportunity(bot, mem, target) || !SkillUtil.openSky(bot.getBotLevel(), target)
                    || !skills.pilot().tryStart(bot, mem, BotMemory.FlightPlan.FEINT, now)) return false;
            mem.feint = BotMemory.Feint.CLIMB; mem.feintUntil = now + 240;
            mem.flightGoal = target.position();
        } else {
            Vec3 away = bot.getLocation().subtract(target.position()).multiply(1, 0, 1).normalize();
            Vec3 point = standable(bot, bot.getLocation().add(away.scale(6)), 2);
            if (point == null || !safeWalk(bot, point)) return false;
            mem.feint = BotMemory.Feint.RETREAT; mem.feintUntil = now + 25; mem.feintPoint = point;
            if (bot instanceof Bot b) skills.chatter().say(b, "enemy.retreat", target, java.util.Map.of());
        }
        mem.clearMovement(); mem.combo = BotMemory.Combo.NONE; mem.comboConfirmed = false;
        mem.feintSince = now; mem.feintTarget = target.getId(); mem.nextFeint = now + 240 + bot.getEntity().getRandom().nextInt(100);
        return true;
    }

    /** Returns true only for ground actions; the normal pilot handles every airborne action. */
    boolean tick(Terminator bot, BotMemory mem, @Nullable LivingEntity target, long now) {
        if (skills.hardness(bot).level() != 10 || target == null || !target.isAlive() || target.level() != bot.getBotLevel()
                || mem.tactic != BotMemory.Tactic.FIGHT || bot instanceof Bot b && b.isConsuming()) {
            cancelFeint(mem); clearIntercept(mem); return false;
        }
        if (!skills.enabled(bot, "deception") || mem.feintTarget != target.getId() || now - mem.lastDamage < 20
                || bot.getBotHealth() / bot.getEntity().getMaxHealth() < 0.8 || mem.allIn) cancelFeint(mem);
        if (mem.feint != BotMemory.Feint.NONE) {
            if (mem.feint == BotMemory.Feint.RETREAT) {
                if (now >= mem.feintUntil || bot.getLocation().distanceTo(mem.feintPoint) < 1) cancelFeint(mem);
                else if (safeWalk(bot, mem.feintPoint)) { move(bot, mem.feintPoint, target.getEyePosition()); return true; }
                else cancelFeint(mem);
            } else if (mem.flight == BotMemory.Flight.NONE && !bot.isGliding()) cancelFeint(mem);
            else if (mem.feint == BotMemory.Feint.CLIMB && bot.isGliding() && bot.getLocation().y - target.getY() >= 10
                    && SkillUtil.horizontalDistance(bot.getLocation(), target.position()) < 20) {
                mem.feint = BotMemory.Feint.DIVE; mem.feintUntil = now + 35;
            } else if (mem.feint == BotMemory.Feint.DIVE && (bot.getLocation().y - target.getY() < 6 || now >= mem.feintUntil
                    || target.isBlocking() || mem.window == BotMemory.Window.EQUIPMENT_SWAP)) {
                Vec3 side = target.getLookAngle().multiply(1, 0, 1).normalize();
                if (side.lengthSqr() < 0.1) side = new Vec3(1, 0, 0);
                mem.flightGoal = target.position().add(-side.z * 10, 14, side.x * 10);
                mem.feint = BotMemory.Feint.EXIT; mem.feintUntil = now + 45;
            } else if (now >= mem.feintUntil) cancelFeint(mem);
            if (mem.feint != BotMemory.Feint.NONE) return false;
        }
        return intercept(bot, mem, target, now);
    }

    private static void cancelFeint(BotMemory mem) {
        mem.feint = BotMemory.Feint.NONE; mem.feintPoint = null; mem.feintTarget = -1;
        if (mem.plan == BotMemory.FlightPlan.FEINT) { mem.plan = BotMemory.FlightPlan.TRAVEL; mem.flightGoal = null; }
    }

    private static void clearIntercept(BotMemory mem) {
        mem.interceptedPearl = -1; mem.pearlLanding = mem.interceptPoint = null;
        if (mem.combo == BotMemory.Combo.PEARL_AMBUSH) mem.combo = BotMemory.Combo.NONE;
        if (mem.plan == BotMemory.FlightPlan.INTERCEPT) { mem.plan = BotMemory.FlightPlan.TRAVEL; mem.flightGoal = null; }
    }

    private boolean intercept(Terminator bot, BotMemory mem, LivingEntity target, long now) {
        if (!skills.enabled(bot, "interception")) { clearIntercept(mem); return false; }
        if (mem.interceptPoint != null) {
            var entity = bot.getBotLevel().getEntity(mem.interceptedPearl);
            if (now > mem.interceptUntil || !(entity instanceof ThrownEnderpearl pearl) || pearl.getOwner() != target) {
                boolean landed = target.position().distanceTo(mem.pearlLanding) < 3;
                clearIntercept(mem);
                if (landed) { mem.enemyLandingUntil = now + 10; mem.window = BotMemory.Window.LANDING; mem.nextEliteDecision = now + 10; }
                return false;
            }
        }
        if (now >= mem.nextPearlRead) {
            mem.nextPearlRead = now + 5;
            var pearls = bot.getBotLevel().getEntitiesOfClass(ThrownEnderpearl.class, bot.getEntity().getBoundingBox().inflate(64), p -> p.getOwner() == target && p.isAlive());
            for (var pearl : pearls.stream().limit(4).toList()) {
                if (mem.interceptedPearl != -1 && mem.interceptedPearl != pearl.getId()) continue;
                var landing = PearlTrajectory.predict(pearl, 80);
                if (landing == null) { if (mem.interceptedPearl == pearl.getId()) clearIntercept(mem); continue; }
                Vec3 point = standable(bot, landing.position(), 3);
                if (point == null) { if (mem.interceptedPearl == pearl.getId()) clearIntercept(mem); continue; }
                double distance = SkillUtil.horizontalDistance(bot.getLocation(), point);
                if (mem.interceptedPearl == -1) {
                    if (mem.flight != BotMemory.Flight.NONE || mem.maceDrop || mem.bowTicks > 0 || !bot.isBotOnGround()) continue;
                    if (distance > Math.max(3, landing.ticks() * 0.3)) {
                        if (skills.enabled(bot, "pearls") && now >= mem.nextPearl && bot.getBotHealth() > 7 && bot.countItem(Items.ENDER_PEARL) > 0) {
                            var own = PearlAim.solve(bot.getBotLevel(), bot.getEntity(), point, 2.5);
                            if (own == null || own.ticks() > landing.ticks() + 3 || !bot.throwEnderPearl(own.yaw(), own.pitch())) continue;
                            mem.nextPearl = now + 100;
                        } else if (distance / 1.2 + 16 < landing.ticks() && skills.pilot().tryStart(bot, mem, BotMemory.FlightPlan.INTERCEPT, now)) {
                            mem.flightGoal = point;
                        } else continue;
                    }
                    mem.clearMovement(); mem.combo = BotMemory.Combo.PEARL_AMBUSH;
                }
                mem.interceptedPearl = pearl.getId(); mem.pearlLanding = landing.position(); mem.interceptPoint = point;
                mem.interceptUntil = now + landing.ticks() + 5;
                if (mem.plan == BotMemory.FlightPlan.INTERCEPT) mem.flightGoal = point;
                break;
            }
        }
        if (mem.interceptPoint == null || mem.flight != BotMemory.Flight.NONE || bot.isGliding()) return false;
        if (!bot.isBotOnGround()) return true;
        if (SkillUtil.horizontalDistance(bot.getLocation(), mem.interceptPoint) > 2.2) {
            if (!safeWalk(bot, bot.getLocation().add(mem.interceptPoint.subtract(bot.getLocation()).multiply(1, 0, 1).normalize()))) {
                clearIntercept(mem); return false;
            }
            move(bot, mem.interceptPoint, mem.pearlLanding.add(0, 1, 0));
        }
        else { bot.stand(); bot.faceLocation(mem.pearlLanding.add(0, 1, 0)); bot.setItem(bot.getWeapon()); }
        return true;
    }

    @Nullable
    private static Vec3 standable(Terminator bot, Vec3 point, int depth) {
        BlockPos base = BlockPos.containing(point);
        for (int dy = 1; dy >= -depth; dy--) {
            BlockPos feet = base.offset(0, dy, 0);
            if (!bot.getBotLevel().hasChunkAt(feet) || !SkillUtil.passable(bot.getBotLevel(), feet) || !SkillUtil.passable(bot.getBotLevel(), feet.above())
                    || !bot.getBotLevel().getBlockState(feet.below()).isFaceSturdy(bot.getBotLevel(), feet.below(), Direction.UP)
                    || !PearlAim.isSafeLanding(bot.getBotLevel(), feet.below()) || !bot.getBotLevel().getFluidState(feet).isEmpty()) continue;
            return Vec3.atBottomCenterOf(feet);
        }
        return null;
    }

    private static void move(Terminator bot, Vec3 point, Vec3 look) {
        Vec3 delta = point.subtract(bot.getLocation()).multiply(1, 0, 1);
        bot.stand(); bot.faceLocation(look); bot.setItem(bot.getWeapon());
        if (delta.lengthSqr() > 0.1 && bot.isBotOnGround()) {
            Vec3 step = delta.normalize().scale(0.2);
            if (bot.getEntity().horizontalCollision) bot.jump(step.add(0, 0.42, 0)); else bot.walk(step);
        }
    }

    private static boolean safeWalk(Terminator bot, Vec3 point) {
        if (!MovementBounds.contains(bot.getEntity(), point, 2)) return false;
        Vec3 delta = point.subtract(bot.getLocation()).multiply(1, 0, 1);
        for (int step = 1; step <= Math.ceil(delta.length()); step++) {
            Vec3 sample = bot.getLocation().add(delta.normalize().scale(Math.min(step, delta.length())));
            Vec3 floor = standable(bot, sample, 1);
            if (floor == null || Math.abs(floor.y - bot.getLocation().y) > 1.1) return false;
        }
        return true;
    }

    Vec3 waypoint(Terminator bot, BotMemory mem, LivingEntity target, Vec3 fallback, long now) {
        double distance = bot.getLocation().distanceTo(target.position());
        if (distance < 2 || distance > 18 || mem.tactic != BotMemory.Tactic.FIGHT) return fallback;
        if (now < mem.waypointUntil && mem.eliteWaypoint != null) return mem.eliteWaypoint;
        mem.waypointUntil = now + 10;
        Vec3 predicted = target.position().add(mem.targetVelocity.scale(5));
        Vec3 facing = target.getLookAngle().multiply(1, 0, 1).normalize();
        Vec3 best = fallback;
        double score = -Double.MAX_VALUE;
        for (int i = 0; i < 8; i++) {
            double angle = i * Math.PI / 4;
            Vec3 direction = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            Vec3 point = predicted.add(direction.scale(2.4));
            BlockPos feet = BlockPos.containing(point);
            if (!bot.getBotLevel().hasChunkAt(feet) || !SkillUtil.passable(bot.getBotLevel(), feet) || !SkillUtil.passable(bot.getBotLevel(), feet.above())
                    || !bot.getBotLevel().getBlockState(feet.below()).isFaceSturdy(bot.getBotLevel(), feet.below(), Direction.UP)) continue;
            double value = -direction.dot(facing) * 3 - point.distanceTo(bot.getLocation()) * 0.2;
            BlockPos behindEnemy = BlockPos.containing(predicted.subtract(direction.scale(3)));
            if (bot.getBotLevel().hasChunkAt(behindEnemy) && !PearlAim.isSafeLanding(bot.getBotLevel(), behindEnemy.below())) value += 2;
            value += bot.getEntity().getRandom().nextDouble() * 0.4;
            if (value > score) { score = value; best = point; }
        }
        mem.eliteWaypoint = best;
        return best;
    }
}

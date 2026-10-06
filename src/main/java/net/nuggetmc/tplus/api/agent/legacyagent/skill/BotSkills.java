package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.nuggetmc.tplus.bot.Bot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * The abilities layered on top of the legacy agent: getting over obstacles and out of stuck positions, elytra flight,
 * mace smashes and ender pearls. The legacy agent calls in at three points of its per-bot tick, see
 * {@link #tickActive}, {@link #tryStart} and {@link #tickNavigation}.
 */
public class BotSkills {

    private final BotManager manager;
    private final SkillSettings settings;
    private final Map<Terminator, BotMemory> memories = new HashMap<>();
    private final BotPathfinder pathfinder = new BotPathfinder();
    private final Navigator navigator;
    private final ElytraPilot pilot;
    private final MaceSkill mace;
    private final PearlSkill pearls;
    private final BowSkill bow;
    private final TacticalSkill tactics;
    private final EliteCombat elite;
    private final OpponentLearning learning;
    private final TeamBoard teams;
    private final ChatterSystem chatter;
    private final ShieldDefense defense;
    private final net.nuggetmc.tplus.compat.WarfareSupport warfare;

    public BotSkills(BotManager manager, SkillSettings settings) {
        this.manager = manager;
        this.settings = settings;
        this.navigator = new Navigator(this);
        this.pilot = new ElytraPilot(this);
        this.mace = new MaceSkill(this);
        this.pearls = new PearlSkill(this);
        this.bow = new BowSkill(this);
        this.tactics = new TacticalSkill(this, manager);
        this.elite = new EliteCombat(this);
        this.learning = new OpponentLearning(manager.getServer());
        this.teams = new TeamBoard(manager, this);
        this.chatter = new ChatterSystem(manager, settings);
        this.defense = new ShieldDefense(this);
        this.warfare = new net.nuggetmc.tplus.compat.WarfareSupport(this);
    }

    public SkillSettings settings() {
        return settings;
    }

    public BotPathfinder pathfinder() {
        return pathfinder;
    }

    ElytraPilot pilot() {
        return pilot;
    }

    MaceSkill mace() {
        return mace;
    }

    PearlSkill pearls() {
        return pearls;
    }

    public BotMemory memory(Terminator bot) {
        return memories.computeIfAbsent(bot, b -> new BotMemory());
    }

    public void forget(Terminator bot) {
        warfare.forget(bot);
        teams.remove(bot, now());
        chatter.forget(bot);
        finishAttempt(memories.get(bot));
        tactics.cleanup(bot, memories.get(bot));
        memories.remove(bot);
    }

    public void clear() {
        warfare.clear();
        teams.clear();
        memories.values().forEach(this::finishAttempt);
        memories.forEach(tactics::cleanup);
        memories.clear();
        pathfinder.clear();
    }

    private long now() {
        return manager.getServer().getTickCount();
    }

    BowSkill bow() { return bow; }
    public ChatterSystem chatter() { return chatter; }
    public net.nuggetmc.tplus.compat.WarfareSupport warfare() { return warfare; }
    public java.util.Collection<Terminator> bots() { return manager.fetch(); }
    public void clearTeamFocus() {
        teams.clear();
        memories.values().forEach(m -> { m.teamRole = BotMemory.TeamRole.NONE; m.guardedAlly = -1; m.rolePoint = null; });
    }
    public void sweepTemporaryBlocks() { tactics.sweep(); chatter.prune(); if (now() % 20 == 0) teams.sweep(now()); }
    public void onPlayerAttack(net.minecraft.server.level.ServerPlayer player, LivingEntity target) { teams.attack(player, target, now()); }
    public void onTeamDamage(LivingEntity victim, LivingEntity attacker) { teams.damage(victim, attacker, now()); }
    @Nullable net.minecraft.server.level.ServerPlayer guardedAlly(Terminator bot) { return teams.ward(bot); }
    public void invalidateCover(net.minecraft.server.level.ServerLevel world, net.minecraft.core.BlockPos position) {
        tactics.invalidate(net.minecraft.core.GlobalPos.of(world.dimension(), position), memories.values());
    }
    public OpponentLearning learning() { return learning; }
    public void saveLearning() throws java.io.IOException {
        memories.values().forEach(this::finishAttempt);
        learning.save();
    }

    public void beginAttempt(Terminator bot, @Nullable LivingEntity target, OpponentLearning.Move move) {
        if (hardness(bot).level() != 10 || !(target instanceof net.minecraft.server.level.ServerPlayer) || target instanceof Terminator) return;
        BotMemory mem = memory(bot);
        finishAttempt(mem);
        mem.pendingOpponent = target.getUUID(); mem.pendingMove = move; mem.pendingUntil = now() + 100; mem.pendingHit = false;
    }
    private void finishAttempt(@Nullable BotMemory mem) {
        if (mem != null && mem.pendingOpponent != null && mem.pendingMove != null) {
            learning.observe(mem.pendingOpponent, mem.pendingMove, mem.pendingHit);
            mem.pendingOpponent = null; mem.pendingMove = null;
        }
    }
    public void confirmHit(Terminator bot, LivingEntity target) {
        BotMemory mem = memory(bot);
        if (target.getUUID().equals(mem.pendingOpponent) && now() <= mem.pendingUntil) mem.pendingHit = true;
    }
    public void onConfirmedDamage(Bot bot, LivingEntity target, boolean arrow, boolean smash) {
        BotMemory mem = memory(bot);
        OpponentLearning.Move actual = arrow ? OpponentLearning.Move.BOW : smash ? OpponentLearning.Move.MACE : OpponentLearning.Move.MELEE;
        if ((actual == mem.pendingMove || actual == OpponentLearning.Move.MELEE && mem.pendingMove == OpponentLearning.Move.SHIELD_BREAK)
                && target.getUUID().equals(mem.pendingOpponent) && now() <= mem.pendingUntil) mem.pendingHit = true;
        if (mem.trackedTarget == target.getId()) {
            mem.combatHits++;
            if (smash) mem.combatSmashes++;
            if (arrow && mem.combo == BotMemory.Combo.BOW_MACE || smash && mem.combo == BotMemory.Combo.MACE_CRITICAL) {
                mem.comboConfirmed = true; mem.comboUntil = now() + 80;
            }
        }
        if (bot.getMainHandItem().getItem() instanceof net.minecraft.world.item.SwordItem
                && (mem.combo == BotMemory.Combo.AXE_SWORD || mem.combo == BotMemory.Combo.MACE_CRITICAL)) mem.combo = BotMemory.Combo.NONE;
        if (smash || arrow) chatter.say(bot, smash ? "enemy.mace_hit" : "enemy.arrow_hit", target, combatFacts(mem));
    }
    public void onCriticalHit(Bot bot, LivingEntity target) { chatter.say(bot, "enemy.crit", target, combatFacts(memory(bot))); }
    public void onShieldBreak(Bot bot, LivingEntity target) {
        confirmHit(bot, target);
        memory(bot).comboConfirmed = true;
        chatter.say(bot, "enemy.shield_break", target, Map.of());
        chatter.say(bot, "ally.shield_break", target, Map.of());
    }
    private Map<String, String> combatFacts(BotMemory mem) {
        return Map.of("hits", Integer.toString(mem.combatHits), "smashes", Integer.toString(mem.combatSmashes),
                "time", Long.toString(Math.max(0, now() - mem.encounterSince) / 20));
    }
    private void speech(Terminator terminator, BotMemory mem, @Nullable LivingEntity target, long now) {
        if (!(terminator instanceof Bot bot) || hardness(bot).level() != 10) return;
        String team = bot.getTeam() == null ? "" : bot.getTeam().getName();
        if (!team.equals(mem.speechTeam) && !team.isEmpty()) chatter.say(bot, "ally.greet", target, Map.of());
        mem.speechTeam = team;
        int id = target == null ? -1 : target.getId();
        if (id != mem.speechTarget && id != -1) {
            mem.encounterSince = now; mem.combatHits = mem.combatSmashes = 0;
            chatter.say(bot, "enemy.lock_on", target, combatFacts(mem));
            chatter.say(bot, "ally.callout_enemy", target, Map.of());
        }
        mem.speechTarget = id;
        if (mem.window != mem.speechWindow) {
            if (mem.window == BotMemory.Window.EATING) chatter.say(bot, "enemy.player_eating", target, combatFacts(mem));
            if (mem.window == BotMemory.Window.TAKEOFF) chatter.say(bot, "enemy.player_elytra", target, combatFacts(mem));
            mem.speechWindow = mem.window;
        }
        if (mem.tactic != mem.speechTactic) {
            if (mem.tactic == BotMemory.Tactic.RETREAT) { chatter.say(bot, "enemy.retreat", target, combatFacts(mem)); chatter.say(bot, "ally.retreat", target, Map.of()); }
            if (mem.tactic == BotMemory.Tactic.COVER_ALLY) chatter.say(bot, "ally.cover", target, Map.of());
            mem.speechTactic = mem.tactic;
        }
        boolean dive = mem.maceDrop || mem.plan == BotMemory.FlightPlan.MACE && mem.flight != BotMemory.Flight.NONE
                || mem.feint == BotMemory.Feint.DIVE;
        if (dive && !mem.speechDive) chatter.say(bot, "enemy.dive_warn", target, combatFacts(mem));
        mem.speechDive = dive;
        if (target == null && bot.tickDelay(200)) chatter.say(bot, "ally.idle", null, Map.of());
    }
    public net.minecraft.world.item.ItemStack meleeWeapon(Bot bot, LivingEntity target) {
        return hardness(bot).level() == 10 ? elite.weapon(bot, target, memory(bot)) : bot.getWeapon();
    }
    public boolean tryFeint(Terminator bot, LivingEntity target, boolean dive) { return elite.tryFeint(bot, memory(bot), target, dive, now()); }
    public LivingEntity currentTarget(Terminator bot) {
        var entity = bot.getBotLevel().getEntity(memory(bot).trackedTarget);
        return entity instanceof LivingEntity living && living.isAlive() ? living : null;
    }

    public boolean canSee(Terminator bot, LivingEntity target) {
        if (!limitedPerception(bot)) return true;
        Vec3 direction = target.getEyePosition().subtract(bot.getEntity().getEyePosition());
        return (direction.lengthSqr() < 4 || bot.getEntity().getLookAngle().dot(direction.normalize()) >= 0.34)
                && bot.getEntity().hasLineOfSight(target);
    }
    private boolean limitedPerception(Terminator bot) {
        return hardness(bot).limitedPerception() || hardness(bot).level() < 10 && bot instanceof Bot b
                && (enabled(bot, "guns") && warfare.carriesGun(b) || b.isPassenger() && warfare.available() && warfare.isVehicle(b.getVehicle()));
    }
    public boolean canAcquire(Terminator bot, LivingEntity target) {
        if (bot.getEntity().isPassenger() && bot.getEntity().getRootVehicle() == target.getRootVehicle()) return false;
        if (canSee(bot, target)) return true;
        Vec3 movement = target instanceof Terminator t ? t.getVelocity() : target.getDeltaMovement();
        if (!target.isShiftKeyDown() && target.position().distanceTo(bot.getLocation()) < 12
                && (movement.lengthSqr() > 0.01 || target.swinging)) {
            BotMemory mem = memory(bot);
            // A sound gives a coarse search location, not an entity lock or hidden velocity updates.
            mem.lastSeen = new Vec3(Math.floor(target.getX() / 2) * 2 + 1, target.getY(), Math.floor(target.getZ() / 2) * 2 + 1);
            mem.lastSeenTick = now();
        }
        return false;
    }

    public Hardness hardness(Terminator bot) {
        return settings.profile(bot.getEntity() instanceof Bot b && b.getHardnessOverride() != 0
                ? b.getHardnessOverride() : settings.hardness);
    }

    public boolean enabled(Terminator bot, String name) {
        Boolean value = bot instanceof Bot b ? b.profileAbilities().getOrDefault(name, settings.get(name)) : settings.get(name);
        return Boolean.TRUE.equals(value) && hardness(bot).allows(name);
    }

    public void onDamage(Terminator bot, LivingEntity attacker) {
        BotMemory mem = memory(bot);
        long tick = now();
        if (tick - mem.pressureSince > 60) { mem.pressureSince = tick; mem.pressureHits = 0; }
        mem.pressureHits++;
        mem.lastDamage = tick;
        mem.threat = attacker.position();
    }

    public boolean react(Terminator bot, @Nullable LivingEntity target) {
        BotMemory mem = memory(bot);
        int id = target == null ? -1 : target.getId();
        if (mem.trackedTarget != id) mem.targetAcquired = now();
        return target != null && now() - mem.targetAcquired >= hardness(bot).reactionTicks();
    }

    public Vec3 pursuitPoint(Terminator bot, LivingEntity target, Vec3 fallback) {
        if (now() < memory(bot).groundFallbackUntil) return MovementBounds.clamp(bot.getEntity(), target.position(), 3);
        if (hardness(bot).level() == 10) fallback = elite.waypoint(bot, memory(bot), target, fallback, now());
        fallback = tactics.pursuitPoint(bot, memory(bot), target, fallback);
        fallback = teams.pursuit(bot, memory(bot), target, fallback, now());
        fallback = bot instanceof Bot b ? warfare.pursuit(b, target, fallback, now()) : fallback;
        return MovementBounds.clamp(bot.getEntity(), fallback, 3);
    }

    public LivingEntity coordinateTarget(Terminator bot, @Nullable LivingEntity target) { return teams.select(bot, target, now()); }
    boolean allowDive(Terminator bot, LivingEntity target, long now) { return teams.dive(bot, target, now); }
    @Nullable Vec3 diveStaging(Terminator bot, LivingEntity target, long now) { return teams.diveStaging(bot, target, now); }

    public boolean meleeReady(Terminator bot) {
        BotMemory mem = memory(bot);
        if (now() < mem.nextMelee) return false;
        mem.nextMelee = now() + hardness(bot).attackInterval();
        return true;
    }

    /**
     * Abilities that take full control of the bot while they run: elytra flights, mace dives, drawing the bow and pearl
     * saves from the void. Called first thing every tick, also without a target.
     *
     * @return true if the bot is busy with one of them (the legacy logic is skipped then)
     */
    public boolean tickActive(Terminator bot, @Nullable LivingEntity target) {
        BotMemory mem = memory(bot);
        long now = now();
        if (bot instanceof Bot b) warfare.tickInventory(b, now);

        if (bot.isBotOnGround()) {
            mem.lastGround = bot.getLocation();
        }

        mem.gunEligible = false;
        trackTarget(mem, target);
        if (target == null || !enabled(bot, "teamwork") || bot.getEntity().getTeam() == null) {
            mem.teamRole = BotMemory.TeamRole.NONE; mem.guardedAlly = -1; mem.rolePoint = null;
        }
        if (target != null && limitedPerception(bot)) { mem.lastSeen = target.position(); mem.lastSeenTick = now; }
        elite.read(bot, mem, target, now);
        if (mem.pendingOpponent != null && now > mem.pendingUntil) finishAttempt(mem);

        // a new totem in the off hand as soon as one pops
        if (enabled(bot, "totems")) {
            bot.equipTotem();
        }

        if (bot instanceof Bot b && warfare.tickCrew(b, target, mem.targetVelocity, now)) {
            speech(bot, mem, target, now); return true;
        }

        boolean tacticalBusy = tactics.tick(bot, mem, target, now);
        teams.updateDive(bot, target, now);
        if (tacticalBusy) { if (bot instanceof Bot b) warfare.release(b); elite.tick(bot, mem, target, now); defense.tick(bot, mem, target, now); speech(bot, mem, target, now); return true; }

        if (pearls.tryVoidRescue(bot, mem, target, now)) {
            if (bot instanceof Bot b) warfare.release(b);
            speech(bot, mem, target, now);
            return true;
        }

        boolean eliteBusy = elite.tick(bot, mem, target, now);
        boolean defending = defense.tick(bot, mem, target, now);
        speech(bot, mem, target, now);
        if (eliteBusy || defending) { if (bot instanceof Bot b) warfare.release(b); return true; }

        if (mem.flight != BotMemory.Flight.NONE || bot.isGliding()) {
            if (bot instanceof Bot b) warfare.release(b);
            pilot.tick(bot, mem, target, now);
            return true;
        }

        if (mem.maceDrop) {
            if (bot instanceof Bot b) warfare.release(b);
            return mace.tickDrop(bot, mem, target, now);
        }

        if (mem.bowTicks > 0) {
            if (bot instanceof Bot b) warfare.release(b);
            return bow.tick(bot, mem, target, now);
        }

        if (bot instanceof Bot b) mem.gunEligible = warfare.prepare(b, target, mem.targetVelocity, now);
        if (target == null && mem.gunEligible) return true;
        if (bot instanceof Bot b && !mem.gunEligible && (target == null ? teams.idle(b, mem) : teams.position(b, mem, target, now))) return true;

        if (target == null && limitedPerception(bot) && mem.lastSeen != null
                && (now - mem.lastSeenTick < 100 || now < mem.recoverySearchUntil)) {
            Vec3 delta = mem.lastSeen.subtract(bot.getLocation()).multiply(1, 0, 1);
            bot.faceLocation(mem.lastSeen);
            if (delta.lengthSqr() > 2 && bot.isBotOnGround()) searchLastSeen(bot, mem, now);
            else if (bot.tickDelay(10)) bot.setLook(bot.getEntity().getYRot() + 30, 0);
            return true;
        }
        if (target == null && limitedPerception(bot) && bot.tickDelay(10)) bot.setLook(bot.getEntity().getYRot() + 30, 0);

        return false;
    }

    /** Walk toward the last observed position, using existing pathfinding around cover. */
    private void searchLastSeen(Terminator bot, BotMemory mem, long now) {
        Vec3 goal = MovementBounds.clamp(bot.getEntity(), mem.lastSeen, 3);
        if (enabled(bot, "pathfinding")) {
            if (now >= mem.nextSearchPath) {
                mem.nextSearchPath = now + 20;
                int y = SkillUtil.surfaceY(bot.getBotLevel(), goal);
                mem.searchPath = pathfinder.find(bot.getBotLevel(), bot.getEntity(), net.minecraft.core.BlockPos.containing(goal.x, y, goal.z), 32);
                mem.searchPathIndex = 1;
            }
            if (mem.searchPath != null) {
                while (mem.searchPathIndex < mem.searchPath.size() && bot.getLocation().distanceToSqr(Vec3.atBottomCenterOf(mem.searchPath.get(mem.searchPathIndex))) < 0.8) mem.searchPathIndex++;
                if (mem.searchPathIndex < mem.searchPath.size()) goal = Vec3.atBottomCenterOf(mem.searchPath.get(mem.searchPathIndex));
            }
        }
        goal = MovementBounds.clamp(bot.getEntity(), goal, 3);
        Vec3 step = goal.subtract(bot.getLocation()).multiply(1, 0, 1).normalize().scale(0.2);
        if (goal.y > bot.getLocation().y + 0.3 || bot.getEntity().horizontalCollision) bot.jump(step.add(0, 0.42, 0));
        else bot.walk(step);
    }

    public void afterGunReload(Bot bot, int duration) {
        BotMemory mem = memory(bot);
        if (mem.lastSeen != null && now() - mem.lastSeenTick <= Math.max(100, duration + 100))
            mem.recoverySearchUntil = Math.max(mem.recoverySearchUntil, now() + 100);
    }

    public void finishWeapons(Terminator bot) {
        BotMemory mem = memory(bot);
        if (mem.gunEligible && bot instanceof Bot b && warfare.holding(b) && !b.isGliding() && !b.isBotBlocking()
                && mem.flight == BotMemory.Flight.NONE && !mem.maceDrop && mem.bowTicks == 0) {
            warfare.tick(b, currentTarget(bot), mem.targetVelocity, now());
        }
    }
    public boolean tryStart(Terminator bot, LivingEntity target) {
        if (!bot.isBotOnGround() || bot.isBotInWater()) return false;

        BotMemory mem = memory(bot);
        long now = now();

        if (bot instanceof Bot b && b.isConsuming() || mem.tactic == BotMemory.Tactic.COVER_ALLY) return false;
        if (bot instanceof Bot b && warfare.holding(b)
                && (warfare.reloadCommitted(b) || warfare.canEngage(b, target))) return false;
        if (hardness(bot).tactical()) {
            // A modulo gate can miss every brief landing when its period aliases the jump cycle.
            if (now < mem.nextSkillDecision) return false;
            mem.nextSkillDecision = now + hardness(bot).skillInterval();
            if (hardness(bot).level() == 10) return elite.tryStart(bot, mem, target, now);
        } else if (!bot.tickDelay(hardness(bot).skillInterval())) return false;

        if (mace.tryStart(bot, mem, target, now)) return true;
        if (pearls.tryGapClose(bot, mem, target, now)) return true;
        if (!(bot instanceof Bot b && warfare.holding(b)) && bow.tryStart(bot, mem, target, now)) return true;
        return pilot.tryTravel(bot, mem, target, now);
    }

    /**
     * Follows how the target moves, so arrows can lead it.
     */
    private static void trackTarget(BotMemory mem, @Nullable LivingEntity target) {
        if (target == null) {
            mem.trackedTarget = -1;
            mem.lastTargetPos = null;
            mem.targetVelocity = Vec3.ZERO;
            return;
        }

        Vec3 pos = target.position();

        if (mem.trackedTarget == target.getId() && mem.lastTargetPos != null) {
            Vec3 moved = pos.subtract(mem.lastTargetPos);
            // a teleport isn't movement
            if (moved.lengthSqr() > 16) moved = Vec3.ZERO;
            mem.targetVelocity = mem.targetVelocity.scale(0.6).add(moved.scale(0.4));
        } else {
            mem.targetVelocity = Vec3.ZERO;
        }

        mem.trackedTarget = target.getId();
        mem.lastTargetPos = pos;
    }

    /**
     * Climbing and stuck recovery.
     *
     * @return true if the bot's movement is handled for this tick
     */
    public boolean tickNavigation(Terminator bot, LivingEntity target) {
        return navigator.tick(bot, memory(bot), target, now());
    }

    /**
     * The legacy agent broke a block for the bot: digging counts as progress.
     */
    public void onBlockBroken(Terminator bot) {
        BotMemory mem = memories.get(bot);
        if (mem != null) {
            mem.lastProgress = now();
        }
    }

    /**
     * The legacy agent keeps hitting a block nobody can break: don't wait for the usual stuck timeout.
     */
    public void onUnbreakable(Terminator bot) {
        BotMemory mem = memory(bot);
        long now = now();

        if (mem.unbreakableSince < 0 || now - mem.unbreakableSince > 60) {
            mem.unbreakableSince = now;
        } else if (now - mem.unbreakableSince > 10) {
            mem.lastProgress = Math.min(mem.lastProgress, now - Navigator.STUCK_TICKS - 1);
            mem.unbreakableSince = -1;
        }
    }
}

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

    public BotSkills(BotManager manager, SkillSettings settings) {
        this.manager = manager;
        this.settings = settings;
        this.navigator = new Navigator(this);
        this.pilot = new ElytraPilot(this);
        this.mace = new MaceSkill(this);
        this.pearls = new PearlSkill(this);
        this.bow = new BowSkill(this);
        this.tactics = new TacticalSkill(this, manager);
    }

    public SkillSettings settings() {
        return settings;
    }

    BotPathfinder pathfinder() {
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

    public void benchmarkForcePathRecovery(Terminator bot, Vec3 target, long now) {
        forget(bot);
        BotMemory mem=memory(bot);
        mem.progressPos=bot.getLocation();
        mem.progressDistance=bot.getLocation().distanceTo(target);
        mem.lastProgress=now-100;
        mem.nextRecovery=0;
    }

    public void forget(Terminator bot) {
        tactics.cleanup(bot, memories.get(bot));
        memories.remove(bot);
    }

    public void clear() {
        memories.forEach(tactics::cleanup);
        memories.clear();
        pathfinder.clear();
    }

    private long now() {
        return manager.getServer().getTickCount();
    }

    public Hardness hardness(Terminator bot) {
        return new Hardness(bot.getEntity() instanceof Bot b && b.getHardnessOverride() != 0
                ? b.getHardnessOverride() : settings.hardness);
    }

    public boolean enabled(Terminator bot, String name) {
        return Boolean.TRUE.equals(settings.get(name)) && hardness(bot).allows(name);
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
        return tactics.pursuitPoint(bot, memory(bot), target, fallback);
    }

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

        if (bot.isBotOnGround()) {
            mem.lastGround = bot.getLocation();
        }

        trackTarget(mem, target);

        // a new totem in the off hand as soon as one pops
        if (enabled(bot, "totems")) {
            bot.equipTotem();
        }

        if (tactics.tick(bot, mem, target, now)) return true;

        if (pearls.tryVoidRescue(bot, mem, target, now)) {
            return true;
        }

        if (mem.flight != BotMemory.Flight.NONE || bot.isGliding()) {
            pilot.tick(bot, mem, target, now);
            return true;
        }

        if (mem.maceDrop) {
            return mace.tickDrop(bot, mem, target, now);
        }

        if (mem.bowTicks > 0) {
            return bow.tick(bot, mem, target, now);
        }

        return false;
    }

    /**
     * Decides (a few times a second, on the ground) whether to start a mace dive, a pearl throw, a bow shot or a flight.
     */
    public boolean tryStart(Terminator bot, LivingEntity target) {
        if (!bot.isBotOnGround() || bot.isBotInWater() || !bot.tickDelay(hardness(bot).skillInterval())) return false;

        BotMemory mem = memory(bot);
        long now = now();

        if (mace.tryStart(bot, mem, target, now)) return true;
        if (pearls.tryGapClose(bot, mem, target, now)) return true;
        if (bow.tryStart(bot, mem, target, now)) return true;
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

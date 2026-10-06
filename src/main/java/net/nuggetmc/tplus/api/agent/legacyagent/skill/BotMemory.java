package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.GlobalPos;
import net.minecraft.world.level.block.state.BlockState;
import java.util.Map;
import java.util.LinkedHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Per-bot state of the skill layer. Times are server ticks.
 */
public class BotMemory {
    boolean gunEligible;
    List<BlockPos> searchPath;
    int searchPathIndex;
    long nextSearchPath;

    public enum Flight {
        NONE,
        TAKEOFF,
        CRUISE
    }

    public enum FlightPlan {
        /**
         * Fly towards the target and land next to it.
         */
        TRAVEL,
        /**
         * Climb above the target, stop gliding and smash down with a mace.
         */
        MACE,
        RECOVER,
        HUNT,
        FEINT,
        INTERCEPT
    }

    // ---- progress / stuck detection ----
    @Nullable
    Vec3 progressPos;
    double progressDistance = Double.MAX_VALUE;
    long lastProgress;
    int recoveryAttempts;
    long nextRecovery;
    long unbreakableSince = -1;

    // ---- path following (vanilla pathfinder detours) ----
    @Nullable
    List<BlockPos> path;
    int pathIndex;
    long pathDeadline;
    long nextPathSearch;

    // ---- pillaring up (over walls, out of pits) ----
    int towerGoalY = Integer.MIN_VALUE;
    @Nullable
    BlockPos towerBase;
    boolean towerPlaced;
    long towerDeadline;

    // ---- walking to a ladder ----
    @Nullable
    BlockPos ladder;
    long ladderDeadline;

    // ---- side step ----
    long jiggleUntil;
    @Nullable
    Vec3 jiggleDir;

    // ---- elytra ----
    Flight flight = Flight.NONE;
    FlightPlan plan = FlightPlan.TRAVEL;
    int flightTicks;
    long lastRocket;
    long nextTakeoff;
    long nextFlyingHit;
    long nextOffensiveFlight;
    long nextEscapeFlight;
    long groundFallbackUntil;
    public FlightPlan getFlightPlan() { return plan; }
    public long getNextOffensiveFlight() { return nextOffensiveFlight; }
    public long getGroundFallbackUntil() { return groundFallbackUntil; }

    // ---- mace ----
    boolean maceDrop;
    int maceDropTicks;
    long nextWindCharge;
    long nextMaceAttempt;
    int teamDiveTarget = -1;
    int teamContactTicks = -1;
    long teamImpactTick = -1;
    long teamDiveReleased = -1;
    long nextTeamDive;
    long teamDiveWaitingSince = -1;
    public long getTeamImpactTick() { return teamImpactTick; }
    public long getTeamDiveReleased() { return teamDiveReleased; }
    public int getTeamContactTicks() { return teamContactTicks; }

    // ---- ender pearls ----
    long nextPearl;

    // ---- bow ----
    int bowTicks;
    long nextArrow;

    // ---- the current target's movement (to lead shots) ----
    int trackedTarget = -1;
    @Nullable
    Vec3 lastTargetPos;
    Vec3 targetVelocity = Vec3.ZERO;
    @Nullable Vec3 lastSeen;
    long lastSeenTick = -1000;
    long recoverySearchUntil;
    public enum Window { NONE, EATING, SHIELD, ATTACK_COOLDOWN, EQUIPMENT_SWAP, TAKEOFF, LANDING }
    public enum Combo { NONE, AXE_SWORD, BOW_MACE, MACE_CRITICAL, PEARL_AMBUSH }
    Window window = Window.NONE;
    Combo combo = Combo.NONE;
    long comboUntil;
    int readTarget = -1;
    boolean wasAirborne;
    boolean wasGliding;
    String previousHand = "";
    String previousOffhand = "";
    int enemyTotems;
    int enemyApples;
    long nextEliteDecision;
    long nextSkillDecision;
    @Nullable Vec3 eliteWaypoint;
    long waypointUntil;
    long feintUntil;
    public enum Feint { NONE, RETREAT, CLIMB, DIVE, EXIT }
    Feint feint = Feint.NONE;
    long feintSince;
    long nextFeint;
    @Nullable Vec3 feintPoint;
    int feintTarget = -1;
    boolean comboConfirmed;
    long comboStarted;
    boolean allIn;
    int interceptedPearl = -1;
    @Nullable Vec3 pearlLanding;
    @Nullable Vec3 interceptPoint;
    long interceptUntil;
    long nextPearlRead;
    long enemyLandingUntil;
    long defendingUntil;
    long nextDefense;
    int defendingThreat = -1;
    public boolean isDefending() { return defendingUntil > 0; }
    public Feint getFeint() { return feint; }
    @Nullable public Vec3 getPearlLanding() { return pearlLanding; }
    @Nullable public Vec3 getInterceptPoint() { return interceptPoint; }
    public boolean isAllIn() { return allIn; }
    @Nullable java.util.UUID pendingOpponent;
    @Nullable OpponentLearning.Move pendingMove;
    long pendingUntil;
    boolean pendingHit;
    int speechTarget = -1;
    Window speechWindow = Window.NONE;
    Tactic speechTactic = Tactic.FIGHT;
    String speechTeam = "";
    boolean speechDive;
    long encounterSince;
    int combatHits;
    int combatSmashes;
    public Window getWindow() { return window; }
    public Combo getCombo() { return combo; }
    public int getEnemyTotems() { return enemyTotems; }

    public enum Tactic { FIGHT, RETREAT, RECOVER, HUNT, COVER_ALLY }
    Tactic tactic = Tactic.FIGHT;
    long tacticSince;
    long nextRetreat;
    long targetAcquired;
    long nextMelee;
    long lastDamage = -1000;
    long pressureSince;
    int pressureHits;
    @Nullable Vec3 threat;
    @Nullable Vec3 safePoint;
    @Nullable Vec3 flightGoal;
    @Nullable Vec3 protectedAlly;
    public enum TeamRole { NONE, GUARD, BAIT, FLANK, ARCHER }
    TeamRole teamRole = TeamRole.NONE;
    int guardedAlly = -1;
    int roleTarget = -1;
    @Nullable Vec3 rolePoint;
    long nextRolePoint;
    public TeamRole getTeamRole() { return teamRole; }
    public int getGuardedAlly() { return guardedAlly; }
    long nextCover;
    long coverExpires;
    long nextBuff;
    long nextChorus;
    final Map<GlobalPos, BlockState> cover = new LinkedHashMap<>();
    public Tactic getTactic() { return tactic; }

    @Nullable
    Vec3 lastGround;

    public Flight getFlight() {
        return flight;
    }

    public boolean isMaceDropping() {
        return maceDrop;
    }

    public boolean isDrawingBow() {
        return bowTicks > 0;
    }

    public boolean isFollowingPath() {
        return path != null;
    }

    public boolean isTowering() {
        return towerGoalY != Integer.MIN_VALUE;
    }

    void clearMovement() {
        path = null;
        towerGoalY = Integer.MIN_VALUE;
        towerBase = null;
        ladder = null;
        jiggleUntil = 0;
    }
}

package net.nuggetmc.tplus.api.agent.legacyagent.skill;

import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.List;

/**
 * Per-bot state of the skill layer. Times are server ticks.
 */
public class BotMemory {

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
        MACE
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

    // ---- mace ----
    boolean maceDrop;
    int maceDropTicks;
    long nextWindCharge;
    long nextMaceAttempt;

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

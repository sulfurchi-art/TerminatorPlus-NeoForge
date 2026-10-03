package net.nuggetmc.tplus.api.agent.botagent;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.nuggetmc.tplus.api.BotManager;
import net.nuggetmc.tplus.api.Terminator;
import net.nuggetmc.tplus.api.agent.Agent;
import net.nuggetmc.tplus.api.utils.MathUtils;
import net.nuggetmc.tplus.api.utils.PlayerUtils;

import java.util.Set;

/*
 * New bot agent!!!!!
 * this will replace legacyagent eventually
 * - basically this one will actually have A* pathfinding, whereas the legacy one only straightlines
 */

public class BotAgent extends Agent {

    private int count;

    public BotAgent(BotManager manager) {
        super(manager);
    }

    @Override
    protected void tick() {
        Set<Terminator> bots = manager.fetch();
        count = bots.size();
        bots.forEach(this::tickBot);
    }

    // This is where the code starts to get spicy
    private void tickBot(Terminator bot) {
        if (!bot.isBotAlive()) return;

        Vec3 loc = bot.getLocation();

        // if bot.hasHoldState() return; << This will be to check if a bot is mining or something similar where it can't move

        ServerPlayer player = nearestPlayer(bot, loc);
        if (player == null) return;

        Vec3 target = player.position();

        if (count > 1) target = target.add(bot.getOffset());

        // Make the XZ offsets stored in the bot object (so they don't form a straight line),
        // and make it so when mining and stuff, the offset is not taken into account

        // if checkVertical(bot) { break block action add; return; }

        BotSituation situation = new BotSituation(bot, target);

        // based on the situation, the bot can perform different actions
        // there can be priorities assigned

        // for building up, bot.setAction(BotAction.TOWER) or bot.startBuildingUp()

        VerticalDisplacement disp = situation.getVerticalDisplacement();

        // Later on maybe do bot.setAction(Action.MOVE) and what not instead of hardcoding it here

        move(bot, player, loc, target);

        if (bot.tickDelay(3)) attack(bot, player, loc);
    }

    private void attack(Terminator bot, ServerPlayer player, Vec3 loc) {
        if (PlayerUtils.isInvincible(player) || player.invulnerableTime >= 5 || loc.distanceTo(player.position()) >= 4)
            return;

        bot.attackTarget(player);
    }

    private void move(Terminator bot, ServerPlayer player, Vec3 loc, Vec3 target) {
        Vec3 vel = target.subtract(loc).normalize();

        if (bot.tickDelay(5)) bot.faceLocation(player.position());
        if (!bot.isBotOnGround()) return; // calling this a second time later on

        bot.stand(); // eventually create a memory system so packets do not have to be sent every tick
        bot.setItem(null); // method to check item in main hand, bot.getItemInHand()

        vel = vel.add(bot.getVelocity());

        if (MathUtils.isNotFinite(vel)) {
            vel = MathUtils.clean(vel);
        }

        if (vel.length() > 1) vel = vel.normalize();

        if (loc.distanceTo(target) <= 5) {
            vel = vel.scale(0.3);
        } else {
            vel = vel.scale(0.4);
        }

        vel = MathUtils.withY(vel, 0.4);

        bot.jump(vel);
    }

    private ServerPlayer nearestPlayer(Terminator bot, Vec3 loc) {
        ServerPlayer result = null;

        for (ServerPlayer player : manager.getServer().getPlayerList().getPlayers()) {
            if (player instanceof Terminator || PlayerUtils.isInvincible(player) || player.level() != bot.getBotLevel()) continue;

            if (result == null || loc.distanceTo(player.position()) < loc.distanceTo(result.position())) {
                result = player;
            }
        }

        return result;
    }
}

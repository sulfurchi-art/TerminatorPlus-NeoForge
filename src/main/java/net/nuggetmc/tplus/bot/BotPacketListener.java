package net.nuggetmc.tplus.bot;

import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;

import javax.annotation.Nullable;
import java.util.Set;

/**
 * Game packet listener of a bot. Nothing is ever sent to (or received from) a bot.
 */
public class BotPacketListener extends ServerGamePacketListenerImpl {

    private final Bot bot;

    public BotPacketListener(MinecraftServer server, Bot bot) {
        super(server, new BotConnection(), bot, CommonListenerCookie.createInitial(bot.getGameProfile(), false));
        this.bot = bot;
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener) {
    }

    @Override
    public void tick() {
    }

    /**
     * Kicking a bot (e.g. {@code /kick} on a bot in the player list) simply removes it.
     */
    @Override
    public void disconnect(DisconnectionDetails disconnectionDetails) {
        server.execute(bot::removeBot);
    }

    /**
     * Teleports (commands, ender pearls...) move the bot right away instead of waiting for a client acknowledgement.
     */
    @Override
    public void teleport(double x, double y, double z, float yaw, float pitch, Set<RelativeMovement> relativeSet) {
        super.teleport(x, y, z, yaw, pitch, relativeSet);
        bot.onTeleported();
    }
}

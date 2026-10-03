package net.nuggetmc.tplus.bot;

import io.netty.channel.Channel;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;

import javax.annotation.Nullable;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * A network connection that leads nowhere: bots have no client, so everything sent to them is dropped.
 * <p>
 * NeoForge looks up per-connection data through netty channel attributes (for example when datapacks are reloaded it
 * checks every player's negotiated payload channels), so this connection exposes a real but unconnected channel instead
 * of {@code null}.
 */
public class BotConnection extends Connection {

    private static final SocketAddress ADDRESS = new InetSocketAddress(InetAddress.getLoopbackAddress(), 0);

    private final Channel dummyChannel = new EmbeddedChannel();

    public BotConnection() {
        super(PacketFlow.SERVERBOUND);
    }

    @Override
    public Channel channel() {
        return dummyChannel;
    }

    @Override
    public boolean isConnected() {
        return true;
    }

    @Override
    public boolean isConnecting() {
        return false;
    }

    @Override
    public boolean isMemoryConnection() {
        return false;
    }

    @Override
    public SocketAddress getRemoteAddress() {
        return ADDRESS;
    }

    @Override
    public void send(Packet<?> packet) {
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener sendListener) {
    }

    @Override
    public void send(Packet<?> packet, @Nullable PacketSendListener listener, boolean flush) {
    }

    @Override
    public void flushChannel() {
    }

    @Override
    public void tick() {
    }

    @Override
    public void disconnect(Component message) {
    }

    @Override
    public void disconnect(DisconnectionDetails disconnectionDetails) {
    }

    @Override
    public void setReadOnly() {
    }

    @Override
    public void handleDisconnection() {
    }

    @Override
    public void setListenerForServerboundHandshake(PacketListener packetListener) {
    }

    @Override
    public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> protocolInfo, T packetListener) {
    }

    @Override
    public void setupOutboundProtocol(ProtocolInfo<?> protocolInfo) {
    }
}

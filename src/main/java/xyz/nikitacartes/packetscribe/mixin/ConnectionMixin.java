package xyz.nikitacartes.packetscribe.mixin;

import xyz.nikitacartes.packetscribe.utils.PacketCreationTracker;
import xyz.nikitacartes.packetscribe.PacketDumpService;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public class ConnectionMixin {
    @Inject(method = "channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void packetdump$onInbound(ChannelHandlerContext context, Packet<?> packet, CallbackInfo ci) {
        PacketDumpService service = PacketDumpService.getInstance();
        PacketCreationTracker.markIfAbsent(packet, null);
        service.onInbound((Connection)(Object)this, packet);
    }

    @Inject(
        method = "sendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD"),
        require = 0
    )
    private void packetdump$onOutboundSend(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        PacketCreationTracker.markIfAbsent(packet, packetdump$captureCurrentStackTrace());
    }

    @Inject(method = "doSendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
    private void packetdump$onOutbound(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        PacketDumpService service = PacketDumpService.getInstance();
        PacketCreationTracker.markIfAbsent(packet, null);
        service.onOutbound((Connection)(Object)this, packet, flush);
    }

    @Inject(
        method = "exceptionCaught(Lio/netty/channel/ChannelHandlerContext;Ljava/lang/Throwable;)V",
        at = @At("TAIL"),
        require = 0
    )
    private void packetdump$onException(ChannelHandlerContext context, Throwable throwable, CallbackInfo ci) {
        PacketDumpService.getInstance().dumpRecentOnException(throwable);
    }

    private static List<String> packetdump$captureCurrentStackTrace() {
        StackTraceElement[] fullTrace = Thread.currentThread().getStackTrace();
        List<String> result = new ArrayList<>(fullTrace.length);
        for (StackTraceElement element : fullTrace) {
            result.add(element.toString());
        }
        return result;
    }
}
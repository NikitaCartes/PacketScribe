package dev.nikitacartes.packetscribe.mixin.network;

import dev.nikitacartes.packetscribe.packetdump.PacketDumpService;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
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
		PacketDumpService.getInstance().onInbound((Connection)(Object)this, packet);
	}

	@Inject(method = "doSendPacket(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V", at = @At("HEAD"))
	private void packetdump$onOutbound(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
		PacketDumpService.getInstance().onOutbound((Connection)(Object)this, packet, flush);
	}
}
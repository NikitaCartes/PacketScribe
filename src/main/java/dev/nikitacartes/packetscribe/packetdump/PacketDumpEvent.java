package dev.nikitacartes.packetscribe.packetdump;

import java.util.List;

public record PacketDumpEvent(
	long epochMs,
	String timestampIso,
	String direction,
	String packetClass,
	String packetType,
	String packetFlow,
	String protocol,
	String listenerClass,
	PacketPlayerRef player,
	String connectionId,
	String remoteAddress,
	boolean localConnection,
	String threadName,
	Boolean flush,
	List<String> stackTrace
) {
}
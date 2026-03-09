package xyz.nikitacartes.packetscribe.packetdump;

import com.google.gson.JsonElement;
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
	JsonElement packetContent,
	List<String> stackTrace
) {
}
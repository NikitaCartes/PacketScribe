package dev.nikitacartes.packetscribe.packetdump;

import java.util.Locale;

public enum PacketDumpDirection {
	INBOUND("inbound"),
	OUTBOUND("outbound");

	private final String id;

	PacketDumpDirection(String id) {
		this.id = id;
	}

	public String id() {
		return this.id;
	}

	public static PacketDumpDirection fromString(String rawDirection) {
		String normalized = rawDirection == null ? "" : rawDirection.trim().toLowerCase(Locale.ROOT);
		return switch (normalized) {
			case "in", "inbound" -> INBOUND;
			case "out", "outbound" -> OUTBOUND;
			default -> throw new IllegalArgumentException("Unknown direction: " + rawDirection);
		};
	}
}
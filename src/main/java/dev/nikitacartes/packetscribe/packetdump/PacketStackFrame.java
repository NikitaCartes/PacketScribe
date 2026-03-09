package dev.nikitacartes.packetscribe.packetdump;

public record PacketStackFrame(
	String className,
	String methodName,
	String fileName,
	Integer lineNumber,
	boolean nativeMethod
) {
}

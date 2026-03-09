package xyz.nikitacartes.packetscribe.packetdump;

import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.network.protocol.Packet;

public final class PacketCreationTracker {
	private static final ReferenceQueue<Packet<?>> STALE_ENTRIES = new ReferenceQueue<>();
	private static final ConcurrentMap<IdentityWeakPacketRef, PacketCreationInfo> CREATION_INFO = new ConcurrentHashMap<>();

	private PacketCreationTracker() {
	}

	public static void markIfAbsent(Packet<?> packet, List<String> stackTrace) {
		if (packet == null) {
			return;
		}

		purgeCollectedEntries();
		List<String> traceToStore = stackTrace != null ? stackTrace : captureCurrentThreadStackTrace();
		PacketCreationInfo info = new PacketCreationInfo(List.copyOf(traceToStore));
		CREATION_INFO.putIfAbsent(new IdentityWeakPacketRef(packet, STALE_ENTRIES), info);
	}

	public static PacketCreationInfo get(Packet<?> packet) {
		if (packet == null) {
			return null;
		}

		purgeCollectedEntries();
		return CREATION_INFO.get(new IdentityWeakPacketRef(packet));
	}

	public static void clear() {
		CREATION_INFO.clear();
		while (STALE_ENTRIES.poll() != null) {
			// drain queued stale references
		}
	}

	private static List<String> captureCurrentThreadStackTrace() {
		StackTraceElement[] stackTrace = Thread.currentThread().getStackTrace();
		List<String> result = new ArrayList<>(stackTrace.length);
		for (StackTraceElement element : stackTrace) {
			result.add(element.toString());
		}
		return result;
	}

	private static void purgeCollectedEntries() {
		Reference<? extends Packet<?>> staleRef;
		while ((staleRef = STALE_ENTRIES.poll()) != null) {
			CREATION_INFO.remove(staleRef);
		}
	}

	public record PacketCreationInfo(List<String> stackTrace) {
	}

	private static final class IdentityWeakPacketRef extends WeakReference<Packet<?>> {
		private final int identityHash;

		private IdentityWeakPacketRef(Packet<?> packet, ReferenceQueue<Packet<?>> queue) {
			super(packet, queue);
			this.identityHash = System.identityHashCode(packet);
		}

		private IdentityWeakPacketRef(Packet<?> packet) {
			this(packet, null);
		}

		@Override
		public int hashCode() {
			return this.identityHash;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (!(obj instanceof IdentityWeakPacketRef other)) {
				return false;
			}

			Packet<?> thisPacket = this.get();
			Packet<?> otherPacket = other.get();
			return thisPacket != null && thisPacket == otherPacket;
		}
	}
}
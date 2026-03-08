package dev.nikitacartes.packetscribe.packetdump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingDeque;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.Connection;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PacketDumpService {
	private static final Logger LOGGER = LoggerFactory.getLogger("packetdump-service");
	private static final Gson EVENT_GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();
	private static final Gson PRETTY_GSON = new GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create();
	private static final PacketDumpService INSTANCE = new PacketDumpService();

	private final PacketDumpConfigManager configManager;
	private final Object configLock = new Object();
	private final Object recentLock = new Object();
	private final Deque<PacketDumpEvent> recentEvents = new ArrayDeque<>();
	private final AtomicBoolean started = new AtomicBoolean(false);
	private final AtomicBoolean running = new AtomicBoolean(false);
	private final AtomicBoolean shutdownHookInstalled = new AtomicBoolean(false);
	private final AtomicLong droppedEvents = new AtomicLong(0);

	private volatile PacketDumpConfig config;
	private volatile boolean commandDumpEnabled = false;
	private final BlockingDeque<PacketDumpEvent> writerQueue;
	private volatile Thread writerThread;

	private PacketDumpService() {
		Path configPath = FabricLoader.getInstance().getConfigDir().resolve("packetdump.json");
		this.configManager = new PacketDumpConfigManager(configPath);
		this.config = this.configManager.loadOrCreate();
		this.writerQueue = new LinkedBlockingDeque<>(this.config.writerQueueCapacity);
	}

	public static PacketDumpService getInstance() {
		return INSTANCE;
	}

	public void start() {
		if (this.started.compareAndSet(false, true)) {
			this.running.set(true);
			this.writerThread = new Thread(this::writerLoop, "packetdump-writer");
			this.writerThread.setDaemon(true);
			this.writerThread.start();

			if (this.shutdownHookInstalled.compareAndSet(false, true)) {
				Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "packetdump-shutdown"));
			}
		}
	}

	public void shutdown() {
		if (!this.running.getAndSet(false)) {
			return;
		}

		Thread activeWriter = this.writerThread;
		if (activeWriter != null) {
			activeWriter.interrupt();
			try {
				activeWriter.join(5000);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}

		this.drainQueueSynchronously();
	}

	public void reloadConfig() {
		synchronized (this.configLock) {
			this.config = this.configManager.loadOrCreate();
		}
	}

	public void onInbound(Connection connection, Packet<?> packet) {
		this.safeCapture(connection, packet, PacketDumpDirection.INBOUND, null);
	}

	public void onOutbound(Connection connection, Packet<?> packet, boolean flush) {
		this.safeCapture(connection, packet, PacketDumpDirection.OUTBOUND, flush);
	}

	public boolean isEffectivelyEnabled() {
		PacketDumpConfig cfg = this.config;
		return cfg.dumpAllByConfig || this.commandDumpEnabled;
	}

	public boolean isCommandDumpEnabled() {
		return this.commandDumpEnabled;
	}

	public void setCommandDumpEnabled(boolean enabled) {
		this.commandDumpEnabled = enabled;
	}

	public boolean toggleCommandDumpEnabled() {
		this.commandDumpEnabled = !this.commandDumpEnabled;
		return this.commandDumpEnabled;
	}

	public PacketDumpConfig getConfigSnapshot() {
		return this.config.copy();
	}

	public void setDirections(List<String> directions) {
		this.updateConfig(cfg -> cfg.directions = new ArrayList<>(directions));
	}

	public void addPacketFilter(String filter) {
		String normalized = normalizeToken(filter);
		if (normalized == null) {
			return;
		}

		this.updateConfig(cfg -> {
			if (!cfg.packetFilters.contains(normalized)) {
				cfg.packetFilters.add(normalized);
			}
		});
	}

	public boolean removePacketFilter(String filter) {
		String normalized = normalizeToken(filter);
		if (normalized == null) {
			return false;
		}

		AtomicBooleanHolder removed = new AtomicBooleanHolder();
		this.updateConfig(cfg -> removed.value = cfg.packetFilters.removeIf(existing -> existing.equals(normalized)));
		return removed.value;
	}

	public void clearPacketFilters() {
		this.updateConfig(cfg -> cfg.packetFilters.clear());
	}

	public void addPlayerFilter(String filter) {
		String normalized = normalizeToken(filter);
		if (normalized == null) {
			return;
		}

		this.updateConfig(cfg -> {
			if (!cfg.playerFilters.contains(normalized)) {
				cfg.playerFilters.add(normalized);
			}
		});
	}

	public boolean removePlayerFilter(String filter) {
		String normalized = normalizeToken(filter);
		if (normalized == null) {
			return false;
		}

		AtomicBooleanHolder removed = new AtomicBooleanHolder();
		this.updateConfig(cfg -> removed.value = cfg.playerFilters.removeIf(existing -> existing.equals(normalized)));
		return removed.value;
	}

	public void clearPlayerFilters() {
		this.updateConfig(cfg -> cfg.playerFilters.clear());
	}

	public void setRetentionMinutes(int retentionMinutes) {
		this.updateConfig(cfg -> cfg.retentionMinutes = retentionMinutes);
	}

	public void setStackTracesEnabled(boolean enabled) {
		this.updateConfig(cfg -> cfg.stackTraces = enabled);
	}

	public int getRecentCount() {
		synchronized (this.recentLock) {
			PacketDumpConfig cfg = this.config;
			this.pruneRecentLocked(System.currentTimeMillis(), cfg.retentionMinutes);
			return this.recentEvents.size();
		}
	}

	public long getDroppedEvents() {
		return this.droppedEvents.get();
	}

	public Path writeRecentSnapshot() throws IOException {
		List<PacketDumpEvent> snapshot;
		PacketDumpConfig cfg = this.config;
		synchronized (this.recentLock) {
			this.pruneRecentLocked(System.currentTimeMillis(), cfg.retentionMinutes);
			snapshot = List.copyOf(this.recentEvents);
		}

		Path outputPath = resolveOutputPath(cfg.outputFile);
		Path parent = outputPath.getParent();
		Path snapshotPath = parent != null ? parent.resolve("packetdump-recent.json") : Path.of("packetdump-recent.json");

		Path snapshotParent = snapshotPath.getParent();
		if (snapshotParent != null) {
			Files.createDirectories(snapshotParent);
		}

		Files.writeString(
			snapshotPath,
			PRETTY_GSON.toJson(snapshot),
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.TRUNCATE_EXISTING,
			StandardOpenOption.WRITE
		);

		return snapshotPath;
	}

	private void safeCapture(Connection connection, Packet<?> packet, PacketDumpDirection direction, Boolean flush) {
		try {
			this.capture(connection, packet, direction, flush);
		} catch (Throwable t) {
			LOGGER.warn("Packet capture failed for {}", packet.getClass().getName(), t);
		}
	}

	private void capture(Connection connection, Packet<?> packet, PacketDumpDirection direction, Boolean flush) {
		PacketDumpConfig cfg = this.config;
		if (!cfg.dumpAllByConfig && !this.commandDumpEnabled) {
			return;
		}

		if (!cfg.includeLocalConnections && connection.isMemoryConnection()) {
			return;
		}

		PacketDumpEvent event = this.buildEvent(connection, packet, direction, flush, cfg);
		if (!this.matchesFilters(event, cfg)) {
			return;
		}

		this.addRecentEvent(event, cfg);
		this.enqueueEvent(event, cfg);
	}

	private PacketDumpEvent buildEvent(Connection connection, Packet<?> packet, PacketDumpDirection direction, Boolean flush, PacketDumpConfig cfg) {
		long nowMs = System.currentTimeMillis();
		String timestampIso = Instant.ofEpochMilli(nowMs).toString();
		String packetClass = packet.getClass().getName();

		String packetType = null;
		String packetFlow = null;
		try {
			PacketType<?> type = packet.type();
			if (type != null) {
				if (type.id() != null) {
					packetType = type.id().toString();
				}
				if (type.flow() != null) {
					packetFlow = type.flow().id();
				}
			}
		} catch (Throwable ignored) {
		}

		PacketListener listener = connection.getPacketListener();
		String listenerClass = listener != null ? listener.getClass().getName() : null;
		String protocol = null;
		if (listener != null) {
			try {
				protocol = listener.protocol().id();
			} catch (Throwable ignored) {
			}
		}

		PacketPlayerRef playerRef = this.resolvePlayer(listener);
		String remoteAddress = connection.getRemoteAddress() == null ? null : connection.getRemoteAddress().toString();
		String connectionId = Integer.toHexString(System.identityHashCode(connection));
		List<String> stackTrace = cfg.stackTraces ? this.captureStackTrace(cfg.stackTraceDepth) : null;

		return new PacketDumpEvent(
			nowMs,
			timestampIso,
			direction.id(),
			packetClass,
			packetType,
			packetFlow,
			protocol,
			listenerClass,
			playerRef,
			connectionId,
			remoteAddress,
			connection.isMemoryConnection(),
			Thread.currentThread().getName(),
			flush,
			stackTrace
		);
	}

	private PacketPlayerRef resolvePlayer(PacketListener listener) {
		if (listener == null) {
			return null;
		}

		if (listener instanceof ServerGamePacketListenerImpl serverGameListener && serverGameListener.player != null) {
			return toPlayerRef(serverGameListener.player.getGameProfile());
		}

		if (listener instanceof ServerCommonPacketListenerImpl serverCommonListener) {
			return toPlayerRef(serverCommonListener.getOwner());
		}

		try {
			Method getter = listener.getClass().getMethod("getLocalGameProfile");
			Object profileObject = getter.invoke(listener);
			if (profileObject instanceof GameProfile gameProfile) {
				return toPlayerRef(gameProfile);
			}
		} catch (ReflectiveOperationException ignored) {
		}

		return null;
	}

	private static PacketPlayerRef toPlayerRef(GameProfile profile) {
		if (profile == null) {
			return null;
		}

		String uuid = profile.id() == null ? null : profile.id().toString();
		String name = profile.name();
		if ((uuid == null || uuid.isBlank()) && (name == null || name.isBlank())) {
			return null;
		}

		return new PacketPlayerRef(uuid, name);
	}

	private List<String> captureStackTrace(int maxDepth) {
		StackTraceElement[] fullTrace = Thread.currentThread().getStackTrace();
		List<String> result = new ArrayList<>(Math.min(maxDepth, fullTrace.length));

		for (int i = 3; i < fullTrace.length && result.size() < maxDepth; i++) {
			StackTraceElement element = fullTrace[i];
			String className = element.getClassName();
			if (className.startsWith(PacketDumpService.class.getName())) {
				continue;
			}
			result.add(element.toString());
		}

		return result;
	}

	private boolean matchesFilters(PacketDumpEvent event, PacketDumpConfig cfg) {
		String direction = event.direction().toLowerCase(Locale.ROOT);
		if (!cfg.directions.contains(direction)) {
			return false;
		}

		if (!cfg.packetFilters.isEmpty() && !matchesPacketFilter(cfg.packetFilters, event.packetClass(), event.packetType())) {
			return false;
		}

		if (!cfg.playerFilters.isEmpty() && !matchesPlayerFilter(cfg.playerFilters, event.player())) {
			return false;
		}

		return true;
	}

	private static boolean matchesPacketFilter(List<String> filters, String packetClass, String packetType) {
		String className = packetClass == null ? "" : packetClass.toLowerCase(Locale.ROOT);
		String simpleName;
		int lastDot = className.lastIndexOf('.');
		if (lastDot >= 0 && lastDot + 1 < className.length()) {
			simpleName = className.substring(lastDot + 1);
		} else {
			simpleName = className;
		}

		String packetTypeNormalized = packetType == null ? "" : packetType.toLowerCase(Locale.ROOT);

		for (String filter : filters) {
			if (filter.equals(className) || filter.equals(simpleName) || className.endsWith('.' + filter)) {
				return true;
			}

			if (!packetTypeNormalized.isEmpty()) {
				if (packetTypeNormalized.equals(filter)
					|| packetTypeNormalized.endsWith(':' + filter)
					|| packetTypeNormalized.endsWith('/' + filter)) {
					return true;
				}
			}
		}

		return false;
	}

	private static boolean matchesPlayerFilter(List<String> filters, PacketPlayerRef playerRef) {
		if (playerRef == null) {
			return false;
		}

		String playerUuid = playerRef.uuid() == null ? "" : playerRef.uuid().toLowerCase(Locale.ROOT);
		String playerName = playerRef.name() == null ? "" : playerRef.name().toLowerCase(Locale.ROOT);

		for (String filter : filters) {
			if (!playerUuid.isEmpty() && filter.equals(playerUuid)) {
				return true;
			}
			if (!playerName.isEmpty() && filter.equals(playerName)) {
				return true;
			}
		}

		return false;
	}

	private void addRecentEvent(PacketDumpEvent event, PacketDumpConfig cfg) {
		synchronized (this.recentLock) {
			this.recentEvents.addLast(event);
			this.pruneRecentLocked(event.epochMs(), cfg.retentionMinutes);
		}
	}

	private void pruneRecentLocked(long nowMs, int retentionMinutes) {
		long cutoff = nowMs - retentionMinutes * 60_000L;
		while (!this.recentEvents.isEmpty()) {
			PacketDumpEvent oldest = this.recentEvents.peekFirst();
			if (oldest == null || oldest.epochMs() >= cutoff) {
				break;
			}
			this.recentEvents.removeFirst();
		}
	}

	private void enqueueEvent(PacketDumpEvent event, PacketDumpConfig cfg) {
		if (this.writerQueue.offerLast(event)) {
			return;
		}

		if (cfg.dropOldestWhenQueueFull) {
			this.writerQueue.pollFirst();
			if (!this.writerQueue.offerLast(event)) {
				this.droppedEvents.incrementAndGet();
			}
		} else {
			this.droppedEvents.incrementAndGet();
		}
	}

	private void writerLoop() {
		while (this.running.get() || !this.writerQueue.isEmpty()) {
			try {
				PacketDumpEvent event = this.writerQueue.pollFirst(500, TimeUnit.MILLISECONDS);
				if (event != null) {
					this.appendEventToFile(event);
				}
			} catch (InterruptedException e) {
				if (!this.running.get()) {
					Thread.currentThread().interrupt();
					break;
				}
			} catch (Exception e) {
				LOGGER.error("Failed to write packet event", e);
			}
		}

		this.drainQueueSynchronously();
	}

	private void drainQueueSynchronously() {
		PacketDumpEvent event;
		while ((event = this.writerQueue.pollFirst()) != null) {
			try {
				this.appendEventToFile(event);
			} catch (Exception e) {
				LOGGER.error("Failed to flush packet event", e);
			}
		}
	}

	private void appendEventToFile(PacketDumpEvent event) throws IOException {
		Path outputPath = resolveOutputPath(this.config.outputFile);
		Path parent = outputPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		Files.writeString(
			outputPath,
			EVENT_GSON.toJson(event) + System.lineSeparator(),
			StandardCharsets.UTF_8,
			StandardOpenOption.CREATE,
			StandardOpenOption.WRITE,
			StandardOpenOption.APPEND
		);
	}

	private static Path resolveOutputPath(String outputFile) {
		try {
			Path raw = Path.of(outputFile);
			if (raw.isAbsolute()) {
				return raw.normalize();
			}
			return FabricLoader.getInstance().getGameDir().resolve(raw).normalize();
		} catch (Exception ignored) {
			return FabricLoader.getInstance().getGameDir().resolve("logs/packetdump.jsonl").normalize();
		}
	}

	private void updateConfig(Consumer<PacketDumpConfig> updater) {
		synchronized (this.configLock) {
			PacketDumpConfig updated = this.config.copy();
			updater.accept(updated);
			updated.normalize();
			this.config = updated;
			this.configManager.save(updated);
		}
	}

	private static String normalizeToken(String token) {
		if (token == null) {
			return null;
		}
		String normalized = token.trim().toLowerCase(Locale.ROOT);
		return normalized.isEmpty() ? null : normalized;
	}

	private static final class AtomicBooleanHolder {
		private boolean value;
	}
}
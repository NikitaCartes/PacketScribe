package xyz.nikitacartes.packetscribe.packetdump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.mojang.authlib.GameProfile;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
	private static final int PACKET_JSON_MAX_DEPTH = 5;
	private static final int PACKET_JSON_MAX_COLLECTION_ITEMS = 128;
	private static final int PACKET_JSON_MAX_FIELD_COUNT = 256;
	private static final int PACKET_JSON_MAX_STRING_LENGTH = 16*1024;
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
            packetType = type.id().toString();
            packetFlow = type.flow().id();
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
		String remoteAddress = connection.getRemoteAddress().toString();
		String connectionId = Integer.toHexString(System.identityHashCode(connection));
		JsonElement packetContent = this.resolvePacketContent(packet, cfg);
		PacketCreationTracker.PacketCreationInfo creationInfo = PacketCreationTracker.get(packet);
		List<String> stackTrace = creationInfo != null ? creationInfo.stackTrace() : null;

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
			packetContent,
			stackTrace
		);
	}

	private JsonElement resolvePacketContent(Packet<?> packet, PacketDumpConfig cfg) {
		if (!cfg.includePacketContent) {
			return null;
		}

		try {
			int maxStringLength = Math.min(cfg.packetContentMaxLength, PACKET_JSON_MAX_STRING_LENGTH);
			JsonElement content = this.toJsonElement(packet, 0, new IdentityHashMap<>(), maxStringLength);
			return this.truncateJsonElement(content, packet.getClass().getName(), cfg.packetContentMaxLength);
		} catch (Throwable t) {
			JsonObject fallback = new JsonObject();
			fallback.addProperty("_type", packet.getClass().getName());
			fallback.addProperty("_error", "packet-json-serialization-failed");
			fallback.addProperty("_exception", t.getClass().getSimpleName());
			if (t.getMessage() != null && !t.getMessage().isBlank()) {
				fallback.addProperty("_message", this.truncateString(t.getMessage(), 256));
			}
			return fallback;
		}
	}

	private JsonElement toJsonElement(Object value, int depth, IdentityHashMap<Object, Boolean> visited, int maxStringLength) {
        switch (value) {
            case null -> {
                return JsonNull.INSTANCE;
            }
            case JsonElement jsonElement -> {
                return jsonElement.deepCopy();
            }
            case Number number -> {
                return new JsonPrimitive(number);
            }
            case Boolean bool -> {
                return new JsonPrimitive(bool);
            }
            case Character character -> {
                return new JsonPrimitive(character);
            }
            case CharSequence text -> {
                return new JsonPrimitive(this.truncateString(text.toString(), maxStringLength));
            }
            case Enum<?> enumValue -> {
                return new JsonPrimitive(enumValue.name());
            }
            case Class<?> clazz -> {
                return new JsonPrimitive(clazz.getName());
            }
            default -> {
            }
        }

        Class<?> valueClass = value.getClass();
		if (depth >= PACKET_JSON_MAX_DEPTH) {
			JsonObject depthLimited = new JsonObject();
			depthLimited.addProperty("_type", valueClass.getName());
			depthLimited.addProperty("_depthLimited", true);
			return depthLimited;
		}

		if (visited.containsKey(value)) {
			JsonObject cycle = new JsonObject();
			cycle.addProperty("_type", valueClass.getName());
			cycle.addProperty("_cycleRef", true);
			return cycle;
		}

		visited.put(value, Boolean.TRUE);
		try {
			if (valueClass.isArray()) {
				return this.toJsonArray(value, depth, visited, maxStringLength);
			}

			if (value instanceof Iterable<?> iterable) {
				return this.toJsonArray(iterable, depth, visited, maxStringLength);
			}

			if (value instanceof Map<?, ?> map) {
				return this.toJsonObject(map, depth, visited, maxStringLength);
			}

			if (valueClass.getName().startsWith("java.")) {
				return new JsonPrimitive(this.truncateString(String.valueOf(value), maxStringLength));
			}

			return this.reflectObjectToJson(value, valueClass, depth, visited, maxStringLength);
		} finally {
			visited.remove(value);
		}
	}

	private JsonArray toJsonArray(Object arrayValue, int depth, IdentityHashMap<Object, Boolean> visited, int maxStringLength) {
		int length = Array.getLength(arrayValue);
		int limit = Math.min(length, PACKET_JSON_MAX_COLLECTION_ITEMS);
		JsonArray jsonArray = new JsonArray();
		for (int i = 0; i < limit; i++) {
			jsonArray.add(this.toJsonElement(Array.get(arrayValue, i), depth + 1, visited, maxStringLength));
		}

		if (length > limit) {
			JsonObject marker = new JsonObject();
			marker.addProperty("_truncatedItems", length - limit);
			jsonArray.add(marker);
		}

		return jsonArray;
	}

	private JsonArray toJsonArray(Iterable<?> iterable, int depth, IdentityHashMap<Object, Boolean> visited, int maxStringLength) {
		JsonArray jsonArray = new JsonArray();
		int count = 0;
		for (Object item : iterable) {
			if (count >= PACKET_JSON_MAX_COLLECTION_ITEMS) {
				JsonObject marker = new JsonObject();
				marker.addProperty("_truncatedItems", true);
				jsonArray.add(marker);
				break;
			}

			jsonArray.add(this.toJsonElement(item, depth + 1, visited, maxStringLength));
			count++;
		}

		return jsonArray;
	}

	private JsonObject toJsonObject(Map<?, ?> map, int depth, IdentityHashMap<Object, Boolean> visited, int maxStringLength) {
		JsonObject jsonObject = new JsonObject();
		int count = 0;
		for (Map.Entry<?, ?> entry : map.entrySet()) {
			if (count >= PACKET_JSON_MAX_COLLECTION_ITEMS) {
				jsonObject.addProperty("_truncatedEntries", map.size() - count);
				break;
			}

			String key = this.truncateString(String.valueOf(entry.getKey()), 128);
			jsonObject.add(key, this.toJsonElement(entry.getValue(), depth + 1, visited, maxStringLength));
			count++;
		}

		return jsonObject;
	}

	private JsonObject reflectObjectToJson(
		Object value,
		Class<?> valueClass,
		int depth,
		IdentityHashMap<Object, Boolean> visited,
		int maxStringLength
	) {
		JsonObject objectJson = new JsonObject();
		objectJson.addProperty("_type", valueClass.getName());

		List<Field> fields = this.collectSerializableFields(valueClass);
		int addedFields = 0;
		for (Field field : fields) {
			if (addedFields >= PACKET_JSON_MAX_FIELD_COUNT) {
				objectJson.addProperty("_truncatedFields", fields.size() - addedFields);
				break;
			}

			try {
				field.setAccessible(true);
				Object fieldValue = field.get(value);
				objectJson.add(field.getName(), this.toJsonElement(fieldValue, depth + 1, visited, maxStringLength));
			} catch (Throwable t) {
				objectJson.addProperty(field.getName(), "<inaccessible:" + t.getClass().getSimpleName() + ">");
			}
			addedFields++;
		}

		if (addedFields == 0) {
			objectJson.addProperty("_value", this.truncateString(String.valueOf(value), maxStringLength));
		}

		return objectJson;
	}

	private List<Field> collectSerializableFields(Class<?> valueClass) {
		List<Field> fields = new ArrayList<>();
		Class<?> current = valueClass;
		while (current != null && current != Object.class) {
			for (Field field : current.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || field.isSynthetic()) {
					continue;
				}
				fields.add(field);
			}
			current = current.getSuperclass();
		}
		return fields;
	}

	private JsonElement truncateJsonElement(JsonElement content, String packetClass, int maxLength) {
		String encoded = EVENT_GSON.toJson(content);
		if (encoded.length() <= maxLength) {
			return content;
		}

		JsonObject truncated = new JsonObject();
		truncated.addProperty("_type", packetClass);
		truncated.addProperty("_truncated", true);
		truncated.addProperty("_maxLength", maxLength);
		truncated.addProperty("_actualLength", encoded.length());
		truncated.addProperty("_preview", encoded.substring(0, maxLength));
		return truncated;
	}

	private String truncateString(String value, int maxLength) {
		if (value == null || value.length() <= maxLength) {
			return value;
		}

		int truncatedChars = value.length() - maxLength;
		return value.substring(0, maxLength) + "... [truncated +" + truncatedChars + " chars]";
	}

	private PacketPlayerRef resolvePlayer(PacketListener listener) {
        switch (listener) {
            case null -> {
                return null;
            }
            case ServerGamePacketListenerImpl serverGameListener -> {
                return toPlayerRef(serverGameListener.player.getGameProfile());
            }
            case ServerCommonPacketListenerImpl serverCommonListener -> {
                return toPlayerRef(serverCommonListener.getOwner());
            }
            default -> {
            }
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

	private boolean matchesFilters(PacketDumpEvent event, PacketDumpConfig cfg) {
		String direction = event.direction().toLowerCase(Locale.ROOT);
		if (!cfg.directions.contains(direction)) {
			return false;
		}

		if (!cfg.packetFilters.isEmpty() && !matchesPacketFilter(cfg.packetFilters, event.packetClass(), event.packetType())) {
			return false;
		}

        return cfg.playerFilters.isEmpty() || matchesPlayerFilter(cfg.playerFilters, event.player());
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
		List<PacketDumpEvent> batch = new ArrayList<>();
		long batchStartedAtMs = 0L;

		while (this.running.get() || !this.writerQueue.isEmpty()) {
			try {
				PacketDumpConfig cfg = this.config;
				int batchSize = cfg.writerBatchSize;
				int flushIntervalMs = cfg.writerFlushIntervalMs;

				PacketDumpEvent event = this.writerQueue.pollFirst(flushIntervalMs, TimeUnit.MILLISECONDS);
				long nowMs = System.currentTimeMillis();
				if (event != null) {
					if (batch.isEmpty()) {
						batchStartedAtMs = nowMs;
					}
					batch.add(event);
					if (batch.size() < batchSize) {
						this.writerQueue.drainTo(batch, batchSize - batch.size());
					}
				}

				if (!batch.isEmpty()) {
					boolean flushBySize = batch.size() >= batchSize;
					boolean flushByInterval = nowMs - batchStartedAtMs >= flushIntervalMs;
					boolean flushOnShutdown = !this.running.get() && this.writerQueue.isEmpty();
					if (flushBySize || flushByInterval || flushOnShutdown) {
						this.appendEventsToFile(batch);
						batch.clear();
						batchStartedAtMs = 0L;
					}
				}
			} catch (InterruptedException e) {
				if (!this.running.get()) {
					Thread.currentThread().interrupt();
					break;
				}
			} catch (Exception e) {
				LOGGER.error("Failed to write packet event batch", e);
				batch.clear();
				batchStartedAtMs = 0L;
			}
		}

		if (!batch.isEmpty()) {
			try {
				this.appendEventsToFile(batch);
			} catch (Exception e) {
				LOGGER.error("Failed to flush packet event batch", e);
			}
		}

		this.drainQueueSynchronously();
	}

	private void drainQueueSynchronously() {
		List<PacketDumpEvent> batch = new ArrayList<>();
		int batchSize = this.config.writerBatchSize;
		PacketDumpEvent event;
		while ((event = this.writerQueue.pollFirst()) != null) {
			batch.add(event);
			if (batch.size() >= batchSize) {
				this.flushBatchSynchronously(batch);
			}
		}

		if (!batch.isEmpty()) {
			this.flushBatchSynchronously(batch);
		}
	}

	private void flushBatchSynchronously(List<PacketDumpEvent> batch) {
		try {
			this.appendEventsToFile(batch);
		} catch (Exception e) {
			LOGGER.error("Failed to flush packet event batch", e);
		} finally {
			batch.clear();
		}
	}

	private void appendEventsToFile(List<PacketDumpEvent> events) throws IOException {
		if (events.isEmpty()) {
			return;
		}

		Path outputPath = resolveOutputPath(this.config.outputFile);
		Path parent = outputPath.getParent();
		if (parent != null) {
			Files.createDirectories(parent);
		}

		StringBuilder payload = new StringBuilder(events.size() * 256);
		for (PacketDumpEvent event : events) {
			payload.append(EVENT_GSON.toJson(event)).append(System.lineSeparator());
		}

		Files.writeString(
			outputPath,
			payload,
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
package dev.nikitacartes.packetscribe.packetdump;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PacketDumpConfig {
	public boolean dumpAllByConfig = false;
	public List<String> directions = new ArrayList<>(List.of(PacketDumpDirection.INBOUND.id(), PacketDumpDirection.OUTBOUND.id()));
	public List<String> packetFilters = new ArrayList<>();
	public List<String> playerFilters = new ArrayList<>();
	public boolean includeLocalConnections = false;
	public int retentionMinutes = 10;
	public boolean stackTraces = false;
	public int stackTraceDepth = 20;
	public String outputFile = "logs/packetdump.jsonl";
	public int writerQueueCapacity = 32768;
	public boolean dropOldestWhenQueueFull = true;

	public PacketDumpConfig copy() {
		PacketDumpConfig copy = new PacketDumpConfig();
		copy.dumpAllByConfig = this.dumpAllByConfig;
		copy.directions = new ArrayList<>(this.directions);
		copy.packetFilters = new ArrayList<>(this.packetFilters);
		copy.playerFilters = new ArrayList<>(this.playerFilters);
		copy.includeLocalConnections = this.includeLocalConnections;
		copy.retentionMinutes = this.retentionMinutes;
		copy.stackTraces = this.stackTraces;
		copy.stackTraceDepth = this.stackTraceDepth;
		copy.outputFile = this.outputFile;
		copy.writerQueueCapacity = this.writerQueueCapacity;
		copy.dropOldestWhenQueueFull = this.dropOldestWhenQueueFull;
		return copy;
	}

	public void normalize() {
		this.directions = normalizeLowercaseList(this.directions);
		if (this.directions.isEmpty()) {
			this.directions.add(PacketDumpDirection.INBOUND.id());
			this.directions.add(PacketDumpDirection.OUTBOUND.id());
		}

		this.packetFilters = normalizeLowercaseList(this.packetFilters);
		this.playerFilters = normalizeLowercaseList(this.playerFilters);

		if (this.retentionMinutes < 1) {
			this.retentionMinutes = 1;
		}

		if (this.stackTraceDepth < 1) {
			this.stackTraceDepth = 1;
		} else if (this.stackTraceDepth > 128) {
			this.stackTraceDepth = 128;
		}

		if (this.outputFile == null || this.outputFile.isBlank()) {
			this.outputFile = "logs/packetdump.jsonl";
		}

		if (this.writerQueueCapacity < 256) {
			this.writerQueueCapacity = 256;
		} else if (this.writerQueueCapacity > 262144) {
			this.writerQueueCapacity = 262144;
		}
	}

	private static List<String> normalizeLowercaseList(List<String> source) {
		Set<String> dedupe = new LinkedHashSet<>();
		if (source != null) {
			for (String value : source) {
				if (value == null) {
					continue;
				}
				String normalized = value.trim().toLowerCase(Locale.ROOT);
				if (!normalized.isEmpty()) {
					dedupe.add(normalized);
				}
			}
		}
		return new ArrayList<>(dedupe);
	}
}
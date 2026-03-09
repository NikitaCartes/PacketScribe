package xyz.nikitacartes.packetscribe.packetdump;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class PacketDumpConfig {
    public boolean dumpAllByConfig = false;
    public boolean fullDisable = false;
    public List<String> directions = new ArrayList<>(List.of(PacketDumpDirection.INBOUND.id(), PacketDumpDirection.OUTBOUND.id()));
    public List<String> packetFilters = new ArrayList<>();
    public List<String> playerFilters = new ArrayList<>();
    public boolean includeLocalConnections = false;
    public int retentionMinutes = 10;
    public boolean stackTraces = false;
    public boolean includePacketContent = false;
    public int packetContentMaxLength = 1024;
    public String outputFile = "logs/packetdump.jsonl";
    public int writerQueueCapacity = 32768;
    public int writerBatchSize = 128;
    public int writerFlushIntervalMs = 250;
    public boolean dropOldestWhenQueueFull = true;

    public PacketDumpConfig copy() {
        PacketDumpConfig copy = new PacketDumpConfig();
        copy.dumpAllByConfig = this.dumpAllByConfig;
        copy.fullDisable = this.fullDisable;
        copy.directions = new ArrayList<>(this.directions);
        copy.packetFilters = new ArrayList<>(this.packetFilters);
        copy.playerFilters = new ArrayList<>(this.playerFilters);
        copy.includeLocalConnections = this.includeLocalConnections;
        copy.retentionMinutes = this.retentionMinutes;
        copy.stackTraces = this.stackTraces;
        copy.includePacketContent = this.includePacketContent;
        copy.packetContentMaxLength = this.packetContentMaxLength;
        copy.outputFile = this.outputFile;
        copy.writerQueueCapacity = this.writerQueueCapacity;
        copy.writerBatchSize = this.writerBatchSize;
        copy.writerFlushIntervalMs = this.writerFlushIntervalMs;
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

        if (this.packetContentMaxLength < 64) {
            this.packetContentMaxLength = 64;
        } else if (this.packetContentMaxLength > 65536) {
            this.packetContentMaxLength = 65536;
        }

        if (this.outputFile == null || this.outputFile.isBlank()) {
            this.outputFile = "logs/packetdump.jsonl";
        }

        if (this.writerQueueCapacity < 256) {
            this.writerQueueCapacity = 256;
        } else if (this.writerQueueCapacity > 262144) {
            this.writerQueueCapacity = 262144;
        }

        if (this.writerBatchSize < 1) {
            this.writerBatchSize = 1;
        } else if (this.writerBatchSize > 8192) {
            this.writerBatchSize = 8192;
        }

        if (this.writerFlushIntervalMs < 10) {
            this.writerFlushIntervalMs = 10;
        } else if (this.writerFlushIntervalMs > 5000) {
            this.writerFlushIntervalMs = 5000;
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
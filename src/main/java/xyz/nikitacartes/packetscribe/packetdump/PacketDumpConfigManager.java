package xyz.nikitacartes.packetscribe.packetdump;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public record PacketDumpConfigManager(Path configPath) {
    private static final Logger LOGGER = LoggerFactory.getLogger("packetdump-config");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public PacketDumpConfig loadOrCreate() {
        try {
            if (Files.notExists(this.configPath)) {
                PacketDumpConfig defaultConfig = new PacketDumpConfig();
                defaultConfig.normalize();
                this.save(defaultConfig);
                return defaultConfig;
            }

            String content = Files.readString(this.configPath, StandardCharsets.UTF_8);
            PacketDumpConfig loaded = GSON.fromJson(content, PacketDumpConfig.class);
            if (loaded == null) {
                loaded = new PacketDumpConfig();
            }

            loaded.normalize();
            this.save(loaded);
            return loaded;
        } catch (Exception e) {
            LOGGER.error("Failed to load config from {}. Falling back to defaults.", this.configPath, e);
            PacketDumpConfig fallback = new PacketDumpConfig();
            fallback.normalize();
            return fallback;
        }
    }

    public void save(PacketDumpConfig config) {
        try {
            Path parent = this.configPath.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }

            Files.writeString(this.configPath, GSON.toJson(config), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        } catch (IOException e) {
            LOGGER.error("Failed to save config to {}", this.configPath, e);
        }
    }
}
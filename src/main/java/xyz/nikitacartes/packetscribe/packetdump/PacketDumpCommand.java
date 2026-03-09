package xyz.nikitacartes.packetscribe.packetdump;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import java.nio.file.Path;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

public final class PacketDumpCommand {
	private static final SimpleCommandExceptionType INVALID_DIRECTION = new SimpleCommandExceptionType(
		Component.literal("Direction must be inbound, outbound, or both")
	);

	private PacketDumpCommand() {
	}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		PacketDumpService service = PacketDumpService.getInstance();

		dispatcher.register(
			Commands.literal("packetdump")
				.requires(source -> source.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER))
				.executes(context -> sendStatus(context, service))
				.then(Commands.literal("on").executes(context -> setCommandEnabled(context, service, true)))
				.then(Commands.literal("off").executes(context -> setCommandEnabled(context, service, false)))
				.then(Commands.literal("toggle").executes(context -> toggleCommand(context, service)))
				.then(
					Commands.literal("fulldisable")
						.then(Commands.literal("on").executes(context -> setFullDisable(context, service, true)))
						.then(Commands.literal("off").executes(context -> setFullDisable(context, service, false)))
				)
				.then(Commands.literal("status").executes(context -> sendStatus(context, service)))
				.then(Commands.literal("reload").executes(context -> reloadConfig(context, service)))
				.then(Commands.literal("recent").executes(context -> writeRecentSnapshot(context, service)))
				.then(
					Commands.literal("direction")
						.then(
							Commands.argument("mode", StringArgumentType.word())
								.executes(context -> setDirection(context, service, StringArgumentType.getString(context, "mode")))
						)
				)
				.then(
					Commands.literal("packet")
						.then(
							Commands.literal("add")
								.then(
									Commands.argument("filter", StringArgumentType.word())
										.executes(context -> addPacketFilter(context, service, StringArgumentType.getString(context, "filter")))
								)
						)
						.then(
							Commands.literal("remove")
								.then(
									Commands.argument("filter", StringArgumentType.word())
										.executes(context -> removePacketFilter(context, service, StringArgumentType.getString(context, "filter")))
								)
						)
						.then(Commands.literal("clear").executes(context -> clearPacketFilters(context, service)))
				)
				.then(
					Commands.literal("player")
						.then(
							Commands.literal("add")
								.then(
									Commands.argument("filter", StringArgumentType.word())
										.executes(context -> addPlayerFilter(context, service, StringArgumentType.getString(context, "filter")))
								)
						)
						.then(
							Commands.literal("remove")
								.then(
									Commands.argument("filter", StringArgumentType.word())
										.executes(context -> removePlayerFilter(context, service, StringArgumentType.getString(context, "filter")))
								)
						)
						.then(Commands.literal("clear").executes(context -> clearPlayerFilters(context, service)))
				)
				.then(
					Commands.literal("retention")
						.then(
							Commands.argument("minutes", IntegerArgumentType.integer(1, 60 * 24 * 30))
								.executes(context -> setRetention(context, service, IntegerArgumentType.getInteger(context, "minutes")))
						)
				)
				.then(
					Commands.literal("stacktrace")
						.then(Commands.literal("on").executes(context -> setStackTrace(context, service, true)))
						.then(Commands.literal("off").executes(context -> setStackTrace(context, service, false)))
				)
		);
	}

	private static int setCommandEnabled(CommandContext<CommandSourceStack> context, PacketDumpService service, boolean enabled) {
		service.setCommandDumpEnabled(enabled);
		if (enabled && service.isFullyDisabled()) {
			send(context, "Command dump ignored because fullDisable=true");
			return Command.SINGLE_SUCCESS;
		}
		send(context, "Command dump set to " + enabled);
		return Command.SINGLE_SUCCESS;
	}

	private static int toggleCommand(CommandContext<CommandSourceStack> context, PacketDumpService service) {
		boolean enabled = service.toggleCommandDumpEnabled();
		if (!enabled && service.isFullyDisabled()) {
			send(context, "Command dump cannot be enabled while fullDisable=true");
			return Command.SINGLE_SUCCESS;
		}
		send(context, "Command dump toggled to " + enabled);
		return Command.SINGLE_SUCCESS;
	}

	private static int sendStatus(CommandContext<CommandSourceStack> context, PacketDumpService service) {
		PacketDumpConfig cfg = service.getConfigSnapshot();
		send(
			context,
			"active="
				+ service.isEffectivelyEnabled()
				+ " (fullDisable="
				+ cfg.fullDisable
				+ ", config="
				+ cfg.dumpAllByConfig
				+ ", command="
				+ service.isCommandDumpEnabled()
				+ ")"
		);
		send(
			context,
			"directions="
				+ String.join(",", cfg.directions)
				+ ", packetFilters="
				+ cfg.packetFilters.size()
				+ ", playerFilters="
				+ cfg.playerFilters.size()
		);
		send(
			context,
			"retentionMinutes="
				+ cfg.retentionMinutes
				+ ", stackTraces="
				+ cfg.stackTraces
				+ ", includePacketContent="
				+ cfg.includePacketContent
				+ ", packetContentMaxLength="
				+ cfg.packetContentMaxLength
				+ ", writerBatchSize="
				+ cfg.writerBatchSize
				+ ", writerFlushIntervalMs="
				+ cfg.writerFlushIntervalMs
				+ ", recent="
				+ service.getRecentCount()
				+ ", dropped="
				+ service.getDroppedEvents()
		);
		send(context, "outputFile=" + cfg.outputFile);
		return Command.SINGLE_SUCCESS;
	}

	private static int reloadConfig(CommandContext<CommandSourceStack> context, PacketDumpService service) {
		service.reloadConfig();
		send(context, "Configuration reloaded from disk");
		return Command.SINGLE_SUCCESS;
	}

	private static int writeRecentSnapshot(CommandContext<CommandSourceStack> context, PacketDumpService service) {
		try {
			Path path = service.writeRecentSnapshot();
			send(context, "Recent snapshot written to " + path);
			return Command.SINGLE_SUCCESS;
		} catch (Exception e) {
			throw new RuntimeException("Failed to write recent snapshot", e);
		}
	}

	private static int setDirection(CommandContext<CommandSourceStack> context, PacketDumpService service, String mode) throws CommandSyntaxException {
		List<String> directions = parseDirections(mode);
		service.setDirections(directions);
		send(context, "Directions updated to " + String.join(",", directions));
		return Command.SINGLE_SUCCESS;
	}

	private static List<String> parseDirections(String mode) throws CommandSyntaxException {
		String normalized = mode == null ? "" : mode.trim().toLowerCase();
		return switch (normalized) {
			case "in", "inbound" -> List.of(PacketDumpDirection.INBOUND.id());
			case "out", "outbound" -> List.of(PacketDumpDirection.OUTBOUND.id());
			case "both", "all" -> List.of(PacketDumpDirection.INBOUND.id(), PacketDumpDirection.OUTBOUND.id());
			default -> throw INVALID_DIRECTION.create();
		};
	}

	private static int addPacketFilter(CommandContext<CommandSourceStack> context, PacketDumpService service, String filter) {
		service.addPacketFilter(filter);
		send(context, "Packet filter added: " + filter);
		return Command.SINGLE_SUCCESS;
	}

	private static int removePacketFilter(CommandContext<CommandSourceStack> context, PacketDumpService service, String filter) {
		boolean removed = service.removePacketFilter(filter);
		send(context, removed ? "Packet filter removed: " + filter : "Packet filter not found: " + filter);
		return Command.SINGLE_SUCCESS;
	}

	private static int clearPacketFilters(CommandContext<CommandSourceStack> context, PacketDumpService service) {
		service.clearPacketFilters();
		send(context, "Packet filters cleared");
		return Command.SINGLE_SUCCESS;
	}

	private static int addPlayerFilter(CommandContext<CommandSourceStack> context, PacketDumpService service, String filter) {
		service.addPlayerFilter(filter);
		send(context, "Player filter added: " + filter);
		return Command.SINGLE_SUCCESS;
	}

	private static int removePlayerFilter(CommandContext<CommandSourceStack> context, PacketDumpService service, String filter) {
		boolean removed = service.removePlayerFilter(filter);
		send(context, removed ? "Player filter removed: " + filter : "Player filter not found: " + filter);
		return Command.SINGLE_SUCCESS;
	}

	private static int clearPlayerFilters(CommandContext<CommandSourceStack> context, PacketDumpService service) {
		service.clearPlayerFilters();
		send(context, "Player filters cleared");
		return Command.SINGLE_SUCCESS;
	}

	private static int setRetention(CommandContext<CommandSourceStack> context, PacketDumpService service, int minutes) {
		service.setRetentionMinutes(minutes);
		send(context, "Retention set to " + minutes + " minute(s)");
		return Command.SINGLE_SUCCESS;
	}

	private static int setFullDisable(CommandContext<CommandSourceStack> context, PacketDumpService service, boolean enabled) {
		service.setFullDisable(enabled);
		if (enabled) {
			send(context, "Full disable set to true; packet memory buffers were cleared");
		} else {
			send(context, "Full disable set to false");
		}
		return Command.SINGLE_SUCCESS;
	}

	private static int setStackTrace(CommandContext<CommandSourceStack> context, PacketDumpService service, boolean enabled) {
		service.setStackTracesEnabled(enabled);
		send(context, "Stack traces set to " + enabled);
		return Command.SINGLE_SUCCESS;
	}

	private static void send(CommandContext<CommandSourceStack> context, String message) {
		context.getSource().sendSuccess(() -> Component.literal("[packetdump] " + message), false);
	}
}
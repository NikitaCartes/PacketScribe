# PacketScribe (Fabric)

This mod intercepts and dumps incoming/outgoing Minecraft packets to JSON.

Author: `NikitaCartes`

## Features

- Dump all packets via config (`dumpAllByConfig`)
- Dump via command (`/packetdump on|off|toggle`)
- Two dump modes:
	- `memory` — keep packets in memory and dump by command only
	- `auto` — automatically dump all tracked packets to file
- Optional exception-triggered recent dump (`dumpRecentOnException`)
- Filter by specific packets
- Filter by specific player (name or UUID)
- Filter by direction (`inbound`/`outbound`)
- Keep the last `n` minutes in memory
- Optional stack traces for each event
- Save events to a `jsonl` file

## Config location

The file is created automatically on first launch:

- `<gameDir>/config/packetdump.json`

Main fields:

- `dumpAllByConfig`
- `autoDumpToFile`
- `dumpRecentOnException`
- `directions`
- `packetFilters`
- `playerFilters`
- `retentionMinutes`
- `stackTraces`
- `stackTraceDepth`
- `includePacketContent`
- `packetContentMaxLength`
- `outputFile`

## Command

Base command: `/packetdump`

Subcommands:

- `on|off|toggle|status`
- `mode <memory|auto>`
- `exceptiondump <on|off>`
- `reload`
- `recent` — write a snapshot of the last `n` minutes to `packetdump-recent-<timestamp>.json`
	- The snapshot file includes metadata fields like `dumpEpochMs` and `dumpTimestampIso`
- `direction <inbound|outbound|both>`
- `packet add/remove/clear <filter>`
- `player add/remove/clear <name-or-uuid>`
- `retention <minutes>`
- `stacktrace <on|off>`

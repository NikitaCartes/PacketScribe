# PacketScribe (Fabric)

This mod intercepts and dumps incoming/outgoing Minecraft packets to JSON.

Author: `NikitaCartes`

## Features

- Dump all packets via config (`dumpAllByConfig`)
- Dump via command (`/packetdump on|off|toggle`)
- Fully disable tracking (`fullDisable`) so packets do not accumulate in memory
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
- `fullDisable`
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
- `fulldisable on|off`
- `reload`
- `recent` — write a snapshot of the last `n` minutes to `packetdump-recent.json`
- `direction <inbound|outbound|both>`
- `packet add/remove/clear <filter>`
- `player add/remove/clear <name-or-uuid>`
- `retention <minutes>`
- `stacktrace <on|off>`

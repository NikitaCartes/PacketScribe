# PacketScribe (Fabric)

Мод перехватывает и дампит входящие/исходящие Minecraft-пакеты в JSON.

Автор: `NikitaCartes`

## Возможности

- Дамп всех пакетов по конфигу (`dumpAllByConfig`)
- Дамп по команде (`/packetdump on|off|toggle`)
- Фильтрация по конкретным пакетам
- Фильтрация по конкретному игроку (имя или UUID)
- Фильтрация по направлению (`inbound`/`outbound`)
- Хранение последних `n` минут в памяти
- Опциональные stack trace для каждого события
- Сохранение событий в файл `jsonl`

## Где лежит конфиг

Файл создаётся автоматически при первом запуске:

- `<gameDir>/config/packetdump.json`

Основные поля:

- `dumpAllByConfig`
- `directions`
- `packetFilters`
- `playerFilters`
- `retentionMinutes`
- `stackTraces`
- `stackTraceDepth`
- `includePacketContent`
- `packetContentMaxLength`
- `outputFile`

## Команда

Базовая команда: `/packetdump`

Подкоманды:

- `on|off|toggle|status`
- `reload`
- `recent` — записать snapshot последних `n` минут в `packetdump-recent.json`
- `direction <inbound|outbound|both>`
- `packet add/remove/clear <filter>`
- `player add/remove/clear <name-or-uuid>`
- `retention <minutes>`
- `stacktrace <on|off>`

## License

Licensed under CC0-1.0.

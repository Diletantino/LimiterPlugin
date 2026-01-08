# BanThings (Paper 1.21.x)

Плагин для Paper, который:
- `/banitem <minecraft:item_id>` — банит предмет (удаляется при попадании в инвентарь)
- `/limititem <minecraft:item_id> <limit>` — лимитирует предмет (лишнее испаряется)
- `/baneffect <minecraft:effect_id>` — банит эффект (при получении очищает все эффекты)

Дополнительно:
- `/banlist` — список забаненных предметов
- `/limitlist` — список лимитов
- `/effectlist` — список забаненных эффектов

## Сборка

Требуется Java 21 + Maven.

```bash
mvn clean package
```

Jar появится в:

`target/banthings-1.1.0.jar`

## Установка на сервер

1. Останови сервер.
2. Положи jar в папку `plugins/`.
3. Запусти сервер.
4. Настрой `plugins/BanThings/config.yml` при желании.

## Настройка сообщений

`config.yml`:
- `notify-mode: OFF|CHAT|ACTIONBAR|BOTH`
- placeholders: `%item%`, `%amount%`, `%limit%`, `%effect%`

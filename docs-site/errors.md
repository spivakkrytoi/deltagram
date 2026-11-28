---
title: Ошибки и лимиты
description: Формат ошибок, коды и повторные запросы
---

# Ошибки и лимиты

Ответ при ошибке:

```json
{
  "ok": false,
  "error_code": 400,
  "description": "Bad Request: chat not found"
}
```

## Коды

| Код | Значение |
| --- | --- |
| `400` | Некорректный запрос или параметр |
| `401` | Неверный токен |
| `403` | Бот заблокирован или нет прав |
| `404` | Метод или файл не найден |
| `409` | Конфликт состояния |
| `429` | Слишком много запросов, смотрите `retry_after` |
| `500` | Внутренняя ошибка DeltaGram |

## Частые описания

- `Bad Request: chat not found` — неверный `chat_id`.
- `Bad Request: message is not modified` — новый текст совпадает со старым.
- `Bad Request: message to edit not found` — сообщение удалено.
- `Forbidden: bot was blocked by the user` — пользователь заблокировал бота.
- `Forbidden: bot is not a member of the chat` — добавьте бота в чат.
- `Unauthorized` — проверьте токен.

## Flood control

При превышении лимита:

```json
{
  "ok": false,
  "error_code": 429,
  "description": "Too Many Requests: retry after 5",
  "parameters": { "retry_after": 5 }
}
```

Подождите `retry_after` секунд и повторите запрос. Не повторяйте запрос
немедленно — это увеличит задержку.

## Рекомендации

- Используйте `getUpdates` с `timeout=25`, а не частые короткие запросы.
- Обрабатывайте `429` с задержкой.
- Не отправляйте больше 30 сообщений в секунду в один чат.
- Для массовых рассылок используйте паузы и `disable_notification`.
- Храните токен в секрете; при утечке выполните `/revoke` у @BotFather.

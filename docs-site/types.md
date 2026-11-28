---
title: Типы
description: Основные объекты DeltaGram Bot API
---

# Типы

Типы совместимы с Telegram Bot API. Ниже — основные объекты, которые
возвращает DeltaGram.

## Update

```json
{
  "update_id": 123456789,
  "message": { "message_id": 1, "date": 1790000000, "chat": {}, "from": {}, "text": "Hi" }
}
```

Поля: `update_id`, `message`, `edited_message`, `callback_query`,
`inline_query`, `chosen_inline_result`, `shipping_query`,
`pre_checkout_query`.

## User

```json
{
  "id": 1780243200,
  "is_bot": false,
  "first_name": "Иван",
  "last_name": "Петров",
  "username": "ivan",
  "language_code": "ru",
  "is_premium": true
}
```

## Chat

```json
{
  "id": -1001234567890,
  "type": "supergroup",
  "title": "DeltaGram Chat",
  "username": "dg_chat",
  "description": "Описание"
}
```

Типы: `private`, `group`, `supergroup`, `channel`.

## Message

```json
{
  "message_id": 42,
  "date": 1790000000,
  "chat": { "id": 123, "type": "private" },
  "from": { "id": 123, "is_bot": false, "first_name": "Иван" },
  "text": "Привет",
  "entities": []
}
```

Поля медиа: `photo`, `document`, `video`, `animation`, `audio`, `voice`,
`video_note`, `sticker`, `location`, `contact`, `venue`, `caption`,
`caption_entities`, `reply_to_message`, `reply_markup`.

## MessageEntity

```json
{ "type": "bold", "offset": 0, "length": 5 }
```

Типы: `mention`, `hashtag`, `cashtag`, `bot_command`, `url`, `email`,
`bold`, `italic`, `underline`, `strikethrough`, `spoiler`, `code`,
`pre`, `text_link`, `text_mention`, `custom_emoji`, `blockquote`.

## InlineKeyboardMarkup

```json
{
  "inline_keyboard": [
    [
      { "text": "Купить", "callback_data": "buy", "style": "success" },
      { "text": "Отмена", "callback_data": "cancel", "style": "danger" }
    ],
    [
      { "text": "Открыть", "web_app": { "url": "https://example.com/app" }, "style": "primary" }
    ]
  ]
}
```

## InlineKeyboardButton

| Поле | Описание |
| --- | --- |
| `text` | Текст кнопки |
| `url` | Ссылка |
| `callback_data` | Данные callback (1–64 байта) |
| `web_app` | Mini App |
| `login_url` | Telegram Login |
| `switch_inline_query` | Inline-режим |
| `switch_inline_query_current_chat` | Inline в текущем чате |
| `copy_text` | Копировать текст |
| `style` | `primary`, `success`, `danger` |

## ReplyKeyboardMarkup

```json
{
  "keyboard": [[{ "text": "Кнопка" }]],
  "resize_keyboard": true,
  "one_time_keyboard": false,
  "is_persistent": false
}
```

## File

```json
{
  "file_id": "AgAC...",
  "file_unique_id": "AQAD...",
  "file_size": 12345,
  "file_path": "documents/file_1.jpg"
}
```

## WebhookInfo

```json
{
  "url": "https://example.com/hook",
  "has_custom_certificate": false,
  "pending_update_count": 0,
  "last_error_date": 0,
  "last_error_message": "",
  "max_connections": 40,
  "allowed_updates": []
}
```

## CallbackQuery

```json
{
  "id": "4382...",
  "from": { "id": 123, "is_bot": false, "first_name": "Иван" },
  "message": {},
  "chat_instance": "123",
  "data": "buy"
}
```

## BotCommand

```json
{ "command": "start", "description": "Запуск" }
```

## MenuButton

```json
{
  "type": "web_app",
  "text": "Открыть",
  "web_app": { "url": "https://example.com/app" }
}
```

Типы: `default`, `commands`, `web_app`.

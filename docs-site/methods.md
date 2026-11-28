---
title: Методы
description: Полный справочник методов DeltaGram Bot API
---

# Методы

Все методы вызываются так:

```text
https://dltagram.duckdns.org/botapi/bot<TOKEN>/<method>
```

Ниже перечислены все методы, реализованные в DeltaGram Bot API. Параметры
совпадают с Telegram Bot API там, где это применимо.

## Получение обновлений

### getMe

Возвращает информацию о боте.

```bash
curl "https://dltagram.duckdns.org/botapi/bot$TOKEN/getMe"
```

### getUpdates

Long polling обновлений.

| Поле | Тип | Обязательно | Описание |
| --- | --- | --- | --- |
| `offset` | integer | нет | ID первого обновления |
| `limit` | integer | нет | 1–100 |
| `timeout` | integer | нет | Таймаут long polling |
| `allowed_updates` | string[] | нет | Типы обновлений |

```bash
curl "https://dltagram.duckdns.org/botapi/bot$TOKEN/getUpdates?offset=0&timeout=25"
```

### setWebhook

```json
{
  "url": "https://example.com/hook",
  "secret_token": "my-secret",
  "drop_pending_updates": false,
  "allowed_updates": ["message", "callback_query"]
}
```

### deleteWebhook

```json
{ "drop_pending_updates": false }
```

### getWebhookInfo

```bash
curl "https://dltagram.duckdns.org/botapi/bot$TOKEN/getWebhookInfo"
```

## Сообщения

### sendMessage

| Поле | Тип | Обязательно | Описание |
| --- | --- | --- | --- |
| `chat_id` | integer/string | да | ID чата или `@username` |
| `text` | string | да | 1–4096 символов |
| `parse_mode` | string | нет | `HTML`, `Markdown`, `MarkdownV2` |
| `entities` | array | нет | Форматирование |
| `disable_web_page_preview` | bool | нет | Отключить превью |
| `disable_notification` | bool | нет | Без звука |
| `reply_to_message_id` | integer | нет | Ответ на сообщение |
| `reply_markup` | object | нет | Клавиатура |

```bash
curl -X POST "https://dltagram.duckdns.org/botapi/bot$TOKEN/sendMessage" \
  -H 'Content-Type: application/json' \
  -d '{"chat_id":123,"text":"Привет, *DeltaGram*","parse_mode":"MarkdownV2"}'
```

### sendRichMessage

Отправляет rich-сообщение (заголовки, списки, цитаты, медиа). Тело содержит
`chat_id`, `rich_message` и стандартные `reply_markup`, `silent`,
`reply_to_message_id`.

### editMessageText

```json
{
  "chat_id": 123,
  "message_id": 456,
  "text": "Новый текст",
  "parse_mode": "HTML",
  "reply_markup": { "inline_keyboard": [] }
}
```

### deleteMessage

```json
{ "chat_id": 123, "message_id": 456 }
```

## Медиа

Все методы медиа принимают `chat_id`, файл (`photo`, `document`, `video`,
`animation`, `audio`, `voice`, `video_note`, `sticker`), `caption`,
`parse_mode`, `reply_markup`, `disable_notification`, `reply_to_message_id`.

| Метод | Файл |
| --- | --- |
| `sendPhoto` | `photo` |
| `sendAnimation` | `animation` |
| `sendAudio` | `audio` |
| `sendDocument` | `document` |
| `sendLivePhoto` | `live_photo` |
| `sendSticker` | `sticker` |
| `sendVideo` | `video` |
| `sendVideoNote` | `video_note` |
| `sendVoice` | `voice` |

```bash
curl -X POST "https://dltagram.duckdns.org/botapi/bot$TOKEN/sendPhoto" \
  -F 'chat_id=123' -F 'caption=Подпись' -F 'photo=@photo.jpg'
```

Файл можно передать как `file_id`, URL или `multipart/form-data`.

## Контакты и геолокация

### sendContact

```json
{
  "chat_id": 123,
  "phone_number": "+15551234567",
  "first_name": "Иван",
  "last_name": "Петров"
}
```

### sendLocation

```json
{ "chat_id": 123, "latitude": 50.45, "longitude": 30.52 }
```

### sendVenue

```json
{
  "chat_id": 123,
  "latitude": 50.45,
  "longitude": 30.52,
  "title": "Офис",
  "address": "Киев, Крещатик 1"
}
```

## Callback и файлы

### answerCallbackQuery

```json
{
  "callback_query_id": "123456",
  "text": "Готово",
  "show_alert": false,
  "url": "",
  "cache_time": 0
}
```

### getFile

```bash
curl "https://dltagram.duckdns.org/botapi/bot$TOKEN/getFile?file_id=AgAC..."
```

Затем скачайте:

```text
https://dltagram.duckdns.org/botapi/file/bot<TOKEN>/<file_path>
```

## Команды и меню

### setMyCommands

```json
{
  "commands": [
    { "command": "start", "description": "Запуск" },
    { "command": "help", "description": "Помощь" }
  ],
  "scope": { "type": "default" }
}
```

### getMyCommands

```bash
curl "https://dltagram.duckdns.org/botapi/bot$TOKEN/getMyCommands"
```

### deleteMyCommands

```json
{ "scope": { "type": "default" } }
```

### setChatMenuButton

```json
{
  "chat_id": 123,
  "menu_button": {
    "type": "web_app",
    "text": "Открыть",
    "web_app": { "url": "https://example.com/app" }
  }
}
```

### getChatMenuButton

```json
{ "chat_id": 123 }
```

## Mini Apps и inline

### answerWebAppQuery

```json
{
  "web_app_query_id": "query-id",
  "result": {
    "type": "article",
    "id": "1",
    "title": "Результат",
    "input_message_content": { "message_text": "Текст" }
  }
}
```

### savePreparedInlineMessage

Сохраняет подготовленное inline-сообщение и возвращает `inline_message_id`.

## Специальные методы

### setUserEmojiStatus

```json
{
  "user_id": 123,
  "emoji_status_custom_emoji_id": "5368324170671202286"
}
```

### giftPremiumSubscription

```json
{
  "user_id": 123,
  "month_count": 3,
  "star_count": 1000,
  "text": "Спасибо!"
}
```

### answerShippingQuery / answerPreCheckoutQuery

```json
{ "shipping_query_id": "id", "ok": true }
```

```json
{ "pre_checkout_query_id": "id", "ok": true }
```

## Ephemeral-сообщения

- `editEphemeralMessageText`
- `editEphemeralMessageMedia`
- `editEphemeralMessageCaption`
- `editEphemeralMessageReplyMarkup`
- `deleteEphemeralMessage`

Параметры: `chat_id`, `receiver_user_id`, `message_id`, новый текст/медиа/
caption/markup.

```json
{
  "chat_id": 123,
  "receiver_user_id": 456,
  "message_id": 789,
  "text": "Новый текст"
}
```

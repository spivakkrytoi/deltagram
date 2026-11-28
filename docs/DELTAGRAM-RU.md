# DeltaGram Bot API

Единственный раздел документации DeltaGram. Полная версия с методами, типами и
примерами: **https://dltagram.duckdns.org/docs/**

## Адрес

```text
https://dltagram.duckdns.org/botapi/bot<TOKEN>/<METHOD>
```

Файлы:

```text
https://dltagram.duckdns.org/botapi/file/bot<TOKEN>/<FILE_PATH>
```

## Проверка токена

```bash
curl "https://dltagram.duckdns.org/botapi/bot$TOKEN/getMe"
```

Ответ:

```json
{"ok":true,"result":{"id":1780243381,"is_bot":true,"first_name":"My Bot","username":"my_bot"}}
```

## Получение обновлений

```bash
curl "https://dltagram.duckdns.org/botapi/bot$TOKEN/getUpdates?timeout=25"
```

Webhook:

```bash
curl -X POST "https://dltagram.duckdns.org/botapi/bot$TOKEN/setWebhook" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/hook","secret_token":"my-secret"}'
```

## Отправка сообщения

```bash
curl -X POST "https://dltagram.duckdns.org/botapi/bot$TOKEN/sendMessage" \
  -H 'Content-Type: application/json' \
  -d '{"chat_id":123,"text":"Привет из DeltaGram"}'
```

## Методы

`getMe`, `getUpdates`, `setWebhook`, `deleteWebhook`, `getWebhookInfo`,
`sendMessage`, `sendRichMessage`, `sendPhoto`, `sendAnimation`, `sendAudio`,
`sendDocument`, `sendLivePhoto`, `sendSticker`, `sendVideo`, `sendVideoNote`,
`sendVoice`, `sendContact`, `sendLocation`, `sendVenue`, `editMessageText`,
`deleteMessage`, `answerCallbackQuery`, `getFile`, `setChatMenuButton`,
`getChatMenuButton`, `setMyCommands`, `getMyCommands`, `deleteMyCommands`,
`setUserEmojiStatus`, `answerWebAppQuery`, `savePreparedInlineMessage`,
`giftPremiumSubscription`, `answerShippingQuery`, `answerPreCheckoutQuery`,
ephemeral-методы.

## Ошибки

```json
{"ok":false,"error_code":400,"description":"Bad Request: chat not found"}
```

- `401` — неверный токен.
- `403` — бот заблокирован или нет прав.
- `429` — подождите `retry_after` секунд.

Токены создаются у **@BotFather** внутри DeltaGram. Токен настоящего Telegram
для этого API не подходит.

Все методы, параметры, типы и примеры кода:
**https://dltagram.duckdns.org/docs/**

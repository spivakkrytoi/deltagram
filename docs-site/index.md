---
title: Начало работы
description: HTTP Bot API DeltaGram — адрес, авторизация, запросы и обновления
---

# DeltaGram Bot API

HTTP-интерфейс для ботов DeltaGram, совместимый по формату с Telegram Bot API.
Библиотеки aiogram, python-telegram-bot, grammY и Telegraf работают без
изменений кода — достаточно заменить адрес сервера.

<div class="docs-stats">
  <a href="/methods"><b>33</b><span>метода</span></a>
  <a href="/types"><b>20+</b><span>типов</span></a>
  <a href="/errors"><b>JSON</b><span>ошибки как в Telegram</span></a>
</div>

## Адрес API

```text
https://dltagram.duckdns.org/botapi/bot<TOKEN>/<METHOD>
```

Файлы, полученные через `getFile`:

```text
https://dltagram.duckdns.org/botapi/file/bot<TOKEN>/<FILE_PATH>
```

Имена методов не зависят от регистра. Параметры принимаются как query string,
`application/x-www-form-urlencoded`, `application/json` и
`multipart/form-data` (для файлов).

Ответ всегда JSON с полем `ok`:

```json
{ "ok": true, "result": {} }
```

При ошибке:

```json
{
  "ok": false,
  "error_code": 400,
  "description": "Bad Request: chat not found"
}
```

## Авторизация

Токен создаётся внутри DeltaGram. В реальном Telegram токен создать нельзя —
это отдельная сеть со своими ботами.

1. Откройте в DeltaGram чат с **@BotFather**.
2. Отправьте `/newbot`.
3. Укажите имя и username, заканчивающийся на `bot`.
4. Сохраните токен вида `1234567890:AAH...`.

```bash
export TOKEN="1234567890:AAH..."
curl "https://dltagram.duckdns.org/botapi/bot$TOKEN/getMe"
```

Ответ:

```json
{
  "ok": true,
  "result": {
    "id": 1780243381,
    "is_bot": true,
    "first_name": "My Bot",
    "username": "my_bot",
    "can_join_groups": true,
    "can_read_all_group_messages": false,
    "supports_inline_queries": false
  }
}
```

## Форматы запросов

Все четыре варианта эквивалентны:

```bash
# query string
curl "https://dltagram.duckdns.org/botapi/bot$TOKEN/sendMessage?chat_id=123&text=Hi"

# JSON
curl -X POST "https://dltagram.duckdns.org/botapi/bot$TOKEN/sendMessage" \
  -H 'Content-Type: application/json' \
  -d '{"chat_id":123,"text":"Hi"}'

# form
curl -X POST "https://dltagram.duckdns.org/botapi/bot$TOKEN/sendMessage" \
  -d 'chat_id=123' -d 'text=Hi'

# multipart (файлы)
curl -X POST "https://dltagram.duckdns.org/botapi/bot$TOKEN/sendPhoto" \
  -F 'chat_id=123' -F 'caption=Фото' -F 'photo=@image.jpg'
```

## Обновления

### getUpdates

```bash
curl "https://dltagram.duckdns.org/botapi/bot$TOKEN/getUpdates?timeout=25"
```

Параметры:

| Поле | Тип | Описание |
| --- | --- | --- |
| `offset` | integer | ID первого обновления, которое нужно вернуть |
| `limit` | integer | 1–100, по умолчанию 100 |
| `timeout` | integer | Long polling, секунды |
| `allowed_updates` | string[] | Какие типы обновлений получать |

### setWebhook

```bash
curl -X POST "https://dltagram.duckdns.org/botapi/bot$TOKEN/setWebhook" \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://example.com/hook","secret_token":"my-secret"}'
```

Сервер отправляет JSON-обновление на ваш HTTPS URL. Секрет приходит в заголовке
`X-Telegram-Bot-Api-Secret-Token`.

- `deleteWebhook` — удалить webhook.
- `getWebhookInfo` — текущий URL, ошибки и количество ожидающих обновлений.

## Файлы

1. Вызовите `getFile` с `file_id`.
2. Получите `file_path`.
3. Скачайте:

```bash
curl -o file.bin \
  "https://dltagram.duckdns.org/botapi/file/bot$TOKEN/<FILE_PATH>"
```

Загрузка файлов — `multipart/form-data` в `sendPhoto`, `sendDocument`,
`sendVideo`, `sendAnimation`, `sendVoice`, `sendAudio`, `sendSticker`.

## Mini Apps

Отправьте кнопку с `web_app`:

```json
{
  "chat_id": 123,
  "text": "Открыть приложение",
  "reply_markup": {
    "inline_keyboard": [[
      { "text": "Открыть", "web_app": { "url": "https://example.com/app" }, "style": "success" }
    ]]
  }
}
```

`answerWebAppQuery` отправляет результат inline-запроса из Mini App.
`savePreparedInlineMessage` сохраняет подготовленное сообщение.

## Цветные кнопки

Поле `style` у `InlineKeyboardButton`:

- `primary` — синяя;
- `success` — зелёная;
- `danger` — красная.

```json
{
  "inline_keyboard": [[
    { "text": "Купить", "callback_data": "buy", "style": "success" },
    { "text": "Отмена", "callback_data": "cancel", "style": "danger" }
  ]]
}
```

## Библиотеки

```python
# aiogram 3
from aiogram import Bot
bot = Bot(token=TOKEN, server="https://dltagram.duckdns.org/botapi")
```

```js
// grammY
import { Bot } from "grammy";
const bot = new Bot(TOKEN, {
  client: { apiRoot: "https://dltagram.duckdns.org/botapi" },
});
```

```python
# python-telegram-bot
from telegram import Bot
bot = Bot(token=TOKEN, base_url="https://dltagram.duckdns.org/botapi/bot")
```

::: tip Токен
Не публикуйте токен. При утечке перевыпустите его через `/revoke` у @BotFather
в DeltaGram.
:::

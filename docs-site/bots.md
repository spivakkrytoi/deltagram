---
title: Боты и Bot API
description: grammystore, Gift Lab, BotFather и HTTP Bot API DeltaGram
---

# Боты

## grammystore / @deltagram_robot

- OTP webhook: `POST /v1/otp/deliveries` (порт `2800`).
- Подпись запросов: HMAC-SHA256 (`CODE_WEBHOOK_SECRET`).
- Код доставляется в чат, привязанный к номеру; коды не пересылаются
  пользователями.
- База `grammystore`: таблицы `numbers`, `otp_deliveries`, `code_access`.
- Токен хранится только в env контейнера, не в репозитории.
- Изменения Gift Lab в магазине этого бота **откатаны**: основной бот пока
  работает без кнопки Mini App.

## Gift Lab / тестовый бот

- Бот: `@kjndsajnksadjnkasdnjkasdbot`, отображаемое имя **DeltaGram Gift Lab**.
- Токен: `/etc/deltagram-gift-lab.env` (права `600`), не публикуется.
- Mini App: `https://dltagram.duckdns.org/gift-lab/`.
- `/start` и `/help` присылают цветные inline-кнопки:
  - **🎁 Открыть Gift Lab** (`style: success`) — открывает Mini App;
  - **📖 Как это работает** (`style: primary`) — ведёт на эту документацию.
- Доступ к Mini App только владельцам из `GIFT_LAB_OWNER_IDS`.
- В интерфейсе доступны уже выпущенные подарки: список, поиск, коллекция,
  живое превью «фон + модель + символ» и кнопка **«Выдать»**.
- В списке и в выборе модели — настоящие модели на сером фоне. Превью
  предварительно рендерятся в PNG и отдаются с `Cache-Control: immutable`.
- Показ тиража/остатка в интерфейсе убран.
- Получатель задаётся в `GIFT_LAB_GRANT_USERNAME` (сейчас `owner`) или
  `GIFT_LAB_GRANT_USER_ID`.
- Выдача идёт через `POST /v1/gifts/give` с `upgrade=true` и
  `allow_disabled=true`; каждый запрос идемпотентен по `requestID`.

## HTTP Bot API

Встроенный Bot API повторяет формат `api.telegram.org`:

```text
https://dltagram.duckdns.org/botapi/bot<TOKEN>/<method>
```

```bash
curl https://dltagram.duckdns.org/botapi/bot<TOKEN>/getMe
curl -X POST https://dltagram.duckdns.org/botapi/bot<TOKEN>/sendMessage \
  -H 'Content-Type: application/json' \
  -d '{"chat_id":1780243200,"text":"Привет из DeltaGram"}'
```

Поддерживаются, в частности:

- `getMe`, `getUpdates`, `setWebhook`, `deleteWebhook`, `getWebhookInfo`;
- `sendMessage`, `sendRichMessage`, `editMessageText`, `deleteMessage`;
- `sendPhoto`, `sendAnimation`, `sendAudio`, `sendDocument`, `sendSticker`,
  `sendVideo`, `sendVideoNote`, `sendVoice`, `sendLivePhoto`;
- `sendContact`, `sendLocation`, `sendVenue`;
- `answerCallbackQuery`, `getFile`;
- `setChatMenuButton`, `getChatMenuButton`;
- `setMyCommands`, `getMyCommands`, `deleteMyCommands`;
- `setUserEmojiStatus`, `answerWebAppQuery`, `savePreparedInlineMessage`,
  `giftPremiumSubscription`;
- ephemeral-сообщения и edit/delete-варианты.

Токен создаётся внутри DeltaGram и проверяется по `bots.token_secret`. Токен
настоящего Telegram (`api.telegram.org`) для этого API не подходит — это
отдельные пространства ботов.

Внутренний адрес `127.0.0.1:8081` (`TELESRV_BOT_API_ADDR`), наружу — Caddy
по пути `/botapi/*`.

## BotFather

В DeltaGram есть собственный `@BotFather` (ID `93372553`):

- `/newbot` — создать бота;
- `/mybots` — список ботов;
- `/deletebot` — удалить бота с подтверждением;
- `/token`, `/revoke` — показать/отозвать токен;
- `/setname`, `/setdescription`, `/setabouttext`;
- `/setcommands`, `/empty`;
- `/setinline`, `/setinlinegeo`, `/setinlinefeedback`;
- `/setjoingroups`, `/setprivacy`;
- `/setlogin`, `/logininfo`, `/resetloginsecret`, `/done`;
- `/cancel`, `/help`.

Ограничение: `/setuserpic` пока нет — аватар задаётся через админ-панель.

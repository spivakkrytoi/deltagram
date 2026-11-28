---
title: Веб-версия
description: Telegram Web A для DeltaGram — вход и работа через браузер
---

# Веб-версия

DeltaGram Web — это **Telegram Web A** (open-source `telegram-tt`), собранный
под DeltaGram и подключённый к собственному MTProto-серверу через
MTProto-over-WebSocket.

- Адрес: `https://dltagram.duckdns.org/web/`
- Клиент: Telegram Web A (`Ajaxy/telegram-tt`)
- Транспорт: `wss://dltagram.duckdns.org/apiws`
- Сервер: `150.241.69.39:2398` (DC2)

## Как это работает

1. Браузер загружает статическую сборку Web A из `/web/`.
2. Клиент открывает WebSocket на том же origin: `/apiws`.
3. Caddy проксирует upgrade в `telesrv:2398`, где включён
   `TELESRV_WEBSOCKET_ENABLE=true`.
4. Дальше обмен идёт обычным MTProto; вход по номеру, коды — в Telegram/`777000`.

В сборке изменён только WebSocket endpoint: вместо официальных
`zws*.web.telegram.org` клиент использует same-origin `wss://<host>/apiws`.
Это обязательно, потому что страница работает по HTTPS, а сырой MTProto-порт
`2398` не подходит для браузера.

## Вход

1. Откройте `https://dltagram.duckdns.org/web/`.
2. Нажмите **LOG IN BY PHONE NUMBER**.
3. Введите номер DeltaGram.
4. Код придёт в Telegram/`777000`.
5. При включённой 2FA введите пароль.

QR-вход также использует MTProto и работает через тот же WebSocket.

## Проверка

```bash
curl -I https://dltagram.duckdns.org/web/
curl -I https://dltagram.duckdns.org/web/assets/...
```

В браузере WebSocket должен открываться без ошибок:

```js
new WebSocket('wss://dltagram.duckdns.org/apiws', 'binary')
```

## Ограничения

- Звонки в браузере зависят от WebRTC и могут требовать HTTPS/STUN/TURN.
- Push-уведомления работают только при настроенном VAPID.
- Для нестандартных DC клиент всегда использует текущий origin; это
  рассчитано на один DeltaGram DC.

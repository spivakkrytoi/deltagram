---
title: Начало работы
description: DeltaGram — самостоятельный MTProto-сервер, клиенты, боты и Gift Lab
---

# DeltaGram

**DeltaGram** — самостоятельный MTProto-сервер, совместимый с Telegram-клиентами,
собственные Android/Windows-клиенты, маркетплейс Deltament, админ-панель и боты.
Документация описывает текущее состояние инфраструктуры и не содержит токенов.

<div class="docs-stats">
  <a href="/gift-lab"><b>Gift Lab</b><span>выдача выпущенных подарков</span></a>
  <a href="/bots#http-bot-api"><b>Bot API</b><span>совместимый HTTP-интерфейс</span></a>
  <a href="/web"><b>Web</b><span>Telegram Web A для DeltaGram</span></a>
  <a href="/windows"><b>Windows</b><span>сборка layer 233</span></a>
</div>

## Быстрый доступ

| Сервис | Адрес |
| --- | --- |
| Сайт / Deltament | `https://dltagram.duckdns.org/` |
| Веб-версия | `https://dltagram.duckdns.org/web/` |
| Документация | `https://dltagram.duckdns.org/docs/` |
| Админ-панель | `http://150.241.69.39:2600/accounts` |
| Подарки в админке | `http://150.241.69.39:2600/gifts` |
| MTProto | `150.241.69.39:2398` |
| Gift Lab | `https://dltagram.duckdns.org/gift-lab/` |
| Bot API | `https://dltagram.duckdns.org/botapi/bot<token>/<method>` |

Android APK от 28.11.2026:
`https://dltagram.duckdns.org/downloads/DeltaGram-frames-banner-20261128.apk`
SHA-256 `db725c48840318e9b835b6be23ca0b76aeabfd93431c72a1a8962969c78e350d`.

## Архитектура

- Caddy принимает HTTPS на `443` и проксирует:
  - `/` → Deltament `127.0.0.1:7000`;
  - `/downloads/*` → `/data/downloads`;
  - `/docs/*` → статическая документация `/data/docs`;
  - `/web/*` → Telegram Web A `/data/web`;
  - `/gift-lab/*` → `127.0.0.1:2810`;
  - `/botapi/*` → Bot API `127.0.0.1:8081`;
  - `/apiws*` → MTProto-over-WebSocket `127.0.0.1:2398`.
- Админка доступна только по IP и порту `2600`; отдельного поддомена нет.
- Все контейнеры имеют `restart: unless-stopped`; на хосте включён
  `podman-restart.service`.

| Компонент | Где работает | Назначение |
| --- | --- | --- |
| `telesrv` | `gramsrv-main_server_1` | MTProto DC, аккаунты, чаты, подарки, звёзды |
| PostgreSQL | `gramsrv-main_postgres_1` | основная БД `telesrv_main` |
| Redis | `gramsrv-main_redis_1` | сессии, коды, кэш |
| Deltament | `gramsrv-main_deltament_1` | сайт, маркетплейс, карта GRAM |
| Админ-панель | `gramsrv-main_admin_1` | `telesrv-admin`, порт `2600` |
| grammystore | `grammystore_app` | OTP-бот `@deltagram_robot`, номера |
| Gift Lab | `deltagram-gift-lab.service` | Mini App выпущенных подарков |

## Аккаунты и вход

- Вход выполняется по номеру телефона.
- Код **не отправляется по SMS**. Сервер доставляет его:
  1. сообщением от `@deltagram_robot` в Telegram;
  2. дублем в сервисный чат `777000`, если у аккаунта есть сессия.
- В клиенте отображается `auth.sentCodeTypeApp` — «код отправлен в Telegram».
- Новые аккаунты получают код через webhook grammystore, существующие —
  через `777000` + webhook.
- Пароль 2FA проверяется сервером; при `SESSION_PASSWORD_NEEDED` сессия не
  считается авторизованной до `auth.checkPassword`.

::: tip Веб-версия
Вход в `https://dltagram.duckdns.org/web/` использует тот же MTProto-сервер.
Код приходит в Telegram/`777000`, как и в мобильном клиенте.
:::

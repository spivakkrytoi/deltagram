# DeltaGram — документация

> Версия документа: 28.11.2026. Документ описывает текущее состояние
> инфраструктуры, клиентов, админ-панели, Deltament, ботов и подарков.
> Токены и пароли в документ не входят: они хранятся только в env-файлах
> на сервере.

Интерактивная версия этой документации: `https://dltagram.duckdns.org/docs/`.

## 1. Что такое DeltaGram

DeltaGram — самостоятельный MTProto-сервер (совместимый с Telegram-клиентами),
собственные Android/Windows-клиенты, маркетплейс Deltament, админ-панель и
набор Telegram-ботов.

Основные компоненты:

| Компонент | Где работает | Назначение |
| --- | --- | --- |
| `telesrv` | контейнер `gramsrv-main_server_1` | MTProto DC `150.241.69.39:2398`, аккаунты, чаты, подарки, звёзды |
| PostgreSQL | `gramsrv-main_postgres_1` | основная БД `telesrv_main` |
| Redis | `gramsrv-main_redis_1` | сессии, коды, кэш |
| Deltament | `gramsrv-main_deltament_1` | сайт, маркетплейс, карта GRAM, вход по коду |
| Админ-панель | `gramsrv-main_admin_1` | панель `telesrv-admin`, порт `2600` |
| Caddy | контейнер `caddy` | HTTPS, `/downloads`, `/investor`, `/gift-lab` |
| grammystore | `grammystore_app` + Postgres | OTP-бот `@deltagram_robot`, номера, магазин |
| Gift Lab | `deltagram-gift-lab.service` | Mini App выдачи выпущенных подарков, бот `@kjndsajnksadjnkasdnjkasdbot` |

## 2. Быстрый доступ

- Сайт/Deltament: `https://dltagram.duckdns.org/`
- Админ-панель: `http://150.241.69.39:2600/accounts`
- Подарки в админке: `http://150.241.69.39:2600/gifts`
- MTProto: `150.241.69.39:2398`
- Android APK (кадры + баннер, 28.11.2026):
  `https://dltagram.duckdns.org/downloads/DeltaGram-frames-banner-20261128.apk`
  SHA-256 `db725c48840318e9b835b6be23ca0b76aeabfd93431c72a1a8962969c78e350d`
- Gift Lab: `https://dltagram.duckdns.org/gift-lab/`
- Веб-версия: `https://dltagram.duckdns.org/web/`
- Документация: `https://dltagram.duckdns.org/docs/`
- Bot API: `https://dltagram.duckdns.org/botapi/bot<token>/<method>`
- Windows EXE: публикуется после успешной CI-сборки (см. раздел 11)

## 3. Архитектура и сеть

- Caddy принимает HTTPS на `443` и проксирует:
  - `/` → Deltament `127.0.0.1:7000`;
  - `/downloads/*` → статические файлы `/data/downloads`;
  - `/investor/*` → `127.0.0.1:2802`;
  - `/gift-lab/*` → `127.0.0.1:2810`;
  - `/botapi/*` → Bot API `127.0.0.1:8081` (префикс `/botapi`
    отрезается Caddy);
  - `/docs/*` → статическая документация `/data/docs`;
  - `/web/*` → Telegram Web A `/data/web`;
  - `/apiws*` → MTProto-over-WebSocket `127.0.0.1:2398`.
- Отдельный поддомен `admin.dltagram.duckdns.org` удалён: админка доступна
  только по IP и порту `2600`.
- Все контейнеры имеют `restart: unless-stopped`; на хосте включён
  `podman-restart.service`, чтобы стек поднимался после перезагрузки.

## 4. Аккаунты и вход

- Вход в DeltaGram выполняется по номеру телефона.
- Код входа **не отправляется по SMS**. Код создаётся сервером и доставляется:
  1. сообщением от `@deltagram_robot` в Telegram;
  2. дублем в сервисный чат `777000` (если у аккаунта есть активная сессия).
- В клиенте отображается тип `auth.sentCodeTypeApp` («код отправлен в Telegram»).
- Новые аккаунты получают код через webhook grammystore; существующие —
  через `777000` + webhook.
- Пароль двухфакторной аутентификации проверяется на сервере; при
  `SESSION_PASSWORD_NEEDED` сессия не считается авторизованной до
  `auth.checkPassword`.

## 5. Админ-панель

Вход: `http://150.241.69.39:2600/accounts`.
Логин `admin` и пароль берутся из `TELESRV_ADMIN_UI_PASSWORD` в
`/opt/gramsrv/deploy/docker/.env`.

Основные разделы:

- **Аккаунты** — поиск, профиль, сессии, баланс, блокировки.
- **Подарки** — каталог, ревизии, коллекции, лимиты, расписание, выдача.
- **Каналы/боты/юзернеймы/номера** — соответствующие реестры.
- **Аудит** — журнал административных команд.

### 5.1. Лимиты подарков

В форме **«Импорт набора подарков»** (и в форме одного подарка) есть:

- **«Лимит подарков»** — общий тираж (`availability_total`). `0` — без лимита.
- **«Лимит на пользователя»** — сколько подарков может получить один
  пользователь (`per_user_total`). `0` — без ограничения.
- **«Цена»**, **«Конвертация»**, **«Сортировка»**.

Для гифтпака эти значения применяются к подаркам, у которых в `pack.json`
нет собственных `limited` / `availability_total` / `per_user_total`.
Собственные значения из `pack.json` имеют приоритет.

### 5.2. Расписание продаж

Блок **«Расписание открытия»**:

- **«Одноразово»** — конкретная дата и время по Киеву; после этого подарок
  доступен постоянно.
- **«Ежедневное окно»** — время открытия по Киеву и длительность окна
  (1–1440 минут).

Время хранится как абсолютный Unix timestamp; клиент показывает его в
часовом поясе пользователя (например, 21:40 Киев = 22:40 Москва). До
открытия клиент рисует красный замок, сервер отклоняет покупку.

### 5.3. Sold out и resale

- Sold out-подарки скрываются через `star_gift_catalog.enabled = false`.
- Resale-предложения хранятся в `star_gift_listings`; приостановленные
  листинги имеют `suspended = true` и не показываются в магазине.
- Витринные счётчики `availability_resale` / `resell_min_stars`
  пересчитываются по активным листингам.
- Резервная копия перед чисткой магазина: таблица
  `star_gift_cleanup_20261127`.

### 5.4. Коллекции подарков

У подарка может быть коллекционный пул:

- модели (`star_gift_collectible_models`);
- паттерны (`star_gift_collectible_patterns`);
- фоны (`star_gift_collectible_backdrops`).

Атрибуты публикуются через `POST /v1/gifts/{id}/collectibles/publish`.
Клиенты получают модель/паттерн/фон в карточке коллекционного подарка.

Служебные маршруты для операторских инструментов (bearer-токен admin API):

- `GET /v1/gifts` — весь каталог, включая снятые с продажи подарки; поле
  `enabled` показывает, виден ли подарок в магазине.
- `GET /v1/gifts/{id}/collectibles` — пул фонов, моделей и символов.
- `GET /v1/gifts/{id}/collectibles/{kind}/{attribute_id}/animation` —
  Lottie-анимация атрибута.
- `POST /v1/gifts/give` — выдача. Для уже снятого с продажи, но
  опубликованного коллекционного подарка передайте `upgrade=true` и
  `allow_disabled=true`. Обычная продажа по-прежнему требует `enabled=true`.

## 6. Подарки в клиентах

- Каталог подарков: `payments.getStarGifts`.
- Замок: поле `locked_until_date` (Unix-время). До открытия кнопка покупки
  блокируется; Android показывает красный замок.
- В Android исправлен размер кастомной верификации (17dp, не перекрывает
  никнейм) и клик по «пузырькам» NFT-подарков: открывается внутренняя
  карточка `StarGiftSheet`, а не внешний `t.me/nft`.
- Обводки аватара: fire/blue/green используют один ободок, отрисованный
  **под** аватаром; лишние заливки/второй ободок убраны.
- Баннер профиля: загружается через Deltament (`POST /api/cards/banner`),
  подписывается MTProto-ключом (`DeltaGram <auth_key_id>:...`).

## 7. Deltament

### 7.1. Карта GRAM

- GRAM и TON — одна и та же единица (1:1).
- Карта хранится в `deltament_ton_cards` и `ton_balances`.
- В интерфейсе все подписи переименованы в **GRAM**.
- **«Вывести GRAM»** переводит GRAM с баланса маркета на карту DeltaGram;
  сумма появляется на карте сразу.
- **«С карты»** создаёт заявку на отправку с карты на внешний кошелёк.
- **«В звёзды»** конвертирует GRAM в Telegram Stars по курсу сервера
  (`stars_per_gram`; сейчас `1 GRAM = 100 ⭐`).

### 7.2. Карта в клиенте DeltaGram

Клиент вызывает `wallet.getState`. Сервер отдаёт адрес и баланс карты из
тех же таблиц Deltament. `wallet.getExistingWaltBalance` возвращает пустой
ответ (у DeltaGram нет отдельного Walt-баланса).

### 7.3. Вход в Deltament

- Вход по номеру DeltaGram; код приходит в Telegram/`777000`.
- Сессия Deltament хранится в `deltament_market_sessions` (хэш токена).
- Нативные запросы клиента (карта, баннер, настройки) подписываются
  постоянным MTProto-ключом; проверка идёт через
  `POST /v1/native/verify` на admin API.

## 8. Боты

### 8.1. grammystore / `@deltagram_robot`

- OTP webhook: `POST /v1/otp/deliveries` (порт `2800`).
- Подпись запросов: HMAC-SHA256 (`CODE_WEBHOOK_SECRET`).
- Доставка кода в чат, привязанный к номеру; коды не пересылаются
  пользователями.
- База: `grammystore` (Postgres), таблицы `numbers`, `otp_deliveries`,
  `code_access`.
- Токен бота хранится только в env контейнера, не в репозитории.

### 8.2. Gift Lab / Mini App

- Бот: `@kjndsajnksadjnkasdnjkasdbot` (отображаемое имя — DeltaGram Gift Lab).
- Токен: `/etc/deltagram-gift-lab.env` (права `600`), не публикуется.
- Mini App: `https://dltagram.duckdns.org/gift-lab/`.
- Проверка `initData` обязательна; доступ только владельцам из
  `GIFT_LAB_OWNER_IDS`.
- В интерфейсе доступны **уже выпущенные** подарки: список, поиск, коллекция,
  живое превью (фон + модель + символ) и кнопка **«Выдать»**.
- В списке подарков и в выборе модели показываются настоящие модели подарка
  на сером фоне. Превью моделей предварительно рендерятся в PNG
  (`web/thumbs/models` и `web/thumbs/gifts`) и отдаются с
  `Cache-Control: immutable`, поэтому список и пикер открываются без
  скачивания 100–800 КБ Lottie на каждую строку.
- Превью собирается из настоящих Lottie-анимаций моделей и символов и цветов
  фона; данные берутся из admin API, а не из внешнего `nft.staticgram.top`.
- Тираж/остаток в интерфейсе не показывается. Снятые с продажи подарки
  помечаются «снят с продажи», но остаются доступны для выдачи, если у
  коллекции ещё есть свободный тираж.
- Получатель задаётся в `GIFT_LAB_GRANT_USERNAME` (сейчас `owner`) или
  `GIFT_LAB_GRANT_USER_ID`. Выдача идёт через
  `POST /v1/gifts/give` с `upgrade=true` и `allow_disabled=true`.
- Идемпотентность: каждый запрос содержит `requestID`; повторная отправка
  того же запроса не создаёт второй подарок.
- Команды бота: `/start`, `/help`, `/id`. В ответе — цветные inline-кнопки:
  **🎁 Открыть Gift Lab** (`style: success`) и **📖 Как это работает**
  (`style: primary`, ведёт на этот сайт документации).
- Изменения в магазине основного бота `@deltagram_robot` откатаны: он пока
  работает без кнопки Mini App.

## 9. Bot API и BotFather

### 9.1. HTTP Bot API

Встроенный Bot API повторяет формат `api.telegram.org`. Адрес:

```text
https://dltagram.duckdns.org/botapi/bot<TOKEN>/<method>
```

Примеры:

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
- ephemeral-сообщения: `sendEphemeralMessage` и edit/delete-варианты.

Токен бота создаётся внутри DeltaGram и проверяется по `bots.token_secret`.
Токен из настоящего Telegram (`api.telegram.org`) для этого API не подходит —
это отдельные пространства ботов.

Внутренний адрес: `127.0.0.1:8081` (`TELESRV_BOT_API_ADDR`), наружу
публикуется Caddy по пути `/botapi/*`.

### 9.2. BotFather

В DeltaGram есть собственный `@BotFather` (ID `93372553`). Он создаёт и
обслуживает ботов внутри DeltaGram:

- `/newbot` — создать бота (имя и username);
- `/mybots` — список своих ботов;
- `/deletebot` — удалить своего бота с подтверждением;
- `/token`, `/revoke` — показать/отозвать токен;
- `/setname`, `/setdescription`, `/setabouttext`;
- `/setcommands` — список команд, `/empty` очищает;
- `/setinline`, `/setinlinegeo`, `/setinlinefeedback`;
- `/setjoingroups`, `/setprivacy`;
- `/setlogin`, `/logininfo`, `/resetloginsecret`, `/done` — Telegram Login;
- `/cancel`, `/help`.

Ограничение: `/setuserpic` (аватар бота) пока не входит в набор — аватар
задаётся через админ-панель. Остальные основные команды, включая
`/deletebot`, реализованы.

## 10. Веб-версия

- Адрес: `https://dltagram.duckdns.org/web/`.
- Клиент: Telegram Web A (`Ajaxy/telegram-tt`), собранный под DeltaGram.
- Транспорт: `wss://dltagram.duckdns.org/apiws` → Caddy → `telesrv:2398`.
- В клиенте изменён WebSocket endpoint на same-origin `/apiws`; сырой
  MTProto-порт `2398` из HTTPS-браузера недоступен.
- Вход по номеру DeltaGram; код приходит в Telegram/`777000`.
- QR-вход использует тот же MTProto и работает через тот же WebSocket.
- Включены `TELESRV_WEBSOCKET_ENABLE=true` и origin
  `https://dltagram.duckdns.org` в `TELESRV_WEBSOCKET_ALLOWED_ORIGINS`.

## 11. Windows EXE

CI: `spivakkrytoi/deltagram-desktop`, workflow `deltagram-windows.yml`,
ветка `dg-win-layer233`.

Сборка выполняется на layer 233 (сервер поддерживает 232 и 233).
Предыдущая попытка дошла до `Configure DeltaGram`, но упала на проверке
свободного места перед компиляцией: 22 ГБ при пороге 23 ГБ. В workflow
добавлена очистка NuGet/npm/pip/cargo-кэшей и порог снижен до 20 ГБ.
Текущий запуск: `46157635724` (ветка `dg-win-layer233`).

После успешной сборки ZIP публикуется в `/downloads/` и указывается
SHA-256. Старый архив layer 234 с текущим сервером несовместим.

## 12. Безопасность

- Все секреты — только в env-файлах на сервере (`/etc/...env`,
  `deploy/docker/.env`), права `600`.
- Не коммитить: `BOT_TOKEN`, `TELESRV_ADMIN_API_TOKEN`,
  `TELESRV_OTP_WEBHOOK_SECRET`, `POSTGRES_PASSWORD`,
  `DELTAMENT_AUTH_SECRET`, приватные ключи и `tdata`.
- Админ-панель на IP:2600 работает по HTTP; при доступе из недоверенной
  сети используйте VPN или попросите включить HTTPS-путь.
- Для бота Gift Lab использовать отдельный токен; при утечке — перевыпустить
  у @BotFather и обновить `/etc/deltagram-gift-lab.env`.

## 13. Диагностика

```bash
# контейнеры
podman ps --format '{{.Names}}\t{{.Status}}'
# сервер
podman logs --tail 100 gramsrv-main_server_1
# Deltament
podman logs --tail 100 gramsrv-main_deltament_1
# админка
podman logs --tail 100 gramsrv-main_admin_1
# бот Gift Lab
journalctl -u deltagram-gift-lab -n 100 --no-pager
# Bot API (getMe любого бота DeltaGram)
curl "https://dltagram.duckdns.org/botapi/bot<TOKEN>/getMe"
# systemd
systemctl --failed
```

База:

```bash
podman exec -it gramsrv-main_postgres_1 \
  psql -U "$POSTGRES_USER" -d "$POSTGRES_DB"
```

Если после перезагрузки стек не поднялся:

```bash
systemctl status podman-restart
podman pod start pod_gramsrv-main
podman start caddy grammystore_app
```

## 14. Ограничения

- Реальная выдача NFT/подарков на блокчейн требует отдельной защищённой
  серверной интеграции; сейчас доступна выдача в DeltaGram.
- Windows-клиент layer 233 появляется только после успешного CI.
- Веб-версия использует один DeltaGram DC; мульти-DC переключение не настроено.
- В BotFather пока нет `/setuserpic`; аватар бота задаётся через
  админ-панель. Остальные основные команды реализованы.
- Gift Lab показывает и выдаёт в том числе подарки, снятые с продажи
  (`enabled=false`), но только если у них есть опубликованная коллекция.

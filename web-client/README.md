# DeltaGram Web

Веб-версия DeltaGram — это **Telegram Web A** (`Ajaxy/telegram-tt`), собранная
под DeltaGram и подключённая к нашему MTProto-серверу.

## Дерево

```bash
git clone --depth 1 https://github.com/Ajaxy/telegram-tt.git
cd telegram-tt
npm install
```

## Патчи

1. `src/lib/gramjs/extensions/PromisedWebSockets.ts` — на HTTPS всегда
   подключаться к same-origin `wss://${location.host}/apiws...`, а не к
   `zws*.web.telegram.org`.
2. `src/api/gramjs/methods/settings.ts` — при получении langpack заменять
   видимые `Telegram` на `DeltaGram` (кроме URL).
3. `src/assets/localization/initialStrings.ts` и `fallback.strings` — брендинг
   строк.
4. `src/components/auth/AuthQrCode.tsx` и `Auth.scss` — логотип DeltaGram в
   центре QR.
5. `public/icon-*.png`, `apple-touch-icon.png`, `favicon.*` — иконки DeltaGram.
6. `src/config.ts` — версии кэшей `tt-assets-dg2` / snapshot, чтобы старый
   service worker не отдавал старый брендинг.

## Сборка

```bash
TELEGRAM_API_ID=34122536 \
TELEGRAM_API_HASH=8353c14fb75e19919d62f8e74432c51e \
BASE_URL=https://dltagram.duckdns.org/web/ \
APP_NAME='DeltaGram Web' APP_TITLE='DeltaGram Web' \
npm run build:production
```

## Публикация

```bash
rm -rf /opt/caddy/data/web/*
cp -a dist/. /opt/caddy/data/web/
```

Caddy отдаёт `/web/*` и проксирует `/apiws*` в `telesrv:2398`.

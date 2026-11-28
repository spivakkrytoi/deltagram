# DeltaGram Gift Lab

Mini App для выдачи **уже выпущенных** коллекционных подарков DeltaGram.
Владелец выбирает подарок, настраивает фон, модель и символ, видит живое
превью и выдаёт экземпляр получателю, указанному в настройках.

## Что делает

- `GET /gift-lab/` — интерфейс Mini App.
- `GET /gift-lab/api/gifts` — список выпущенных подарков с коллекциями.
- `GET /gift-lab/api/gifts/{id}/collectibles` — фоны, модели, символы.
- `GET /gift-lab/api/gifts/{id}/collectibles/{kind}/{attribute}/animation` —
  Lottie-анимация модели или символа.
- `POST /gift-lab/api/issue` — выдача подарка через admin API
  (`POST /v1/gifts/give`, `upgrade=true`, `allow_disabled=true`).

Все запросы требуют подпись Telegram Mini App (`X-Init-Data`) и аккаунт из
`GIFT_LAB_OWNER_IDS`. Прямой доступ из браузера без initData запрещён.

## Переменные окружения

```env
GIFT_LAB_BOT_TOKEN=        # токен бота из @BotFather
GIFT_LAB_OWNER_IDS=        # Telegram ID владельцев через запятую
GIFT_LAB_GRANT_USERNAME=   # @username получателя (например, owner)
GIFT_LAB_GRANT_USER_ID=    # запасной вариант: числовой ID
GIFT_LAB_PUBLIC_URL=       # https://.../gift-lab/
GIFT_LAB_ADMIN_API=        # http://127.0.0.1:2599
GIFT_LAB_ADMIN_TOKEN=      # bearer-токен admin API
```

Токены хранятся только в `/etc/deltagram-gift-lab.env` с правами `600` и не
коммитятся в репозиторий.

## Запуск

```bash
systemctl restart deltagram-gift-lab
journalctl -u deltagram-gift-lab -n 50 --no-pager
```

## Тесты

```bash
cd server/cmd/bots/gift-lab
node --test
```

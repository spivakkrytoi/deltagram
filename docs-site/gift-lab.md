---
title: Gift Lab
description: Mini App для выдачи выпущенных подарков DeltaGram
---

# Gift Lab

Gift Lab — Mini App тестового бота **@kjndsajnksadjnkasdnjkasdbot**
(отображаемое имя **DeltaGram Gift Lab**). Он выбирает уже выпущенный подарок,
настраивает фон, модель и символ, показывает живое превью и выдаёт экземпляр
получателю.

- Mini App: `https://dltagram.duckdns.org/gift-lab/`
- Бот: `@kjndsajnksadjnkasdnjkasdbot`
- Токен: `/etc/deltagram-gift-lab.env` (права `600`)
- Доступ: только Telegram ID из `GIFT_LAB_OWNER_IDS`

## Возможности

- Список всех выпущенных коллекций с настоящими моделями на сером фоне.
- Поиск по подаркам.
- Выбор фона, модели и символа с реальными превью.
- Случайная комбинация одной кнопкой.
- Выдача получателю `@owner` (настраивается).
- Цветные кнопки в боте: зелёная «Открыть Gift Lab» и синяя «Как это работает».

## Производительность

Модели коллекций — тяжёлые Lottie-анимации (100–800 КБ). Для быстрого списка и
пикера они заранее рендерятся в PNG на сером фоне:

```text
server/cmd/bots/gift-lab/web/thumbs/gifts/<giftID>.png
server/cmd/bots/gift-lab/web/thumbs/models/<giftID>-<modelID>.png
```

Файлы отдаются с `Cache-Control: immutable`. Анимации выбранной модели и
символа кешируются в WebView на 24 часа. Если PNG ещё не сгенерирован,
интерфейс откатывается на Lottie.

## API Mini App

Все запросы требуют подпись Telegram Mini App в заголовке `X-Init-Data`.

- `GET /gift-lab/api/gifts` — список выпущенных подарков.
- `GET /gift-lab/api/gifts/{id}/collectibles` — фоны, модели, символы.
- `GET /gift-lab/api/gifts/{id}/collectibles/{kind}/{attribute}/animation` —
  Lottie-анимация.
- `POST /gift-lab/api/issue` — выдача.

```json
{
  "giftID": "9000000000000086",
  "model": "3692",
  "pattern": "11540",
  "backdrop": "4021",
  "requestID": "uuid"
}
```

## Переменные окружения

```env
GIFT_LAB_BOT_TOKEN=
GIFT_LAB_OWNER_IDS=
GIFT_LAB_GRANT_USERNAME=owner
GIFT_LAB_GRANT_USER_ID=
GIFT_LAB_PUBLIC_URL=https://dltagram.duckdns.org/gift-lab/
GIFT_LAB_LISTEN=127.0.0.1
GIFT_LAB_PORT=2810
GIFT_LAB_ADMIN_API=http://127.0.0.1:2599
GIFT_LAB_ADMIN_TOKEN=
```

## Ограничения

- Полностью исчерпанный тираж коллекции не выдаётся.
- Снятый с продажи подарок можно выдать, если остался свободный тираж.
- Часть моделей может не иметь PNG-превью: тогда используется Lottie.

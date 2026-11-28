---
title: Deltament
description: Карта GRAM, вывод, конвертация в Stars и вход
---

# Deltament

## Карта GRAM

- GRAM и TON — одна и та же единица (1:1).
- Карта хранится в `deltament_ton_cards` и `ton_balances`.
- Все подписи в интерфейсе — **GRAM**.
- **«Вывести GRAM»** переводит GRAM с баланса маркета на карту DeltaGram;
  сумма появляется на карте сразу.
- **«С карты»** создаёт заявку на отправку на внешний кошелёк.
- **«В звёзды»** конвертирует GRAM в Stars по курсу сервера
  (`stars_per_gram`; сейчас `1 GRAM = 100 ⭐`).

## Карта в клиенте

Клиент вызывает `wallet.getState`. Сервер отдаёт адрес и баланс карты из тех же
таблиц Deltament. `wallet.getExistingWaltBalance` возвращает пустой ответ — у
DeltaGram нет отдельного Walt-баланса.

## Вход

- Вход по номеру DeltaGram; код приходит в Telegram/`777000`.
- Сессия Deltament хранится в `deltament_market_sessions` (хэш токена).
- Нативные запросы клиента подписываются постоянным MTProto-ключом; проверка
  идёт через `POST /v1/native/verify` на admin API.

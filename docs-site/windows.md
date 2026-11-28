---
title: Windows-клиент
description: Сборка DeltaGram.exe через GitHub Actions
---

# Windows-клиент

CI: `spivakkrytoi/deltagram-desktop`, workflow `deltagram-windows.yml`, ветка
`dg-win-layer233`.

Сборка выполняется на layer 233; сервер поддерживает 232 и 233. Архив layer 234
с текущим сервером несовместим.

## Что уже исправлено в CI

- Снижен порог свободного места под pagefile.
- Добавлена очистка Edge, Windows Update, Temp, NuGet/npm/pip/cargo-кэшей.
- Порог перед компиляцией снижен до 20 ГБ: предыдущий прогон дошёл до
  `Configure DeltaGram` и упал при 22 ГБ свободного места.
- Текущий запуск: `46157635724`.

## Результат

После успешной сборки публикуются:

- `DeltaGram.exe`;
- `DeltaGram-win64-preview.zip`.

Файлы кладутся в `/downloads/`, SHA-256 указывается рядом. Если сборка снова
упадёт, смотрите аннотацию `Build log missing` — она означает, что компилятор
не стартовал из-за места или конфигурации, а не из-за ошибки кода.

## Локальная сборка

Нужны Visual Studio 2022 C++/WinRT, Qt 6 и cmake. Workflow использует
`cmake --build out --config Debug --target Telegram --parallel 1`, затем
упаковывает preview.

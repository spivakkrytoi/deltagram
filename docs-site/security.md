---
title: Безопасность и диагностика
description: Секреты, доступ и обслуживание DeltaGram
---

# Безопасность

- Все секреты — только в env-файлах на сервере (`/etc/...env`,
  `deploy/docker/.env`), права `600`.
- Не коммитить `BOT_TOKEN`, `TELESRV_ADMIN_API_TOKEN`,
  `TELESRV_OTP_WEBHOOK_SECRET`, `POSTGRES_PASSWORD`,
  `DELTAMENT_AUTH_SECRET`, приватные ключи и `tdata`.
- Админ-панель на IP:2600 работает по HTTP. Для доступа из недоверенной сети
  используйте VPN.
- Для Gift Lab используйте отдельный токен; при утечке перевыпустите его
  у @BotFather и обновите `/etc/deltagram-gift-lab.env`.
- Токен основного бота `@deltagram_robot` не меняется и не публикуется.

# Диагностика

```bash
podman ps --format '{{.Names}}\t{{.Status}}'
podman logs --tail 100 gramsrv-main_server_1
podman logs --tail 100 gramsrv-main_deltament_1
podman logs --tail 100 gramsrv-main_admin_1
journalctl -u deltagram-gift-lab -n 100 --no-pager
curl "https://dltagram.duckdns.org/botapi/bot<TOKEN>/getMe"
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

# Ограничения

- Реальная выдача NFT на блокчейн требует отдельной защищённой интеграции;
  сейчас доступна выдача в DeltaGram.
- Windows-клиент layer 233 появляется только после успешного CI.
- В BotFather пока нет `/setuserpic`; аватар задаётся через админ-панель.
- Gift Lab выдаёт снятые с продажи подарки только при свободном тираже.
- Веб-версия использует один DeltaGram DC; мульти-DC переключение не настроено.

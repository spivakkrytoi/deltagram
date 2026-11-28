# DeltaGram Docs

VitePress-сайт документации: `https://dltagram.duckdns.org/docs/`.

## Сборка

```bash
npm install
npm run docs:build
```

## Публикация

```bash
rm -rf /opt/caddy/data/docs/*
cp -a .vitepress/dist/. /opt/caddy/data/docs/
```

Caddy отдаёт `/docs/*` из `/data/docs` с fallback на `index.html`.

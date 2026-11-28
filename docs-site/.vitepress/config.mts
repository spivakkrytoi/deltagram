import { defineConfig } from 'vitepress'

export default defineConfig({
  lang: 'ru',
  title: 'DeltaGram Docs',
  description: 'Документация DeltaGram: сервер, клиенты, боты, Gift Lab и веб-версия',
  base: '/docs/',
  cleanUrls: true,
  head: [
    ['meta', { name: 'theme-color', content: '#1c1c1e' }],
    ['meta', { name: 'color-scheme', content: 'dark light' }],
    ['link', { rel: 'icon', href: '/docs/favicon.svg' }],
  ],
  themeConfig: {
    logo: '/logo.svg',
    siteTitle: 'DeltaGram',
    nav: [
      { text: 'Руководство', link: '/' },
      { text: 'Gift Lab', link: '/gift-lab' },
      { text: 'Bot API', link: '/bots' },
      { text: 'Веб-версия', link: '/web' },
      { text: 'Windows', link: '/windows' },
    ],
    sidebar: [
      {
        text: 'Начало',
        items: [
          { text: 'Обзор', link: '/' },
          { text: 'Быстрый доступ', link: '/#быстрый-доступ' },
        ],
      },
      {
        text: 'Сервер',
        items: [
          { text: 'Архитектура', link: '/#архитектура' },
          { text: 'Аккаунты и вход', link: '/#аккаунты-и-вход' },
          { text: 'Админ-панель', link: '/admin' },
          { text: 'Подарки', link: '/gifts' },
          { text: 'Deltament', link: '/deltament' },
        ],
      },
      {
        text: 'Боты',
        items: [
          { text: 'Обзор ботов', link: '/bots' },
          { text: 'Bot API', link: '/bots#http-bot-api' },
          { text: 'BotFather', link: '/bots#botfather' },
        ],
      },
      {
        text: 'Инструменты',
        items: [
          { text: 'Gift Lab', link: '/gift-lab' },
          { text: 'Веб-версия', link: '/web' },
          { text: 'Windows-клиент', link: '/windows' },
          { text: 'Безопасность', link: '/security' },
          { text: 'Диагностика', link: '/security#диагностика' },
        ],
      },
    ],
    search: { provider: 'local' },
    outline: { level: [2, 3], label: 'На этой странице' },
    docFooter: { prev: 'Назад', next: 'Далее' },
    darkModeSwitchLabel: 'Тема',
    sidebarMenuLabel: 'Меню',
    returnToTopLabel: 'Наверх',
    lastUpdated: { text: 'Обновлено' },
    footer: {
      message: 'DeltaGram · самостоятельный MTProto-сервер и клиенты',
      copyright: '© 2026 DeltaGram',
    },
  },
})

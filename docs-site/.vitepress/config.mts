import { defineConfig } from 'vitepress'

export default defineConfig({
  lang: 'ru',
  title: 'DeltaGram Bot API',
  description: 'HTTP Bot API DeltaGram, совместимый с Telegram Bot API',
  base: '/docs/',
  cleanUrls: true,
  head: [
    ['meta', { name: 'theme-color', content: '#1c1c1e' }],
    ['meta', { name: 'color-scheme', content: 'dark light' }],
    ['link', { rel: 'icon', href: '/docs/favicon.svg' }],
  ],
  themeConfig: {
    logo: '/logo.svg',
    siteTitle: 'DeltaGram Bot API',
    nav: [
      { text: 'Руководство', link: '/' },
      { text: 'Методы', link: '/methods' },
      { text: 'Типы', link: '/types' },
      { text: 'Ошибки', link: '/errors' },
    ],
    sidebar: [
      {
        text: 'Bot API',
        items: [
          { text: 'Начало работы', link: '/' },
          { text: 'Адрес и авторизация', link: '/#адрес-api' },
          { text: 'Форматы запросов', link: '/#форматы-запросов' },
          { text: 'Обновления', link: '/#обновления' },
          { text: 'Файлы', link: '/#файлы' },
          { text: 'Mini Apps', link: '/#mini-apps' },
        ],
      },
      {
        text: 'Справочник',
        items: [
          { text: 'Все методы', link: '/methods' },
          { text: 'Типы', link: '/types' },
          { text: 'Ошибки и лимиты', link: '/errors' },
        ],
      },
    ],
    search: { provider: 'local' },
    outline: { level: [2, 3], label: 'На этой странице' },
    docFooter: { prev: 'Назад', next: 'Далее' },
    darkModeSwitchLabel: 'Тема',
    sidebarMenuLabel: 'Меню',
    returnToTopLabel: 'Наверх',
    footer: {
      message: 'DeltaGram Bot API',
      copyright: '© 2026 DeltaGram',
    },
  },
})

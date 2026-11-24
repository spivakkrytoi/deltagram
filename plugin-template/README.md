# DeltaGram plugin template

Builds a `.plugin` file: a zip with `manifest.json` and `classes.dex`.

## Requirements

- JDK 17 on `PATH` (`javac`, `jar`)
- Android SDK build-tools (`d8`) — set `ANDROID_HOME`

## Build

```powershell
.\build.ps1
```

The result is `build\hello.plugin`. Copy it to the phone, then install it from
**Настройки → Плагины → Установить .plugin**.

## What a plugin can do

Only what `DeltamentPluginContext` exposes right now:

| Method | Purpose |
|---|---|
| `getAppContext()` | application context |
| `getAccount()` | currently selected account |
| `getDir()` | private folder for the plugin's files |
| `getPrefs()` | per-plugin `SharedPreferences` |
| `log(text)` | writes to logcat with a `[dg-plugin id]` prefix |
| `runOnUiThread(runnable)` | posts to the UI thread |

Hook API (message send, requests, menus) is not exposed yet — that is the next
step and needs a stable interface before plugins start depending on it.

## manifest.json

```json
{
  "id": "hello",
  "name": "Hello plugin",
  "version": "1.0",
  "author": "@you",
  "description": "optional, shown under the name",
  "entry": "com.deltagram.plugin.HelloPlugin"
}
```

`id` is used for the file name and preferences, so keep it stable: `a-z`, `0-9`,
`_` and `-`.

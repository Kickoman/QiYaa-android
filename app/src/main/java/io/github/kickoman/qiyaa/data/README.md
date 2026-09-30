# `data/` — сохранение настроек

Всё, что переживает перезапуск: пользовательские настройки и OAuth-токен в приватных `SharedPreferences` приложения (`MODE_PRIVATE`), очередь — в файле `queue.json` во внутренней памяти. Резервное копирование отключено в манифесте. Пакет **не** применяет настройки к плееру: EQ и баланс в `AudioBus` передаёт `AppGraph` (подписки на `StateFlow` в `applicationScope`), громкость — `playback/PlaybackService`. Экраны только меняют настройки.

| Файл | Содержит |
|---|---|
| `Settings.kt` | `Settings` — громкость, баланс, визуализатор, EQ, тема |
| `TokenStore.kt` | `TokenStore` — токен |
| `QueueFile.kt` | `QueueFile` — сохранённая очередь, файл `queue.json` |
| `JamFile.kt` | `JamFile` — сессия хозяина джема, файл `jam.json` |
| `AccentTheme.kt` | `AccentTheme` — три акцентные темы |
| `VisualizerMode.kt` | `VisualizerMode` — спектр / осциллограф / выкл |

## `Settings`

Файл `settings`. Каждое значение — `StateFlow`, писатель `setX` сначала обновляет поток, затем `apply()`:

| Ключ | Тип | По умолчанию | Поток |
|---|---|---|---|
| `volume` | int 0…100 | 75 | `volume` |
| `balance` | int −100…100 | 0 | `balance` |
| `vis/mode` | int (`VisualizerMode.storedValue`: 0 спектр, 1 осциллограф, 2 выкл) | 0 | `visualizerMode` |
| `time/remaining` | boolean | false | `timeRemaining` |
| `equalizer/auto` | boolean | false | `eqAuto` |
| `equalizer/preset` | string | `"Flat"` | `eqPreset` |
| `equalizer/enabled` | boolean | true | часть `eq` |
| `equalizer/preamp` | float dB | 0 | часть `eq` |
| `equalizer/bands` | string, 10 чисел через запятую с одним знаком (`"0.0,3.5,…"`) | плоские | часть `eq` |
| `theme` | string (`AccentTheme.key`: `green`, `amber`, `ice`) | `amber` | `theme` |
| `jam/server` | string, адрес сервера джема (`https://jam.example.org`) | `BuildConfig.JAM_URL` из Gradle-свойства `qiyaaJamUrl`, иначе `""` | `jamServer` |
| `jam/hostKey` | string, ключ хозяина `qjk_…` (выдаёт `keys add` на сервере) | `""` | `jamHostKey` |

Ключи и умолчания те же, что в десктопном QiYaa, где они применимы. `jam/server` и `jam/hostKey`
обрезаются по краям при записи; адрес по умолчанию передаёт `AppGraph` в конструктор, потому что
`data` не видит `BuildConfig`. `setEq(settings, presetName)` пишет четыре ключа одной транзакцией. Строка полос с числом элементов ≠ 10 или с нечисловыми значениями читается как плоский EQ.

**Traps:**
- Значения зажимаются на записи (`setVolume`, `setBalance`), но не на чтении: чужое значение в файле уйдёт в поток как есть.
- `equalizer/preamp` хранится как `float`, а `EqSettings.preampDb` — `Double`.

## `TokenStore`

Файл `auth`, ключ `token`. `load()` возвращает `""`, если токена нет; `clear()` удаляет ключ. Пустая строка означает «не вошли».

## `QueueFile`

Файл `filesDir/queue.json` через `android.util.AtomicFile`: запись во временную копию и переименование, поэтому выгрузка процесса посреди записи оставляет прежний файл. `read()` возвращает `null`, если файла нет или он не читается; `write` бросает `IOException`. Содержимое — текст, формат которого знает только `queue/QueueSnapshotCodec` (см. `queue/README.md`); `AppGraph` оборачивает `QueueFile` в `queue.QueueStore`, потому что `data` не зависит от `queue`. Пишется с потока `Dispatchers.IO`.

## `JamFile`

Как `QueueFile`, только файл `filesDir/jam.json`, а `write(null)` удаляет его: джем закончился.
Формат знает `jam/JamSessionCodec`; `AppGraph` оборачивает файл в `jam.JamStore`.

## `AccentTheme`, `VisualizerMode`

Оба enum хранятся по явному значению (`key`, `storedValue`), а не по `ordinal`, и умеют `next()` по кругу. Неизвестное сохранённое значение — `AccentTheme.DEFAULT` (`AMBER`) и `VisualizerMode.SPECTRUM`. Подписи тем — в `ui` (`AccentTheme.labelId()` → `R.string.theme_*`).

## Not here

- Что делать с токеном при 401 — `ui/AppViewModel.restoreSession`. Применение EQ к звуку — `audio/EqualizerDsp`.

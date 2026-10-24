# `data/` — сохранение настроек

Всё, что переживает перезапуск: пользовательские настройки и OAuth-токен в приватных `SharedPreferences` приложения (`MODE_PRIVATE`), очередь и джем — в файлах `queue.json`, `jam.json` и `jam-guest.json` во внутренней памяти (`filesDir`); что из этого уходит в копии и на другое устройство — в «Резервном копировании» ниже. Пакет **не** применяет настройки к плееру: EQ и баланс в `AudioBus` передаёт `AppGraph` (подписки на `StateFlow` в `applicationScope`), громкость — `playback/PlaybackService`. Экраны только меняют настройки.

| Файл | Содержит |
|---|---|
| `Settings.kt` | `Settings` — громкость, баланс, визуализатор, EQ, тема, язык, качество звука, «продолжать волной», алгоритм shuffle, телеметрия |
| `AudioQuality.kt` | `AudioQuality` — стандарт / экономия (`spec/player/quality.md`) |
| `ShuffleChoice.kt` | `ShuffleChoice` — алгоритм shuffle: без повторов / случайный трек (SHUF-09, SHUF-10, `spec/player/shuffle.md`) |
| `AppLanguage.kt` | `AppLanguage` — языки интерфейса: белорусский (по умолчанию), русский, английский |
| `TokenStore.kt` | `TokenStore` — токен |
| `QueueFile.kt` | `QueueFile` — сохранённая очередь, файл `queue.json` |
| `JamFile.kt` | `JamFile` — сессия хозяина джема (`jam.json`) и членства гостя (`jam-guest.json`) |
| `AccentTheme.kt` | `AccentTheme` — три акцентные темы |
| `VisualizerMode.kt` | `VisualizerMode` — спектр / осциллограф / выкл |

## Резервное копирование

`allowBackup="false"` и `fullBackupContent="false"` в манифесте выключают облачную копию и `adb backup` на всех версиях Android, а на Android 8–11 и перенос на новое устройство. На Android 12 и новее перенос с устройства на устройство `allowBackup` не выключает: что не переносится, решает `res/xml/data_extraction_rules.xml` (`dataExtractionRules`). Его исключения (одни и те же в `<cloud-backup>` и `<device-transfer>`):

| Домен | Путь | Что это |
|---|---|---|
| `sharedpref` | `.` | все `SharedPreferences`: токен (`auth`) и настройки (`settings`) |
| `file` | `jam-guest.json`, `jam-guest.json.new` | членства гостя с секретами входа (`JamFile.GUEST`); `.new` — недописанная копия `AtomicFile` |

`queue.json` и `jam.json` при переносе на Android 12+ уезжают на новое устройство. В `jam.json` — `hostSecret` и `joinUrl` с секретом входа идущего джема (`jam/README.md`, HOST-22).

**Traps:**
- Новый файл с токеном или секретами нужно добавить в обе секции `data_extraction_rules.xml`: `allowBackup="false"` его от переноса на Android 12+ не спасёт. Путь — без масок, поэтому временные файлы `AtomicFile` (`<имя>.new`) перечисляются отдельно.

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
| `jam/server` | string, адрес сервера джема (`https://jam.example.org`) | `BuildConfig.JAM_URL` из Gradle-свойства `qiyaaJamUrl`: `https://qiyaa.kanstancin.net` в `gradle.properties`, сборка может передать другое (`-PqiyaaJamUrl=…`) | `jamServer` |
| `jam/waveFeedback` | bool, фидбек ротора в сессию волны джема — волны по истории базы во время джема (HOST-16) | `true` | `jamWaveFeedback` |
| `jam/shareAudio` | bool, гости могут слушать файлы хозяина (`spec/jam/listen.md`) | `false` | `jamShareAudio` |
| `language` | string (`AppLanguage.tag`: `be`, `ru`, `en`) | `be` (и любое другое значение) | `language` |
| `playback/quality` | string (`AudioQuality.key`: `standard`, `economy`), качество на Wi-Fi и другой безлимитной сети | `standard` (и любое другое значение) | `quality` |
| `playback/qualityMetered` | string, то же на мобильной и другой лимитной сети (QUAL-07) | `standard` (и любое другое значение) | `qualityMetered` |
| `playback/continueWithVibe` | bool, новые очереди без своего продолжения идут дальше волной по истории (CONT-01, `spec/player/continuation.md`) | `true` | `continueWithVibe` |
| `local/permissionAsked` | bool, спрашивало ли приложение разрешение на чтение аудио хоть раз: без него отказ без пояснения — первый, с ним — навсегда (LOCAL-10, `ui/LocalAccessRule`) | `false` | `localPermissionAsked`, `setLocalPermissionAsked()` |
| `shuffle/algorithm` | string (`ShuffleChoice.key`: `without-repeats`, `random`), алгоритм shuffle (SHUF-09, SHUF-10); блок «Случайный порядок» в библиотеке | `without-repeats` (и любое другое значение) | `shuffleAlgorithm` |
| `telemetry/enabled` | bool, отправлять ли статистику (TEL-02, `docs/telemetry.md`); переключатель внизу библиотеки | `true` | `telemetryEnabled` |
| `telemetry/machine` | string, UUID, из которого считается `machine`, — только если у телефона нет `ANDROID_ID` (`telemetry/README.md`, `MachineId`) | нет | `telemetryMachine` (не поток), `setTelemetryMachine` |
| `telemetry/firstStart` | string, дата первого запуска телеметрии `yyyy-MM-dd` (`LocalDate.toString()`) | нет | `markTelemetryFirstStart()` |

Ключи и умолчания те же, что в десктопном QiYaa, где они применимы (`playback/quality` там единственный: у ПК нет мобильной сети; `playback/continueWithVibe` там есть только в `settings.ini`, здесь — блок в библиотеке; `shuffle/algorithm` у десктопа — меню «Шафл», `application.cpp` в `8cfe9f3`, строки 226–240). Какое из двух качеств действует, решает `AppGraph` по `NetworkMonitor.metered` (`AppGraph.qualityInEffect`, `playback/README.md`, кэш ссылок); `data` про сеть не знает. `jam/server`
обрезается по краям при записи; ключ `jam/hostKey` из 0.2.3 (ключи хозяина отменены) `Settings` удаляет при создании; адрес по умолчанию передаёт `AppGraph` в конструктор, потому что
`data` не видит `BuildConfig`. `setEq(settings, presetName)` пишет четыре ключа одной транзакцией. Строка полос с числом элементов ≠ 10 или с нечисловыми значениями читается как плоский EQ.

Ключи `telemetry/*` те же, что у десктопа. Без сборки с телеметрией (`-PqiyaaWithTelemetry=true`, `docs/release.md`) их никто не пишет и не читает. `telemetry/machine` пишется через `apply()` один раз, когда `MachineId.resolve` не нашёл ни `ANDROID_ID`, ни сохранённого id. `markTelemetryFirstStart()` пишет дату, только если ключа ещё нет, через `edit(commit = true) { … }`: запись синхронная, чтобы убитый первый запуск не сделал первым и следующий. Зовёт его `AppTelemetry` на `Dispatchers.IO`. Результат `commit()` не проверяется: lint `UseKtx` (AGP 9) требует `edit { … }` из core-ktx, а оно результата не возвращает. Если запись не удалась, ничего не пишется в logcat, и следующий запуск снова посчитается первым.

`wasEmpty` — был ли файл `settings` пустым при создании `Settings` (до удаления `jam/hostKey`): поле `firstRun` события `start`, как `settings.allKeys().isEmpty()` у десктопа. Пользователь, который ничего не менял, оставил бы файл пустым навсегда; первая запись `telemetry/firstStart` это исправляет.

`Settings.storedLanguage(context)` читает язык без графа: `AppLocale` зовёт его в `attachBaseContext`, раньше `QiYaaApp.onCreate`.

**Traps:**
- Значения зажимаются на записи (`setVolume`, `setBalance`), но не на чтении: чужое значение в файле уйдёт в поток как есть.
- `equalizer/preamp` хранится как `float`, а `EqSettings.preampDb` — `Double`.

## `TokenStore`

Файл `auth`, ключ `token`. `load()` возвращает `""`, если токена нет; `clear()` удаляет ключ. Пустая строка означает «не вошли».

## `QueueFile`

Файл `filesDir/queue.json` через `android.util.AtomicFile`: запись во временную копию и переименование, поэтому выгрузка процесса посреди записи оставляет прежний файл. `read()` возвращает `null`, если файла нет или он не читается; `write` бросает `IOException`. Содержимое — текст, формат которого знает только `queue/QueueSnapshotCodec` (см. `queue/README.md`); `AppGraph` оборачивает `QueueFile` в `queue.QueueStore`, потому что `data` не зависит от `queue`. Пишется с потока `Dispatchers.IO`.

## `JamFile`

```kotlin
class JamFile(context: Context, name: String = HOST) {
    fun read(): String?;  fun write(text: String?)   // null удаляет файл
    companion object { const val HOST = "jam.json"; const val GUEST = "jam-guest.json" }
}
```

Как `QueueFile`, только файл `filesDir/<name>`, а `write(null)` удаляет его. Два файла:

| Имя | Что в нём | Формат | `write(null)` |
|---|---|---|---|
| `jam.json` (`HOST`) | сессия хозяина джема | `jam/JamSessionCodec` | джем закончился |
| `jam-guest.json` (`GUEST`) | членства гостя в чужих комнатах и открытая сессия | `jam/JamGuestCodec` | выход из аккаунта |

`AppGraph` оборачивает каждый файл в `jam.JamStore`. В `jam-guest.json` есть секреты (`joinSecret`, `participantId`):
он не уходит ни в облачную копию, ни на новое устройство; `jam.json` при переносе на Android 12+ уезжает
(«Резервное копирование» выше).

## `AccentTheme`, `VisualizerMode`, `AudioQuality`, `ShuffleChoice`

Все четыре enum хранятся по явному значению (`key`, `storedValue`), а не по `ordinal`; `AccentTheme` и `VisualizerMode` умеют `next()` по кругу. Неизвестное сохранённое значение — `AccentTheme.DEFAULT` (`AMBER`), `VisualizerMode.SPECTRUM`, `AudioQuality.STANDARD` (так `lossless` из будущей версии прочитается как стандарт) и `ShuffleChoice.WITHOUT_REPEATS` (SHUF-10). `ShuffleChoice` — выбор пользователя; в `queue.ShuffleAlgorithm` его переводит `AppGraph` (`data` и `queue` друг друга не импортируют): он подписан на `shuffleAlgorithm` на главном потоке и отдаёт каждое значение `QueueController.setShuffleAlgorithm`, первое — синхронно, ещё в `QiYaaApp.onCreate`. Выбор — настройка приложения, а не часть сохранённой очереди (`queue/README.md`, «Алгоритм shuffle»). `AudioQuality` — выбор пользователя; в `yandex.Quality` его переводит `AppGraph`, потому что `data` не зависит от `yandex`. Подписи тем — в `ui` (`AccentTheme.labelId()` → `R.string.theme_*`).

## Not here

- Что делать с токеном при 401 — `ui/AppViewModel.restoreSession`. Применение EQ к звуку — `audio/EqualizerDsp`.

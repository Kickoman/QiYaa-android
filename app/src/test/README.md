# `app/src/test/` — JVM-тесты

JUnit 4 без эмулятора: `./gradlew testDebugUnitTest`. Сетевые классы тестируются против `MockWebServer`, DSP — синтезированными сигналами. Имена тестов — предложения о поведении в обратных кавычках.

| Файл | Проверяет |
|---|---|
| `yandex/YandexApiTest.kt` | каждый эндпоинт `Library`/`YandexApi` против mock-сервера: заголовки, формы, конверт, ошибки, чанки по 250, `tokenRejections`, отказ `/users/…` без аккаунта (порт `tests/test_library.cpp`) |
| `yandex/DeviceAuthTest.kt` | опрос `/token`, `error_description`, истечение кода |
| `yandex/TokenNormalizerTest.kt` | токен из строки, JSON, URL, `OAuth ` |
| `yandex/TrackParsingTest.kt` | `parseTrack`, обёртки `{"track": …}`, `formatSeconds` |
| `yandex/TrackUrlTest.kt` | подпись ссылки (эталонный md5 из Yaamp), выбор варианта, `idString` |
| `yandex/ErrorsTest.kt` | дерево исключений: `IOException`, `isTokenRejected`, причина, `NotSignedInException` |
| `yandex/SessionTest.kt` | состояния сессии на фейковом `AccountGateway` и виртуальном времени: офлайн-старт, backoff 2…60 с, сброс по возврату сети, 401 и `AuthException` → `Expired`, `tokenRejections`, `signIn`/`signOut` |
| `audio/EqualizerTest.kt` | пресеты Winamp, тождество плоского EQ, АЧХ в центре полосы, усиление синуса на 6 дБ |
| `audio/AnalyzerTest.kt` | 0 dBFS для синуса полной шкалы, полоса спектра для 1 кГц, спад 0.07/кадр |
| `audio/VisualizerTapTest.kt` | порядок чтения, моно → стерео, проверка ёмкости |
| `audio/Pcm16Test.kt` | шкала и клиппинг |
| `data/VisualizerModeTest.kt` | сохранённые значения `VisualizerMode` и `AccentTheme` |
| `ui/FormatTest.kt` | `formatTime`, `balanceLabel`, `formatReadout`, `formatDb` |

Не тестируются на JVM: `AppViewModel`, `PlayerViewModel`, `PlaybackService`, `QueueManager` (зависят от Android/Media3). Их поведение описано в `playback/README.md` и `ui/README.md`; изменения там проверяются вручную по чек-листу из корневого `README.md`.

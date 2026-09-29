# `app/src/test/` — JVM-тесты

JUnit 4 без эмулятора: `./gradlew testDebugUnitTest`. Сетевые классы тестируются против `MockWebServer` на общих фикстурах из `spec/`, DSP — синтезированными сигналами. Имена тестов — предложения о поведении в обратных кавычках.

## Общие фикстуры (`spec/`)

Тесты API отдают mock-серверу ответы из `spec/fixtures/yandex/<endpoint>/<case>.json` и сравнивают разобранное с `spec/expected/yandex/<endpoint>/<case>.json` (формат — `spec/fixtures/yandex/README.md`, `spec/expected/yandex/README.md`). JSON-ответов внутри кода тестов нет.

| Файл | Содержит |
|---|---|
| `support/SpecFixtures.kt` | `Spec.fixture(endpoint, case)` — тело и HTTP-статус из имени случая (`401-…` → 401), `Spec.expected(…)`, `Spec.cases(endpoint)`; `Fixture.response()` для `MockWebServer` |
| `support/SpecJson.kt` | Android-модели → нейтральный JSON спеки. `Track` не хранит `albumTitle`, `year`, `genre`: они убираются с ожидаемой стороны, а `coverUri` сравнивается как URL, построенный `TrackParsing.coverUrl` |
| `support/FakeEngine.kt` | `queue.PlayerEngine` для тестов: плейлист, курсор, конец очереди, порядок shuffle, синхронные колбэки в `QueueController`, как у ExoPlayer, включая повтор всей очереди, позицию, «назад», паузу, причину перехода (`Transition`) и флаг «играет»; при удалении текущего трека сообщает переход, как ExoPlayer. Журнал команд `commands` |
| `support/FakeMusicSource.kt` | `queue.MusicSource` с ответами-лямбдами (в том числе отложенными через `CompletableDeferred`), журнал вызовов и отметок `/play-audio` |
| `support/QueueHarness.kt` | контроллер + фейки на виртуальном времени `runTest`, собранные события `events`, `track()`/`tracks()` |

Путь к `spec/` передаёт Gradle: системное свойство `qiyaa.spec.dir` в `app/build.gradle.kts`, а файлы спеки объявлены входами задачи тестов, так что правка спеки перезапускает тесты. Без `git submodule update --init` тесты падают с подсказкой.

Расхождение с десктопом не подгоняется: тест пишется по спеке, помечается `@Ignore("Known divergence Kickoman/QiYaa-android#N: …")`, и на расхождение заводится задача. Сейчас так помечены #33 (статус в ошибке OAuth) и #34 (`bestType` = `other`).

**Traps:**
- Эндпоинты, которых в Android ещё нет (`landing3`, `users-playlists-recommendations`, `wheel-new`, `rotor-*-feedback`), тестируются вместе со своими задачами (#31, #32, #20, #26). Их тела ошибок уже используются как подставные: спека разрешает любой фикстуре ошибки стоять за любой эндпоинт.
- В `resolveTrackUrl` хост `storage.mds.yandex.net` из фикстуры подменяется адресом mock-сервера строковой заменой: сам JSON не меняется.

## Файлы

| Файл | Проверяет |
|---|---|
| `yandex/YandexApiTest.kt` | каждый эндпоинт `Library`/`YandexApi` на фикстурах спеки: результат разбора против `expected/`, заголовки, формы, все формы ошибок (401, 500 без тела, 404, 503 строкой), чанки по 250, `tokenRejections`, отказ `/users/…` без аккаунта |
| `yandex/DeviceAuthTest.kt` | фикстуры `oauth-*`: код устройства, `authorization_pending` → опрос до токена, `error_description`; истечение кода |
| `yandex/TokenNormalizerTest.kt` | токен из строки, JSON, URL, `OAuth ` |
| `yandex/TrackParsingTest.kt` | все фикстуры `tracks/*` против `expected/`, `formatSeconds` |
| `yandex/TrackUrlTest.kt` | фикстуры `tracks-download-info/*` (варианты и выбор) и `storage-download-info/*` (разбор и подписанная ссылка, XML → невалидно); эталонный md5 из Yaamp, `idString` |
| `yandex/ErrorsTest.kt` | дерево исключений: `IOException`, `isTokenRejected`, причина, `NotSignedInException` |
| `yandex/WaveFeedbackTest.kt` | TRK-03…11 на уровне `Library.waveFeedback`: поля событий, тело сессии и `batchId`, запасной путь через станцию при 4xx и запоминание сессии, без станции — ничего, 5xx и сеть не повторяются |
| `yandex/SessionTest.kt` | состояния сессии на фейковом `AccountGateway` и виртуальном времени: офлайн-старт, backoff 2…60 с, сброс по возврату сети, 401 и `AuthException` → `Expired`, `tokenRejections`, `signIn`/`signOut` |
| `playback/PlaybackFailuresTest.kt` | вид ошибки воспроизведения: коды 2001/2002, `NetworkException` (в том числе обёрнутый), `UnknownHost`, 401 → сессия, 2004/404/нет вариантов/декодер → трек |
| `queue/ErrorPolicyTest.kt` | `decide` по всем веткам (лимит 3 подряд, последний трек); `awaitRetry`: возврат в момент появления сети, паузы 2…60 с при живой сети |
| `queue/PlayOrderTest.kt` | остаток после текущего трека по порядку воспроизведения, в том числе перемешанному |
| `queue/WaveModeRuleTest.kt` | режим (shuffle, повтор) выключен в волне, выбор пользователя запоминается и возвращается для обычных очередей |
| `queue/QueueSourcesTest.kt` | SRC-01…12: побеждает последний источник, недоступные треки, пустой источник и источник только из недоступных, маршруты поиска |
| `queue/QueueWaveTest.kt` | WAVE-01…08, 10…12: старт, догрузка с последними 5 id, один запрос за раз, поколения, продолжение после конца, shuffle и повтор в волне; пустая первая порция (WAVE-03), молчаливый ответ для заменённой очереди (WAVE-07), «вперёд» в конце после неудачи (WAVE-09) |
| `queue/QueueErrorsTest.kt` | ERR-01…07: пауза без сети и возврат, повторы 2/4/8 с, пропуск битого трека и стоп на третьем, сброс счётчика; битый последний трек волны ждёт догрузку (ERR-07) |
| `queue/QueueEditingTest.kt` | удаление выбранных, выбрать всё, очистка, лайк, дизлайк (TR-07) |
| `queue/QueueTrackingTest.kt` | TRK-01, TRK-02: `/play-audio` при реальном старте, не при постановке в очередь; пауза и перемотка — не старт; битая ссылка — не старт; рестарт, повтор одного трека, «плей» после стопа и конца — новый `play-id` |
| `queue/PlayTrackerTest.kt` | старт трека, закрытие (дослушан/пропущен), секунды только при игре (TRK-08) |
| `queue/QueueFeedbackTest.kt` | TRK-03…08 на уровне очереди: `radioStarted` первым, `batchId` порции трека, `trackFinished` с секундами, `skip` для «вперёд», рестарта, «стоп», удаления, новой очереди и дизлайка, события в свою сессию после смены волны, без фидбека для обычной очереди и пустой волны |
| `queue/QueueTransportTest.kt` | TR-01…05 и WAVE-09 через `next()`/`previous()`; новый движок получает ту же очередь на паузе на том же треке и позиции; источник, выбранный до движка, применяется при подключении; очистка без движка |
| `audio/DspVectorsTest.kt` | эталоны `spec/dsp`: АЧХ EQ (50 случаев × 41 частота, 1e-4 дБ), центры полос и Q, 17 пресетов, `levelToDb` для уровней 0…65, границы 19 полос и один кадр спектра для 24 синусов (1e-3). Из `eqf.json` не проверяются байты файла и `dbToLevel`: `.eqf` на Android n/a |
| `audio/EqualizerTest.kt` | пресеты Winamp, тождество плоского EQ, АЧХ в центре полосы, усиление синуса на 6 дБ |
| `audio/AnalyzerTest.kt` | 0 dBFS для синуса полной шкалы, полоса спектра для 1 кГц, спад 0.07/кадр |
| `audio/VisualizerTapTest.kt` | порядок чтения, моно → стерео, проверка ёмкости |
| `audio/Pcm16Test.kt` | шкала и клиппинг |
| `data/VisualizerModeTest.kt` | сохранённые значения `VisualizerMode` и `AccentTheme` |
| `ui/FormatTest.kt` | `formatTime`, `balanceLabel`, `formatReadout`, `formatDb` |

Не тестируются на JVM: `AppViewModel`, `PlayerViewModel`, `PlaybackService`, `Media3Engine` (зависят от Android/Media3; `Media3Engine` только переводит вызовы, логика — в `QueueController`, который тестируется с `support/FakeEngine`). Их поведение описано в `playback/README.md` и `ui/README.md`; изменения там проверяются вручную по чек-листу из корневого `README.md`.

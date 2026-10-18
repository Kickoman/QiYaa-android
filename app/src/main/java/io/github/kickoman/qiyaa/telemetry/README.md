# `telemetry/` — анонимная статистика и отчёты о падениях

Пакет делает то, что описывает `spec/telemetry/README.md` для Android (Kickoman/QiYaa-android#75): копит события (`start`, `exit`, `crash`, `unclean_exit`, `error`, `feature`) в файлах, пишет отчёт о падении из обработчика непойманных исключений, по следующему запуску решает, чем кончился прошлый (TEL-16), и отправляет пачки на `POST <сервер>/api/telemetry` с ответами и паузами из таблицы спеки (TEL-04). Здесь же расписание отправки и выключатель (TEL-02, TEL-03) — на потоках и виртуальном времени, чтобы их проверял JVM-тест. Пакет **не** знает ни плеера, ни очереди, ни настроек, ни Android: события ему передаёт корневой `AppTelemetry` (сигналы платформы, `queue.activity`, `yandex.requestFailures`, `playback.audioFailure`), он же читает `ANDROID_ID`, причины выхода прошлых процессов (Android 11+) и состояние для `start`. Коды ошибок и функций из типов приложения собирает корневой `TelemetryCodes`. Настройки (`telemetry/enabled`, `telemetry/machine`) — `data/README.md`, переключатель — `ui/README.md`, что и зачем отправляется — `docs/telemetry.md`.

Чистая JVM, не импортирует ни Android, ни другие пакеты приложения, и адреса сервера в коде нет: его даёт `BuildConfig` через корень (TEL-10).

```bash
grep -rlnE --include='*.kt' '^import (android|androidx)' app/src/main/java/io/github/kickoman/qiyaa/telemetry/                      # ничего не печатает
grep -rlnE --include='*.kt' 'qiyaa\.(yandex|audio|data|jam|queue|playback|ui)\.' app/src/main/java/io/github/kickoman/qiyaa/telemetry/  # ничего не печатает
grep -rnE --include='*.kt' 'https?://' app/src/main/java/io/github/kickoman/qiyaa/telemetry/                                         # ничего не печатает
```

Греп обходит папку с подпапками, но читает только `*.kt` (`--include`): в этом README есть пример кадра `io.github.kickoman.qiyaa.queue.…`, и греп по всем файлам нашёл бы его.

Ещё один инвариант, который не проверить grep'ом: файлы пакет читает и пишет только внутри `withContext(io)` и в обработчике падения. Ни один публичный метод `Reporter` и `TelemetrySession`, который зовут с главного потока, не трогает диск и сеть.

| Файл | Содержит |
|---|---|
| `MachineId.kt` | `MachineId` — `machine` из `ANDROID_ID` или сохранённого UUID |
| `Event.kt` | `Event` и его шесть видов, `ErrorArea`, `FeatureName`, `StartFields` (с перечислениями полей `start`), `Counts` |
| `EventCodec.kt` | `EventCodec` — событие в JSON спеки, пачка, запасные значения для каждого свободного поля |
| `CrashReport.kt` | `CrashReport` — отчёт о падении: из исключения, в текст `key=value` и обратно |
| `CrashHandler.kt` | `CrashHandler` — `Thread.UncaughtExceptionHandler`, который пишет `CrashReport` и всегда зовёт прежний обработчик |
| `TelemetryStore.kt` | `TelemetryStore`, `Marker` — файлы запусков в папке телеметрии |
| `RunEnd.kt` | `ExitReason`, `ExitInfo`, `RunEnd` — чем кончился прошлый запуск (TEL-16) |
| `Sender.kt` | `Answer`, `Backoff`, `Sender` — POST пачки и что значит ответ |
| `Reporter.kt` | `Reporter` — один запуск: очередь событий, счётчики, пачки, забыть всё |
| `TelemetrySession.kt` | `Beginning`, `TelemetrySession` — расписание отправки и включение/выключение |

Зависимости внутри пакета: `TelemetrySession → Reporter → (TelemetryStore, CrashHandler, RunEnd, EventCodec, Answer, Backoff)`, `CrashHandler → CrashReport → EventCodec → Event`. `Sender` в `Reporter` не зашит: тот получает функцию `post: suspend (String) -> Answer`, в приложении — `Sender(httpClient, BuildConfig.TELEMETRY_URL)::post`, в тестах — `MockWebServer` или подделка.

## `MachineId`

```kotlin
object MachineId {
    const val KEY = "QiYaa telemetry"
    fun of(systemId: String): String          // первые 16 байт HMAC-SHA256(KEY, UTF-8 id) в hex, 32 знака
    fun resolve(androidId: String?, stored: String?, store: (String) -> Unit, newId: () -> String = UUID): String
}
```

`resolve`: непустой `ANDROID_ID` — его хэш, ничего не сохраняется; иначе непустой сохранённый id (`telemetry/machine`) — его хэш; иначе новый UUID сохраняется через `store` один раз и хэшируется так же. Сам `ANDROID_ID` нигде не хранится и не отправляется. Векторы: `of("abc") = 3a8271ff2b263f300989f47f507a3804` (десктоп), `of("3f2b9c1d7a6e5048") = 97eaa8cc2fbe9a456389e6bd9d44a3fb` (примеры спеки).

**Traps:** `ANDROID_ID` свой у каждого ключа подписи: debug- и release-сборки одного телефона — две машины, сброс к заводским настройкам — новая. Чтение `Settings.Secure.ANDROID_ID` в корне требует `@SuppressLint("HardwareIds")` с причиной в той же строке: lint идёт с `warningsAsErrors`.

## `Event`, `EventCodec`

```kotlin
sealed interface Event { val at: Instant; val session: String; val version: String
    Start(fields: StartFields); Exit(counts: Counts); Crash(signal, frames, seconds, exceptionType?)
    UncleanExit(seconds); Error(area: ErrorArea, kind: String, httpStatus: Int? = null); Feature(name: FeatureName, value: String) }
enum class ErrorArea(code) { PLAYBACK, AUDIO, API, LOGIN, JAM }          // skin и milkdrop у Android нет
enum class FeatureName(code) { JAM, LANGUAGE, EQUALIZER, VIS }
data class StartFields(osVersion, sdk, device, kernel, arch, packaging: Packaging, locale, language: Language,
    dpr, theme: Theme, vis: Vis, equalizer, quality: Quality, qualityMetered: Quality, loggedIn, firstRun)
data class Counts(seconds, tracks, playMinutes, jams, errorsDropped)

object EventCodec {
    fun encode(event: Event): JsonObject?           // null: session не UUID v4 или значение feature вне шаблона
    fun batch(machine: String, lines: List<String>): String   // {"app":"android","machine":…,"events":[…]}
    fun at(instant: Instant): String                // yyyy-MM-dd'T'HH:mm:ss.SSS'Z', UTC
    fun version / device / text / arch / locale / dpr / kind / featureValue / httpStatus / signal / exceptionType / frame / count
}
```

Поля и их имена — ровно `androidStart`, `androidExit`, `androidCrash`, `unclean_exit`, `error`, `feature` из `spec/telemetry/schemas/batch.schema.json`. Перечисления `StartFields` (`Packaging` `apk`/`debug`, `Language`, `Theme`, `Vis`, `Quality`) хранят код спеки, поэтому их значение всегда допустимо.

Один неверный элемент даёт 400, а 400 — это «выбросить пачку», вместе с отчётами о падениях. Поэтому `encode` сам приводит каждое свободное поле к схеме, а то, что привести нельзя, не кодирует:

| Поле | Что делает `encode` |
|---|---|
| `device` | только печатный ASCII (0x20–0x7e), обрезка пробелов, ≤ 64; пусто — `unknown` |
| `osVersion`, `kernel` | без управляющих символов (U+0000–U+001F, U+007F), ≤ 32 и ≤ 64 знаков UTF-16 без разрезанной суррогатной пары; пусто — `unknown` |
| `arch` | строчными, `-` → `_`, только `[a-z0-9_]`, ≤ 16; пусто — `unknown` (известные ABI переводит `TelemetryCodes`: `arm64-v8a` → `arm64`) |
| `locale` | подходит под шаблон — как есть; иначе `язык_РЕГИОН` из частей через `_` или `-` (`sr_Latn_RS`, `sr_RS_#Latn` → `sr_RS`, `en-US` → `en_US`); без языка `[a-z]{2,3}` — `C` |
| `dpr` | в пределах 0,5–8; `NaN` и бесконечность — 1,0 |
| `sdk` | в пределах 1–1000 |
| `version` | недопустимые знаки → `_`, ≤ 32; пусто — `unknown` |
| `kind` | вне `^[a-z0-9-]{1,32}$` — `other` |
| `httpStatus` | вне 100–599 — поля нет |
| `value` у `feature` | вне `^[A-Za-z0-9 ._-]{1,48}$` — событие не кодируется (`null`), `Reporter` его не записывает |
| `signal` | вне шаблона — `UNKNOWN` |
| кадр | знаки вне `A-Za-z0-9_$.<>-` до последнего `:` → `_`, место обрезается до 200, строка вне `[0-9]{1,7}` → `0`, суффикс `:N` остаётся; не подошло и так — кадр выброшен; не больше 32 |
| `exceptionType` | вне `^[A-Za-z_$][A-Za-z0-9_$.]{0,159}$` — поля нет |
| счётчики | в пределах 0–100 000 000 |
| `session` | не UUID v4 — событие не кодируется |

`BatchSchemaTest` подставляет недопустимые значения в каждое из этих полей по очереди и проверяет пачку валидатором `com.networknt:json-schema-validator` (только в тестах) по схеме спеки. Валидатор ловит только недопустимое, а выброшенный кадр пачку не портит, поэтому обрезку кадра до 200 с `:N` тесты проверяют отдельно: `EventCodec.frame` напрямую (`CrashReportTest`) и длинный кадр в отправленном `crash` (`BatchSchemaTest`).

## `CrashReport`, `CrashHandler`

```kotlin
data class CrashReport(session, version, signal = "TERMINATE", frames: List<String> = [], seconds = 0, exceptionType: String? = null) {
    fun text(): String;  fun event(at: Instant): Event.Crash
    companion object {
        fun of(thrown: Throwable, session: String, version: String, seconds: Long): CrashReport
        fun frame(element: StackTraceElement): String     // "<class>.<method>:<line>", line ≤ 0 → 0
        fun read(text: String): CrashReport?
        const val MAX_CHARS = 64 * 1024
    }
}
class CrashHandler(file: File, session: String, version: String, uptimeMs: () -> Long) : Thread.UncaughtExceptionHandler {
    fun install();  fun uninstall()
}
```

`of` идёт по цепочке `cause` (без зацикливания, по идентичности) до последнего звена — корневой причины. `exceptionType` — её класс (`javaClass.name`, вложенные через `$`), кадры — её `stackTrace`; если у корня кадров нет (`writableStackTrace = false`), берутся кадры ближайшего внешнего исключения, у которого они есть. Не больше 32 кадров сверху стека, каждый через `EventCodec.frame`. Текст исключения (`message`, `toString()`) не читается вообще.

Формат файла `<session>.crash` — строки `key=value`, как у десктопа:

```
session=5b0f2e7c-91d4-4c3a-b8e6-2a7d9f1c0e53
version=0.6.0
signal=TERMINATE
seconds=5423
exception=java.lang.IllegalStateException
frame=io.github.kickoman.qiyaa.queue.QueueController.onFailure:291
frame=…
```

`read` возвращает `null` для пустого текста, текста длиннее 64 KiB и текста, где `session`, `version` или `signal` не подходят под шаблоны схемы. Неизвестные ключи и строки без `=` пропускаются; `exception` вне шаблона опускается, неподходящий кадр выбрасывается, `seconds` < 0 или не число — 0.

`CrashHandler.install` запоминает текущий обработчик по умолчанию и ставит себя. На непойманное исключение первый вызов (`AtomicBoolean`) пишет отчёт: `<session>.crash.tmp`, затем переименование в `<session>.crash`; папка создаётся здесь же. Всё это — внутри `catch (Throwable)`, и после него **всегда** вызывается прежний обработчик (на Android — системный, который убивает процесс и показывает диалог), даже если запись не удалась или это второе падение. `seconds` — `uptimeMs() / 1000` в момент падения: в приложении это время с запуска процесса. `uninstall` выключает запись и возвращает прежний обработчик, только если по умолчанию всё ещё стоит этот; если кто-то поставил свой поверх, этот остаётся в цепочке и только передаёт исключение дальше.

**Traps:**
- Обработчик один на процесс. Новый `Reporter` ставит свой только после `forget()` старого (`TelemetrySession` делает это по порядку); иначе прежний писал бы отчёт за забытый запуск.
- Без вызова прежнего обработчика приложение после исключения зависло бы, а не упало. Тест `the previous handler runs even when the report cannot be written` это держит.
- Обработчик пишет синхронно на потоке падения, в том числе на главном: это единственная запись на диск вне `io`, и она — одна небольшая.
- Release-сборки минифицирует R8 (Kickoman/QiYaa-android#22): кадры приложения и библиотек придут как `<класс R8>.<метод R8>:<позиция>`, их переводит `mapping.txt` этого релиза (рецепт — в `docs/telemetry.md` и `spec/telemetry/README.md`). Чтобы `exceptionType` читался без этого, `app/proguard-rules.pro` держит имена всех наследников `Throwable` (`-keepnames class * extends java.lang.Throwable`). Правило только запрещает переименование: R8 по-прежнему выкидывает и оптимизирует эти классы, а имена сохраняет у 114 классов приложения и библиотек, с телеметрией и без (R8 Configuration Analyzer, `./gradlew :app:analyzeReleaseR8Config`, 2026-10-18).

## `TelemetryStore`

```kotlin
data class Marker(version: String, counts: Counts = Counts())
class TelemetryStore(directory: File) {
    fun sessions(): List<String>                 // запуски с файлами, от старых к новым
    fun readQueue(session): List<String>;  fun writeQueue(session, lines: List<String>)   // пустой список удаляет файл
    fun readMarker(session): Marker?;  fun writeMarker(session, marker: Marker)
    fun readCrash(session): CrashReport?;  fun crashFile(session): File
    fun remove(session);  fun removeAll()
}
```

Папка в приложении — `noBackupFilesDir/telemetry/` (не попадает в резервную копию). Файлы запуска:

| Файл | Содержимое |
|---|---|
| `<session>.queue` | события, ждущие отправки, по одному JSON-объекту в строке, от старых к новым; события прошлых запусков тоже здесь |
| `<session>.json` | маркер запуска: `{"version":"0.6.0","seconds":120,"tracks":3,"playMinutes":7,"jams":0,"errorsDropped":0}`; `seconds` — время в использовании, `playMinutes` — минуты воспроизведения |
| `<session>.crash` | отчёт о падении (`CrashReport.text()`) |

`<session>` — UUID v4 строчными; `sessions()` видит только имена `^<uuid>\.(queue|json|crash)$` и сортирует запуски по самому старому времени изменения их файлов, при равенстве — по имени. Запись — во временный `<имя>.tmp` и переименование; `IOException` записи уходит вызывающему, чтение при ошибке даёт `null` или пустой список. Строка очереди, которая не JSON-объект, пропускается; маркер без `version` по шаблону — `null`. Блокировок между процессами нет: процесс приложения один.

## `RunEnd`

```kotlin
enum class ExitReason { CRASH, NATIVE_CRASH, ANR, OTHER }
data class ExitInfo(reason: ExitReason, signal: Int = 0)
object RunEnd {
    fun event(session: String, marker: Marker?, crash: CrashReport?, exit: ExitInfo?, at: Instant): Event?
    fun signalName(signal: Int): String   // 11 → SIGSEGV, 6 → SIGABRT…, иначе SIGNAL_<n>
}
```

| Отчёт о падении | `ExitInfo` (Android 11+, иначе `null`) | Маркер | Событие |
|---|---|---|---|
| есть | любой | любой | `crash` из отчёта (`seconds` — время процесса) |
| нет | `NATIVE_CRASH`, `signal` | есть | `crash`: имя сигнала, `frames: []`, `seconds` маркера |
| нет | `ANR` или `CRASH` (обработчик не успел) | есть | `unclean_exit` с `seconds` маркера |
| нет | `OTHER` или `null` | есть | `exit` со счётчиками маркера |
| нет | любой | нет | ничего |

`ExitInfo` строит корень из `ApplicationExitInfo` (`TelemetryCodes`): процесс помечает себя своим `session` через `setProcessStateSummary`, следующий запуск сопоставляет по нему.

## `Sender`, `Answer`, `Backoff`

```kotlin
sealed interface Answer { Accepted(status); Dropped(status); RetryAfter(seconds); Failed(status: Int?)
    companion object { fun of(status: Int?, retryAfter: String?): Answer; DEFAULT_RETRY_SECONDS = 60; MAX_RETRY_SECONDS = 3_600 } }
object Backoff { fun seconds(failures: Int): Long }   // 60, 120, 240 … не больше 3600
class Sender(client: OkHttpClient, endpoint: String) { suspend fun post(body: String): Answer }
```

| Ответ | `Answer` | `Reporter` |
|---|---|---|
| 2xx | `Accepted` | убирает отправленные события; если ещё есть — сразу следующая пачка |
| 400, 413 | `Dropped` | убирает их же, следующую пачку не шлёт |
| 429 | `RetryAfter(n)` | ждёт `Retry-After` секунд, целое > 0, не больше 3600; иначе 60 |
| другое, нет ответа | `Failed` | ждёт `Backoff.seconds(подряд неудач)`: 1 мин, удваивая до 1 ч |

`post` — `POST` на `endpoint` с `Content-Type: application/json` (ровно так, без `charset`) и телом в UTF-8, через `enqueue` OkHttp, то есть не на вызывающем потоке. Отмена корутины отменяет и `Call`, так что `forget()` не ждёт тайм-аута клиента. `endpoint` разбирается в конструкторе: не URL — `IllegalArgumentException` сразу.

## `Reporter`

```kotlin
class Reporter(version: String, store: TelemetryStore, post: suspend (String) -> Answer, scope: CoroutineScope,
    io: CoroutineContext, now: () -> Instant, uptimeMs: () -> Long, log: (String) -> Unit = {}, val session: String = UUID v4) {
    val queued: Int
    fun install()                                   // обработчик падения; синхронно
    suspend fun begin(start: StartFields, machine: String, exits: Map<String, ExitInfo>)
    fun recordError(area: ErrorArea, kind: String, httpStatus: Int? = null)
    fun recordFeature(name: FeatureName, value: String)
    fun countTrack();  fun countJam();  fun setPlaying(on: Boolean);  fun setInUse(on: Boolean)
    fun saveMarker()
    suspend fun send(): Answer?                     // null: ничего не отправлено
    suspend fun forget()
    companion object { MAX_QUEUED_EVENTS = 500; MAX_BATCH_EVENTS = 50; MAX_BATCH_BYTES = 60 * 1024; MAX_ERRORS_PER_SESSION = 50 }
}
```

Состояние — в памяти под одной блокировкой; методы без `suspend` дешёвы, не бросают и годятся для главного потока. Файлы пишет один писатель на `io`, которого будит `Channel(CONFLATED)`; он берёт последнее состояние очереди и маркера под блокировкой и пишет целиком. Таймеров внутри нет: когда сохранять и отправлять, решает `TelemetrySession`.

- **`begin`** (на `io`): каждый прошлый запуск из `sessions()` — его очередь, затем событие `RunEnd` с `exits[session]`; потом `start` этого запуска (`at` — сейчас), потом то, что записали до `begin` (TEL-04, TEL-05: всё прошлое — до своего `start`). Затем свои `<session>.queue` и `<session>.json` (нулевой маркер — по нему следующий запуск отправит `exit`), и только после этого файлы прошлых запусков удаляются: упавшая запись оставит их следующему запуску.
- **До `begin`** события копятся в памяти (не больше 500), `send()` ничего не делает, `saveMarker()` тоже.
- **Ошибки**: первые 50 за запуск записываются, остальные только считаются в `errorsDropped` (TEL-07).
- **Очередь**: больше 500 — выбрасываются самые старые, но не те, что сейчас в пути (TEL-04). У каждого события свой порядковый номер; пачка помнит номера, и ответ убирает ровно их, сколько бы ни выбросилось за время запроса.
- **`send()`**: один за раз (`Mutex.tryLock`): второй вызов во время запроса сразу возвращает `null`. Пачка — с начала очереди, не больше 50 событий и 60 KiB тела в UTF-8. Ничего не шлёт до `begin`, после `forget`, с пустой очередью и до конца паузы (`uptimeMs`). Ответ — по таблице `Sender`. После каждого ответа — строка в `log`: `HTTP 204, 3 events sent`, `HTTP 400, 3 events dropped`, `HTTP 429, 3 events kept, next try in 30 s`, `no answer, 3 events kept, next try in 60 s`; тела запроса в логе нет.
- **Маркер**: `seconds` — сумма промежутков `setInUse(true…false)`, `playMinutes` — `setPlaying` в минутах, `tracks`, `jams`, `errorsDropped`.
- **`forget()`** (TEL-02): помечает запуск забытым, отменяет запрос в пути, закрывает канал писателя и ждёт, пока тот допишет, снимает обработчик падения и под той же блокировкой файлов удаляет всё в папке — именно в таком порядке и в `withContext(NonCancellable + io)`, поэтому ни запрос, ни отложенная запись не вернут файл после удаления. После `forget()` все методы ничего не делают.

**Traps:**
- `queued` считает и события до `begin`.
- Запуск, который упал до `begin`, оставляет только `.crash` — и это всё равно `crash` (первая строка таблицы `RunEnd`).
- Если запись своих файлов в `begin` не удалась, события прошлых запусков уйдут из памяти, а их файлы останутся — следующий запуск пришлёт их второй раз. Дубликат лучше потери.

## `TelemetrySession`

```kotlin
data class Beginning(start: StartFields, machine: String, exits: Map<String, ExitInfo> = emptyMap())
class TelemetrySession(enabled: Flow<Boolean>, onScreen: Flow<Boolean>, playing: Flow<Boolean>, online: Flow<Boolean>,
    scope: CoroutineScope, io: CoroutineContext, store: TelemetryStore, newReporter: () -> Reporter,
    beginning: suspend () -> Beginning, log: (String) -> Unit = {}) {
    val reporter: Reporter?
    fun start()
    fun recordError(…);  fun recordFeature(…);  fun countTrack();  fun countJam()   // в текущий Reporter или никуда
}
```

`start()` подписывается на потоки. «В использовании» — `onScreen || playing` (спека, раздел Android).

| Когда | Что |
|---|---|
| `enabled` → `true` | новый `Reporter`, `install()` сразу, затем `beginning()` на `io`, `begin` и отправка |
| `enabled` → `false` | `forget()` текущего (TEL-02); если его нет (выключено с самого начала) — `removeAll()` на `io` |
| в использовании | каждую минуту `saveMarker()`, каждую пятую — отправка (TEL-03) |
| перестало быть в использовании | `saveMarker()` и отправка |
| `online` → `true` | отправка |
| `online == false` | отправка не вызывается вовсе, поэтому и паузы не копятся |

Отправка — `scope.launch { reporter.send() }`. Минуты считаются от начала каждого периода использования; вне использования таймера нет, и закэшированный процесс не просыпается.

**Traps:**
- `enabled` обрабатывается `collect`, а не `collectLatest`: если подписка увидела «выкл», то «вкл», пришедшее во время `forget()`, ждёт его конца, и `forget()` старого запуска всегда заканчивается до того, как новый `begin` посмотрит в папку. Иначе файлы забытого запуска ушли бы как «прошлый запуск».
- «Выкл» и сразу «вкл» подписка может не увидеть вовсе. `enabled` в приложении — `StateFlow` настройки, а он отдаёт подписчику только последнее значение: если оба переключения успели, пока подписка занята, для неё значение так и осталось `true`. Занята она в `beginning()` на `io` (сразу после включения или запуска приложения: ожидание `connect`, `commit()` первого старта, причины выхода) — миллисекунды, и запуску, которому от роду столько же. Такой запуск продолжается со своими файлами и очередью, и это принятое поведение, а не ошибка: выключатель в итоге не выключался. «Вкл» и сразу «выкл» во время `forget()` сливаются так же и ничего не меняют: запуск уже забыт. Отдельный буферизованный поток каждого переключения мимо `StateFlow` настройки ради этого окна не заведён. Тесты — на `MutableStateFlow`, как в приложении: `TEL-02 an off and on the setting coalesces into on keep the session…` и `TEL-02 an on while the off is forgetting waits for it…`.
- Подписка запускается `UNDISPATCHED`: если первое значение `enabled` — `true` (`StateFlow` настройки), обработчик падения стоит уже к возврату из `start()`, до остальной сборки графа.
- `beginning()` с исключением не роняет приложение: строка в `log`, и этот `Reporter` так и не начнётся (события копятся в памяти, ничего не уходит).

## В корне: `AppTelemetry`, `TelemetryCodes`

Не часть пакета (они знают Android и типы приложения), но описаны здесь: своего README у корня нет.

```kotlin
class AppTelemetry(application: Application, settings: Settings, httpClient: OkHttpClient, endpoint: String,
    scope: CoroutineScope) {
    fun start()                                     // обработчик падения ставится здесь же, если telemetry/enabled
    fun connect(api: YandexApi, sessionState: StateFlow<SessionState>, queue: QueueController, jamHost: JamHost,
        network: StateFlow<Boolean>)
    fun loginFailed();  fun audioFailed(failure: AudioFailure)
}
data class ErrorCode(area: ErrorArea, kind: String, httpStatus: Int? = null)
data class ProcessExit(summary: String?, reason: Int, status: Int)
object TelemetryCodes {
    fun error(kind: FailureKind / failure: AudioFailure / failure: RequestFailure): ErrorCode
    fun error(event: JamHostEvent): ErrorCode?;  val LOGIN_FAILED: ErrorCode
    fun onOff / language / theme / vis / quality / packaging / arch / locale / device
    fun exitInfo(reason: Int, status: Int): ExitInfo;  fun exits(newestFirst: List<ProcessExit>): Map<String, ExitInfo>
}
```

`AppGraph` создаёт `AppTelemetry` сразу после `settings`, только если `BuildConfig.TELEMETRY` и `BuildConfig.TELEMETRY_URL` начинается с `https://` (сборка с `-PqiyaaWithTelemetry=true`, `docs/release.md`), иначе `telemetry = null` и ничего из этого пакета не создаётся (TEL-01). `start()` зовётся тут же: при включённой настройке обработчик падения стоит раньше остальной сборки графа. `connect(…)` — в конце `init` графа, до сервиса и экранов. Входы `TelemetrySession`:

| Вход | Откуда |
|---|---|
| `enabled` | `Settings.telemetryEnabled` |
| `onScreen` | `registerActivityLifecycleCallbacks`: запущенных (`onStart` без `onStop`) активностей больше нуля; `onStop` при смене конфигурации (`isChangingConfigurations`) уходом с экрана не считается |
| `playing` | `QueueActivity.PlayingChanged` |
| `online` | `NetworkMonitor.available` |
| `store` | `File(noBackupFilesDir, "telemetry")` |
| `newReporter` | `Reporter(BuildConfig.VERSION_NAME, store, Sender(httpClient, endpoint)::post, applicationScope, Dispatchers.IO, Instant::now, { elapsedRealtime() - Process.getStartElapsedRealtime() }, logcat)`; на Android 11+ ещё `ActivityManager.setProcessStateSummary(session)` — по этой метке следующий запуск найдёт причину выхода |
| `beginning` (на `io`) | ждёт `connect` (`loggedIn` — `api.token` не пустой), `Settings.markTelemetryFirstStart()`, `MachineId.resolve(ANDROID_ID, telemetry/machine)`, на Android 11+ `getHistoricalProcessExitReasons(пакет, 0, 16)` → `TelemetryCodes.exits`, поля `start` |

Строки отправки идут в logcat с тегом `QiYaa`: `Telemetry: HTTP 204, 3 events sent`; «не начался» — предупреждением.

Поля `start`:

| Поле | Откуда |
|---|---|
| `osVersion`, `sdk` | `Build.VERSION.RELEASE`, `Build.VERSION.SDK_INT` |
| `device` | `TelemetryCodes.device(Build.MANUFACTURER, Build.MODEL)` |
| `kernel` | `System.getProperty("os.version")` |
| `arch` | `TelemetryCodes.arch(Build.SUPPORTED_ABIS[0])` |
| `package` | `apk` для `BuildConfig.BUILD_TYPE == "release"`, иначе `debug` |
| `locale` | системная: `Resources.getSystem().configuration.locales[0]`, не `Locale.getDefault()` — его `AppLocale.wrap` подменяет языком приложения |
| `language`, `theme`, `vis`, `equalizer`, `quality`, `qualityMetered` | `Settings` |
| `dpr` | `resources.displayMetrics.density` |
| `loggedIn` | `api.token` не пустой |
| `firstRun` | `Settings.wasEmpty` (`data/README.md`) |

События из типов приложения:

| Источник | Событие |
|---|---|
| `QueueActivity.TrackStarted` | `countTrack()` |
| `QueueActivity.PlayingChanged(playing)` | поток `playing` |
| `QueueActivity.PlaybackFailed(NETWORK / SESSION / TRACK)` | `error playback/network`, `auth`, `track` |
| `AudioFailure` (`PlaybackService` → `audioFailed`) `INIT` / `DEVICE` / `UNDECODABLE` | `error audio/init`, `device`, `undecodable` |
| `YandexApi.requestFailures`: `NETWORK` / `HTTP` / `CONTENT` | `error api/network`, `http` с `httpStatus`, `content` |
| `AppViewModel`: вход по коду устройства или токеном не удался; `Session.state` стал `Expired` | `error login/failed` |
| `JamHostEvent.Refused(reason)` | `error jam/<reason>`; причина вне `^[a-z0-9-]{1,32}$` — `other` |
| `JamHostEvent.Ended(GONE / EXPIRED / BY_SERVER)` | `error jam/gone`, `expired`, `ended-by-server`; `BY_HOST` — ничего |
| `JamHostEvent.NotConnected` (хозяин убирает элементы без связи, HOST-36, HOST-25) | ничего: это подсказка на экране, среди видов ошибок `jam` спеки её нет |
| `jamHost.state.phase` стал / перестал быть `ACTIVE` | `feature jam on` и `countJam()` / `feature jam off`; как у десктопа, до первого «вкл» «выкл» не шлётся |
| `Settings.language`, `eq.enabled`, `visualizerMode` — смена после старта | `feature language be/ru/en`, `equalizer on/off`, `vis spectrum/oscilloscope/off` |

`TelemetryCodes`:
- `arch`: `arm64-v8a` → `arm64`, `armeabi-v7a` → `arm`, `x86` → `i386` (как у десктопа), остальное как есть — его приводит `EventCodec.arch` (`x86_64`).
- `locale(language, country)`: `be_BY`, `es_419`; `sr-Latn-RS` даёт `sr_RS` (`Locale.country` без письменности); без страны — язык; без языка — `C`.
- `device(manufacturer, model)`: «производитель модель», но без производителя, если модель с него уже начинается (без учёта регистра): `Google Pixel 7`, `samsung SM-S911B`, `Xiaomi 13`.
- `exitInfo(reason, status)` — по числам `ApplicationExitInfo` (свои константы: `telemetry` и `TelemetryCodes` без `android.*`, а `TelemetryCodesTest` сверяет их с константами Android): 4 `REASON_CRASH` → `CRASH`, 5 `REASON_CRASH_NATIVE` → `NATIVE_CRASH` с `status` как номером сигнала, 6 `REASON_ANR` → `ANR`, остальное → `OTHER`.
- `exits`: записи от новых к старым (так их отдаёт Android); `summary` — `session` процесса, не UUID v4 — запись пропускается; у одного запуска берётся самая новая.

`TelemetryCodesTest` проверяет каждую строку этих таблиц, кроме подписок `AppTelemetry`: это Android-класс без логики, он не тестируется на JVM.

**Traps:**
- `connect` обязан идти до первых событий: у `queue.activity`, `requestFailures` и `jamHost.events` нет `replay`, раньше подписки события теряются. `AppGraph` зовёт его в конце `init`, до сервиса и экранов.
- `beginning` ждёт `connect`: до него `loggedIn` неизвестен. Если бы `connect` не позвали, `Reporter` так и не начался бы (события копятся в памяти, ничего не уходит).
- `ANDROID_ID` читается только на `io` и только в `beginning`; `@SuppressLint("HardwareIds")` с причиной в той же строке.
- Процесс без активностей (сервис поднят кнопкой гарнитуры) в использовании, только пока играет.
- `noBackupFilesDir` при первом обращении создаёт папку `no_backup` — один `mkdir` на главном потоке при сборке графа; сама папка `telemetry` создаётся уже на `io` или в обработчике падения.
- `BuildConfig.TELEMETRY` — `boolean`-константа: без флага `AppGraph.telemetry` всегда `null`, и R8 выбрасывает весь пакет вместе с `AppTelemetry` и `TelemetryCodes` (release без флага, 2026-10-18: в dex от телеметрии осталось только поле `Settings.telemetryEnabled`), а resource shrinking — строки `telemetry_title` и `telemetry_note`.

## Not here

- Что и когда отправляется в терминах пользователя, сборка с `-PqiyaaWithTelemetry=true`, рецепт `retrace` — `docs/telemetry.md`.
- Ключи настроек `telemetry/*` — `data/README.md`; переключатель в Library — `ui/README.md`.
- Сервер и схема — `spec/telemetry/README.md`, QiYaa-jam `server/src/net/telemetry.ts`.

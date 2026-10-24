# `lyrics/` — текст песни текущего трека

Какой текст показывает экран текста (`spec/player/lyrics.md`, LYR-01…17): разбор LRC и обычного текста, выбор между источником трека и сервисом текстов, память на 10 треков и сервис LRCLIB. Порт `src/core/lyrics*` и `src/lrclib` десктопа. Пакет сам ничего не показывает и не знает, откуда текст у источника: текст Яндекса даёт корневой `YandexLyrics`, текст своего файла — `local/LocalLyrics`, экран — `ui/`, «Текст песни».

Чистая JVM, импортирует только `track`:

```bash
grep -rlnE --include='*.kt' '^import (android|androidx)' app/src/main/java/io/github/kickoman/qiyaa/lyrics/   # ничего не печатает
grep -rlnE --include='*.kt' 'qiyaa\.(yandex|audio|data|jam|local|queue|playback|ui|telemetry)\.' app/src/main/java/io/github/kickoman/qiyaa/lyrics/   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `Lyrics.kt` | `Lyrics`, `LyricsLine`, `LyricsKind`, `LyricsFrom`, `LyricsAnswer`, `LyricsSource` — текст и ответ источника или сервиса |
| `LyricsText.kt` | `LyricsText` — LRC или обычный текст в строки, пределы |
| `LyricsFinder.kt` | `LyricsFinder`, `LyricsView`, `LyricsPhase`, `LyricsLookup`, `LyricsShown` — что показывает экран текста |
| `LrclibSearch.kt` | `LrclibRecord`, `LrclibChoice`, `LrclibSearch` — разбор ответа поиска LRCLIB и выбор записи |
| `Lrclib.kt` | `Lrclib` — сервис LRCLIB: запрос, повторы, название без версии |

## `Lyrics`

```kotlin
enum class LyricsKind { SYNCED, PLAIN }
data class LyricsLine(val ms: Long?, val text: String)               // ms == null — строка обычного текста
data class Lyrics(val kind: LyricsKind, val lines: List<LyricsLine>)
enum class LyricsFrom { CATALOG, LRC_FILE, FILE_TAGS, THIRD_PARTY }   // откуда текст, для подписи экрана
sealed interface LyricsAnswer {
    data class Text(val lyrics: Lyrics, val from: LyricsFrom)
    data class Missing(val serverError: Boolean = false)            // serverError — LRCLIB так и не ответил (503)
    data object Instrumental; data object Failed; data object NoAccount
}
fun interface LyricsSource { suspend fun lyrics(track: Track): LyricsAnswer }
```

- `LyricsSource` — и источник трека, и сервис. Он не бросает: сеть и ошибки сервера — `Failed`, «ничего нет» — `Missing`. Смену трека, выключенный сервис и истёкшие 8 с он узнаёт как отмену корутины, но может её и не заметить (блокирующий OkHttp в `YandexApi`): ждать его `LyricsFinder` не станет («Ожидание» ниже).
- `CATALOG` — каталог источника трека; экран называет его по `track.source`. `LRC_FILE` и `FILE_TAGS` — свой файл. `THIRD_PARTY` ставит сам `LyricsFinder` на каждый текст сервиса.
- `serverError` у `Missing`: LRCLIB отвечает 503 на часть промахов, на экране это «ничего», а в телеметрии — сбой сервиса.

## `LyricsText`

```kotlin
object LyricsText {
    const val MAX_BYTES = 256 * 1024; const val MAX_LINES = 2_000; const val MAX_LINE_CHARACTERS = 500
    fun parse(text: String): Lyrics?   // LRC или обычный; null — ничего или больше MAX_BYTES
    fun plain(text: String): Lyrics?   // только обычный, что бы в тексте ни было
}
```

Правила — `spec/lyrics/README.md`, проверка — `LyricsTextTest` на всех случаях `spec/lyrics/lrc.json` (LYR-11, LYR-12). Коротко: текст синхронный, если хоть одна строка начинается с метки времени; тогда остаются только строки с метками, по времени (равные — в порядке текста), `[offset:±N]` сдвигает всё, пустая строка с меткой — пауза. Иначе обычный: без строк ID-тегов, серии пустых строк — одна. `plain` — для источника, чей текст меток не несёт (Яндекс: `[00:01]` в нём — просто текст).

**Traps:**
- Байт-метка в начале — `Char(0xFEFF)`, а не литерал: `ktlintFormat` вырезает невидимый символ из исходника, и литерал становится пустым.
- Предел строки — в UTF-16 code units, как считают строки обоих приложений; суррогатная пара, которую разрезал бы предел, уходит целиком.
- `lowercase()` у ключа ID-тега — без локали (Kotlin так делает сам), иначе в турецкой `TI` не стало бы `ti`.

## `LyricsFinder`

```kotlin
class LyricsFinder(
    scope: CoroutineScope,                     // однопоточный: в приложении главный поток, в тестах — тестовый
    source: LyricsSource,                      // источник трека; выбор по track.source — у того, кто его даёт
    thirdParty: LyricsSource?,                 // LRCLIB; null — сервиса нет
    currentTrack: Flow<Track?>, active: Flow<Boolean>, thirdPartyEnabled: Flow<Boolean>,
    connectivity: Flow<Boolean>, signedIn: Flow<Boolean>,
    answerWaitMs: Long = 8_000, sourceSilentMs: Long = 1_000,
) {
    val view: StateFlow<LyricsView>
    val lookups: SharedFlow<LyricsLookup>     // для счётчиков телеметрии (TEL-17), буфер 16
}
enum class LyricsPhase { IDLE, LOADING, SHOWN, MISSING, INSTRUMENTAL, FAILED, NO_ACCOUNT }
data class LyricsView(val track: Track? = null, val phase: LyricsPhase = IDLE, val lyrics: Lyrics? = null,
                      val from: LyricsFrom? = null, val better: Boolean = false)   // better — лучший текст ещё может прийти
data class LyricsLookup(val shown: LyricsShown, val thirdPartyFailed: Boolean)
```

Порт `Core::LyricsFinder` десктопа, правило за правилом; `LyricsFinderTest` проверяет их по ID («Тесты» ниже).

- **Только пока экран открыт** (LYR-01, `active`). Закрытый ничего не спрашивает, его вид — `IDLE`. Открытие ищет текущий трек, из памяти, если можно; закрытие бросает то, что спрашивалось.
- **Текущий трек** сравнивается по `TrackKey`: тот же трек, пришедший снова (другая строка очереди, обновлённый `Track`), второй раз не спрашивается, а свой файл 42 и трек Яндекса 42 — разные треки.
- **Порядок** (LYR-03, LYR-05). Источник спрашивается сразу. Сервис — когда он есть и включён, а у источника нет синхронного текста: ответил без него или молчит дольше `sourceSilentMs`. Синхронный текст источника отменяет запрос сервиса. Вид показывает первый текст из: синхронный источника, синхронный сервиса, обычный источника, обычный сервиса. Без текста: `INSTRUMENTAL`, если так говорит сервис; `LOADING`, пока кто-то спрашивается; потом `NO_ACCOUNT` или `FAILED` по ответу источника; иначе `MISSING`.
- **Ожидание.** Запрос идёт отдельной корутиной в `scope`, а ждёт его `await` с `withTimeoutOrNull(answerWaitMs)`. Кто не ответил за это время, тому нечего сказать (`Missing`, в память не идёт), его корутина отменяется, поздний ответ не нужен. Ожидание не зависит от того, заметит ли источник отмену: `withTimeoutOrNull` прямо вокруг вызова ждал бы конца блокирующего запроса, до 20 с тайм-аутов общего клиента.
- **Устаревшие ответы** (LYR-09). У каждого запроса номер; ответ на прежний запрос, трек или поиск ничего не делает, а сама корутина прежнего запроса отменяется.
- **Память** (LYR-14): ответы 10 последних треков, источника и сервиса отдельно. `Failed`, `NoAccount` и истёкшее ожидание не хранятся. Ответ из памяти запроса не слал, поэтому его `serverError` второй раз не считается. Вход и выход (`signedIn`, со второго значения) забывают всё и ищут текущий трек заново.
- **Сервис включили и выключили** (LYR-15, `thirdPartyEnabled`). Включили — спрашивается сразу, если у источника нет синхронного текста. Выключили — его ответ уходит из вида, запрос отменяется.
- **Сеть вернулась** (LYR-17, `connectivity`: переход в `true`) — снова спрашивается тот, кто ответил `Failed` по текущему треку.
- **`lookups`** — раз на поиск, который слал запрос: когда ответили все спрошенные, когда сменился трек или когда экран закрыли, — с тем, что вид показывал в этот момент. Включение сервиса посреди трека открывает новый поиск.

**Traps:**
- Всё состояние меняется в корутинах одного `scope`. Многопоточный диспетчер сломает его: номера запросов и флаги — обычные поля.
- Подписки на потоки стартуют в `init` и живут, пока жив `scope`. В приложении это `AppGraph` («В приложении» ниже): память переживает пересоздание активности.
- `LyricsSource`, который бросит, уронит корутину `scope`. Источники ловят свои ошибки сами (`YandexLyrics`, `Lrclib`, `local/LocalLyrics`).
- `lookups` шлёт через `tryEmit`: без подписчика или при полном буфере (16) событие теряется. Подписчик — `AppTelemetry` (`countLyrics`).

## `LrclibSearch`, `Lrclib`

```kotlin
object LrclibSearch {
    const val DURATION_TOLERANCE_MS = 2_000L
    fun parse(body: String): List<LrclibRecord>                           // не массив или не JSON — пусто
    fun pick(records: List<LrclibRecord>, durationMs: Long): LrclibChoice  // durationMs <= 0 — длительность неизвестна
    fun titleWithoutVersion(title: String): String?                       // "Song (Live)" → "Song"; null — версии нет
}
class Lrclib(client: OkHttpClient, appVersion: String, baseUrl: String = "https://lrclib.net",
             retryDelayMs: Long = 1_000, timeoutMs: Long = 8_000) : LyricsSource
```

Запрос, выбор записи, повторы и название без версии — `spec/player/lyrics.md`, «LRCLIB» (LYR-04); `LrclibSearchTest` гоняет выбор на `spec/fixtures/lrclib`, `LrclibTest` — запросы на `MockWebServer`.

- `GET <baseUrl>/api/search?track_name=<название>&artist_name=<первый исполнитель>`, оба без пробелов по краям. Без названия или исполнителя запроса нет, ответ — `Missing`.
- `User-Agent: QiYaa/<версия> (https://github.com/Kickoman/QiYaa-android)` — этого требует спека. Версию даёт `AppGraph` (`BuildConfig.VERSION_NAME`).
- Свой клиент из общего (`newBuilder`): `callTimeout` 8 с (у общего — 20 с на чтение), без тихого повтора OkHttp при обрыве (`retryOnConnectionFailure(false)`: повтор один, тот, что в спеке) и без редиректов между `https` и `http` в обе стороны (`followSslRedirects(false)`). `callTimeout` — параметр конструктора, чтобы тест не ждал 8 с.
- 5xx или обрыв сети — ещё раз через `retryDelayMs`. После повтора 503 — «ничего» с `serverError` и следующее название, другой 5xx или обрыв — `Failed`. 4xx — «ничего» по этому названию.
- Ответ больше 2 МиБ — `Failed` без повтора: по `Content-Length`, а без него — после 2 МиБ + 1 байта.
- Ответ разбирается на потоке OkHttp (`read`), выбор записи с разбором LRC — на `Dispatchers.Default`: у `LyricsFinder` это главный поток.
- Ничего не нашлось, а у названия есть версия в конце (` (…)` или ` […]`), — поиск ещё раз без неё.

## Тесты

`app/src/test/.../lyrics/`: `LyricsTextTest` (LYR-11, LYR-12, `spec/lyrics/lrc.json`, сдвиг `[offset]`), `LrclibSearchTest` и `LrclibTest` (LYR-04, LYR-09: отмена запроса, LYR-11), `LyricsFinderTest` (LYR-01, 03, 05, 09, 14–17, ожидание 8 с и источник, который не замечает отмены, `lookups`). Ответ Яндекса — `YandexLyricsTest` в корне и `yandex/TrackLyricsTest`. LYR-06…08 и 13 — экран (`ui/README.md`, «Текст песни»), LYR-10 — `local/README.md`, «Тексты своих файлов»; на телефоне — Kickoman/QiYaa-android#118.

## В приложении

`AppGraph` держит один `LyricsFinder` на процесс (память переживает пересоздание активности) на своём `mainScope` (`SupervisorJob() + Dispatchers.Main.immediate`) — однопоточном, как он требует (`applicationScope` графа многопоточный и не годится):
- `source` — корневой `SourceLyrics`: `when (track.source)` без `else` — `YandexLyrics` для Яндекса, `local/LocalLyrics` для своих файлов, так что Spotify (#113) остановит компиляцию здесь;
- `thirdParty` — `Lrclib(httpClient, BuildConfig.VERSION_NAME)`;
- `currentTrack` — `AppGraph.lyricsTrack` (его пишет `PlayerViewModel`), `active` — `lyricsShown && lyricsOnScreen`, `thirdPartyEnabled` — `Settings.lyricsThirdParty`, `connectivity` — `NetworkMonitor.available`, `signedIn` — `Session.state.signedIn` (токен API ставится раньше, чем состояние становится «вошёл», поэтому первый запрос после входа уже с ним);
- `lookups` слушает `AppTelemetry` (`countLyrics`).

## Not here

- Запрос текста Яндекса и его фикстуры — `yandex/README.md` (`lyricsText`); что из него видит пользователь — корневой `YandexLyrics`.
- Текст своего файла (`.lrc`, теги) — `local/README.md`, «Тексты своих файлов».
- Экран текста, переключатель «текст вместо обложки» и настройка LRCLIB — `ui/README.md`, «Текст песни»; счётчики и `feature` — `telemetry/README.md`.

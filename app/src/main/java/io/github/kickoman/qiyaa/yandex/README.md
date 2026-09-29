# `yandex/` — клиент Яндекс Музыки

Пакет говорит с `api.music.yandex.net` и `oauth.yandex.ru`: конверт `{invocationInfo, result}`, вход по коду устройства, подпись ссылки на mp3, нормализация токена и источники библиотеки (лайки, плейлисты, волна, поиск). Он **не** хранит токен (это `data/TokenStore`), **не** знает про ExoPlayer и очередь (это `playback/`) и не показывает ничего пользователю: любая ошибка — исключение из дерева ниже, любой ожидаемый промах — данные.

Чистая JVM: пакет не импортирует `android.*` и тестируется против `MockWebServer`.

```bash
grep -rlnE '^import (android|androidx)' app/src/main/java/io/github/kickoman/qiyaa/yandex/   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `Errors.kt` | `YandexException` и его наследники |
| `ErrorKind.kt` | `ErrorKind` — вид ошибки для пользователя по типу исключения |
| `JsonFields.kt` | мягкий доступ к JSON: `objectOrEmpty`, `arrayOrEmpty`, `string`, `int`, `long`, `boolean`, `scalarString`, `idString`, `parseJsonObjectOrNull` |
| `Models.kt` | `Account`, `Track`, `NamedRef`, `PlaylistRef`, `Station`, `WheelWave`, `WaveBatch`, `SearchResult`, `DownloadVariant`, `DownloadInfo`, `ResolvedUrl`, `WaveEvent`, `WaveContext` |
| `YandexApi.kt` | `YandexApi` — транспорт (OkHttp), конверт, `accountStatus`, `tracks`, `resolveTrackUrl`, `reportPlayStarted` |
| `TrackParsing.kt` | `TrackParsing` — `JsonElement` → `Track` |
| `TrackUrl.kt` | `TrackUrl` — выбор варианта и подпись ссылки на mp3 |
| `Library.kt` | `Library` — аккаунт, лайки, источники треков, волна, поиск |
| `Session.kt` | `SessionState`, `AccountGateway`, `Session` — жизненный цикл входа: офлайн, повторы, истёкший токен |
| `DeviceAuth.kt` | `DeviceAuth` — OAuth «код устройства» |
| `TokenNormalizer.kt` | `TokenNormalizer` — из вставленного текста в чистый токен |

Зависимости внутри пакета: `Session → AccountGateway` (реализует `Library`), `Library → YandexApi → TrackParsing, TrackUrl → JsonFields, Models, Errors`. `DeviceAuth` и `TokenNormalizer` зависят только от `JsonFields` и `Errors`.

## Исключения

```
YandexException : IOException
├── HttpException(status, method, path, reason)      // сервер ответил ≥ 400
│     isTokenRejected == status in {401, 403}
├── NetworkException(method, path, cause)            // OkHttp не получил ответ
├── MalformedResponseException(method, path, detail) // ответ 2xx, но без нужного поля
├── AuthException(message)                           // токен не принят (аккаунт без uid)
│   ├── OAuthException(status, method, path, reason) // OAuth ответил ошибкой: "HTTP 400 on POST /device/code: Client not found"
│   └── CodeExpiredException                         // код устройства истёк до входа
└── NotSignedInException(path)                       // запрос к /users/{uid}/… до того, как аккаунт известен
```

Корень наследует `IOException` намеренно: `playback/TrackResolver` вызывает `resolveTrackUrl` из `ResolvingDataSource.Resolver`, которому ExoPlayer разрешает бросать только `IOException`. Сообщения всегда содержат метод и путь: `HTTP 401 on GET /users/42/likes/artists: Token expired`.

Данные, а не исключения: состояние сессии (`SessionState.Offline`, `Expired`), пустой `uid` в `Account` (`isValid == false`), `null` от `TrackUrl.pickBestVariant` и `parseDownloadInfo`, `""` от `TokenNormalizer.normalize`, пустые списки от `Library` при пустых ответах.

## `ErrorKind`

```kotlin
sealed interface ErrorKind {   // NoNetwork, TokenRejected, ServerError(status), Malformed, SignInRefused, CodeExpired, TrackUnplayable, Unknown
    companion object {
        fun of(failed: Throwable): ErrorKind
        fun isNetworkFailure(failure: Throwable): Boolean
        fun causes(first: Throwable?): List<Throwable>   // цепочка cause без циклов
    }
}
```

Что показать пользователю, решает тип исключения, а не его текст. `of` идёт по цепочке `cause` и берёт первое звено, которое узнаёт:

| Звено | Вид |
|---|---|
| `NetworkException`, `UnknownHost`, `Connect`, `SocketTimeout`, `NoRouteToHost` | `NoNetwork` |
| `HttpException` 401/403 | `TokenRejected` |
| `HttpException` с другим статусом | `ServerError(status)` |
| `NotSignedInException`, `AuthException` (не OAuth) | `TokenRejected` |
| `OAuthException` | `SignInRefused` |
| `CodeExpiredException` | `CodeExpired` |
| `MalformedResponseException` | `Malformed` |
| ничего из этого | `Unknown` |

`TrackUnplayable` здесь не выдаётся: его назначает `playback/PlaybackFailures` битому треку. Тексты видов — в `ui/ErrorText.kt`, подробности пишет в лог тот, кто поймал исключение.

## `YandexApi`

```kotlin
class YandexApi(client: OkHttpClient, val baseUrl: String = "https://api.music.yandex.net") {
    @Volatile var token: String                                  // "" = без Authorization
    val tokenRejections: SharedFlow<HttpException>               // 401/403 от API на запросе с токеном
    suspend fun getJson(path: String, query: Map<String, String> = emptyMap()): JsonElement
    suspend fun postForm(path: String, form: List<Pair<String, String>>): JsonElement
    suspend fun postJson(path: String, body: JsonObject, query: Map<String, String> = emptyMap(), unwrapResult: Boolean = true): JsonElement
    fun timestampNow(): String                                   // UTC, yyyy-MM-dd'T'HH:mm:ss.SSS'Z' 
    suspend fun getText(fullUrl: String): String                 // без конверта, для download-info
    suspend fun accountStatus(): Account
    suspend fun tracks(ids: List<String>): List<Track>           // POST /tracks/, до 250 id за раз
    suspend fun resolveTrackUrl(trackId: String): ResolvedUrl    // "id" или "id:albumId"
    suspend fun reportPlayStarted(account: Account, track: Track, playId: String)
    companion object { fun formatSeconds(durationMs: Long): String }
}
```

Каждый запрос уходит с `Accept-Language: ru`. `Authorization: OAuth <token>` добавляется, только если токен не пуст и адрес запроса совпадает с `baseUrl` по схеме, хосту и порту (`api.music.yandex.net`, в тестах — mock-сервер). На другие хосты токен не уходит никогда: в первую очередь это `downloadInfoUrl`, чей хост приходит из ответа сервера. Все вызовы выполняются на `Dispatchers.IO`.

Конверт: тело ответа — объект с полем `result`; оно и возвращается. При статусе ≥ 400 сообщение берётся из `error.message`, затем из строкового `error`, затем из HTTP reason phrase. Ответ 2xx без `result` — `MalformedResponseException`. Исключение — `POST /wheel/new`, который отвечает без конверта: `postJson(…, unwrapResult = false)` возвращает весь объект, а ответ, который не объект JSON, — тоже `MalformedResponseException`.

`tokenRejections` получает каждое `HttpException` с `isTokenRejected`, если запрос ушёл с заголовком `Authorization`, до того как исключение брошено. Так `Session` узнаёт об отозванном токене на **любом** запросе, а не только при старте. Не эмитят: запросы без токена (вход), 5xx, `getText()` — у хранилища свой 403, не про токен. Поток — `MutableSharedFlow(extraBufferCapacity = 1)` с `tryEmit`: без подписчика событие теряется.

`resolveTrackUrl` делает два запроса: `GET /tracks/{id}/download-info` → список вариантов; затем `GET <downloadInfoUrl>?format=json` → `{host, path, ts, s}` → подпись (см. `TrackUrl`). `accountStatus` без `uid` в ответе — `AuthException` (токен не принят).

`reportPlayStarted` отправляет форму `POST /play-audio` с полями `track-id`, `album-id`, `from=web-own_tracks-track-track-main`, `play-id`, `uid`, `timestamp` и `client-now` (одно и то же значение, `yyyy-MM-dd'T'HH:mm:ss.SSS'Z'` в UTC), `track-length-seconds` (формат `QString::number`: `180`, `201.5`), `total-played-seconds=0`, `end-position-seconds=0`.

**Traps:**
- Второй прыжок `resolveTrackUrl` (`getText` по `downloadInfoUrl` на `storage.mds.yandex.net`) идёт без токена: ссылка подписана параметром `sign`. Если хранилище начнёт отвечать 401/403 без токена, разрешить его явно (отдельный список хостов), а не возвращать токен на любой адрес. Десктоп пока шлёт токен и туда.
- OkHttp сам убирает `Authorization` при редиректе на другой хост, так что редирект с API наружу токен тоже не уносит.
- `token` читается в момент сборки запроса; смена токена не влияет на уже отправленные вызовы.
- `getText` не распаковывает конверт и не парсит JSON — только для второго прыжка `download-info`.
- `formatSeconds` эмулирует Qt: целые секунды без `.0`. Так делает десктопный QiYaa, и сервер это принимает.

## `TrackParsing`

```kotlin
object TrackParsing {
    const val COVER_SIZE = "400x400"
    fun parseTrack(element: JsonElement): Track
    fun parseTrackArray(element: JsonElement?): List<Track>   // распаковывает {"track": {...}}
    fun coverUrl(uri: String, size: String = COVER_SIZE): String?   // null при пустом uri
}
```

`title` получает суффикс ` (version)`, если есть `version`. `albumId` — id первого элемента `albums`. Обложка — `coverUri` первого альбома, иначе `coverUri` трека, иначе `ogImage` трека (порядок спеки, фикстура `tracks/cover-order`); `%%` заменяется на размер, `https://` добавляется, если схемы нет. `durationMs` через `double`, `available` по умолчанию `true`.

## `TrackUrl`

```kotlin
object TrackUrl {
    fun parseDownloadVariants(result: JsonElement): List<DownloadVariant>
    fun pickBestVariant(variants: List<DownloadVariant>): DownloadVariant?   // null для пустого списка
    fun parseDownloadInfo(json: String): DownloadInfo?                       // null, если нет host/path/s
    fun buildTrackUrl(info: DownloadInfo): String
}
```

Выбор: mp3 без `preview` с максимальным `bitrateInKbps`; если таких нет — первый вариант. Варианты без `downloadInfoUrl` с `http(s)://` отбрасываются.

Подпись: `md5("XGRlBW9FXlekgbPrRHuSiA" + path.substring(1) + s)` в hex, ссылка `https://{host}/get-mp3/{md5}/{ts}{path}`. `ts` может прийти числом — `scalarString` приводит его к строке без дробной части.

**Traps:** `path` обязан начинаться с `/` (иначе `null`), потому что подписывается без первого символа.

## `Library`

```kotlin
class Library(val api: YandexApi) : AccountGateway {
    val account: StateFlow<Account>;  val likedIds: StateFlow<Set<String>>
    val isLoggedIn: Boolean;  fun isLiked(trackId: String): Boolean
    suspend fun connectAccount(): Account;  fun logout()
    // AccountGateway: token = api.token, tokenRejections = api.tokenRejections, preloadLikes() = likedTrackIds(), forget() = logout()
    suspend fun tracksByIds(ids: List<String>): List<Track>          // чанками по TRACKS_PER_REQUEST = 250
    suspend fun likedTrackIds(): List<String>;  suspend fun likedTracks(): List<Track>
    suspend fun userPlaylists(): List<PlaylistRef>;  suspend fun playlistTracks(playlist: PlaylistRef): List<Track>
    suspend fun personalPlaylists(): List<PlaylistRef>              // «Для вас»: GET /landing3?blocks=personalplaylists
    suspend fun playlistRecommendations(playlist: PlaylistRef): List<Track>   // «Похожие треки»
    suspend fun wheelWaves(seeds: List<String>): List<WheelWave>    // «Колесо волн»: POST /wheel/new
    suspend fun likedArtists(): List<NamedRef>;  suspend fun artistTopTracks(artistId: String): List<Track>  // ≤ 100
    suspend fun likedAlbums(): List<NamedRef>;  suspend fun albumTracks(albumId: String): List<Track>
    suspend fun stations(): List<Station>                            // GET /rotor/stations/list?language=ru
    suspend fun startWave(seeds: List<String>): WaveBatch;  suspend fun moreWave(sessionId: String, queue: List<String>): WaveBatch
    suspend fun search(text: String): SearchResult
    suspend fun setLiked(trackId: String, liked: Boolean);  suspend fun dislike(trackId: String)
    suspend fun waveFeedback(context: WaveContext, event: WaveEvent, track: Track?, playedSeconds: Double)
    companion object { fun parseWaveBatch(result: JsonElement): WaveBatch; fun stationGroupKey(type: String): String }
}
```

Эндпоинты и формы:

| Метод | Путь | Тело / query |
|---|---|---|
| GET | `/users/{uid}/likes/tracks` | — → `library.tracks[].id` |
| GET | `/users/{uid}/playlists/list` | — → `uid`/`owner.uid`, `kind`, `title`, `trackCount` |
| POST | `/wheel/new` | JSON `{"context":{"type":"WAVE","data":{"seeds":[…]}},"feedbacks":[]}` → без конверта `result`: `items[]` с `type == "WAVE"`, из `data.wave` — `name`, `description` (нет — `""`), `seeds`; элемент без имени или с пустыми `seeds` пропускается |
| GET | `/landing3?blocks=personalplaylists` | — → `blocks[].entities[].data`: плейлист — вложенный `data`, если есть, иначе сам `data`; `uid`/`owner.uid`, `kind`, `title`, `trackCount` (нет — 0). Запись без владельца или без `kind` (например, `ready: false`) пропускается |
| GET | `/users/{ownerUid}/playlists/{kind}` | — → `tracks[]` (вложенные `track` или только `id`) |
| GET | `/users/{ownerUid}/playlists/{kind}/recommendations` | — → `tracks[]`, разбор тот же, что у плейлиста: трек без названия дозагружается по id через `POST /tracks/` |
| GET | `/users/{uid}/likes/artists`, `/likes/albums` | элементы могут быть обёрнуты в `artist`/`album` |
| GET | `/artists/{id}/track-ids-by-rating` | — → `tracks[]` |
| POST | `/albums` | `album-ids=1,2` (подкасты пропускаются) |
| GET | `/albums/{id}/with-tracks` | — → `volumes[][]`, `albumId` подставляется при отсутствии |
| POST | `/rotor/session/new` | JSON `{seeds, includeTracksInResponse, includeWaveModel, interactive: true}` |
| POST | `/rotor/session/{id}/tracks` | JSON `{queue: [последние id]}` |
| GET | `/search` | `text`, `type=all`, `page=0` → `best.{type,result}`, `tracks.results`. `bestType` — `artist`, `album`, `track`, `playlist` как есть, любой другой тип — `other`, без `best` — `""` |
| POST | `/users/{uid}/likes/tracks/add-multiple`, `…/remove`, `…/dislikes/tracks/add-multiple` | `track-ids=<id>` |

`stationGroupKey` сводит тип `user` к `personal`; остальные типы — ключ как есть. Порядок и названия групп задаёт `ui/screens/LibrarySectionScreen`.

Методы с путём `/users/{uid}/…` до `connectAccount()` бросают `NotSignedInException` и не делают запрос: раньше они уходили на `/users//…`.

**Traps:**
- `likedIds` заполняется только `likedTrackIds()`/`likedTracks()` и правится `setLiked`/`dislike`; `connectAccount` его не трогает — предзагрузку делает `Session` при переходе в `Online`.
- `moreWave` возвращает `sessionId` из запроса, если сервер его не прислал.
- `startWave` без `radioSessionId` — `MalformedResponseException`, а не пустая волна.

### Обратная связь волны

```kotlin
enum class WaveEvent(val wireName: String) { RADIO_STARTED("radioStarted"), TRACK_STARTED("trackStarted"), TRACK_FINISHED("trackFinished"), SKIP("skip") }
data class WaveContext(val sessionId: String, val stationId: String, val batchId: String)
```

`waveFeedback` — порт `Library::waveFeedback` десктопа (сценарии `spec/player/tracking.md`, TRK-03…11). Событие — `{"type", "timestamp"}`; у `radioStarted` ещё `"from": "web-main-rup-radio-main"`, у трековых — `"trackId": "<id>:<albumId>"` или `"<id>"`, у `trackFinished` и `skip` — `"totalPlayedSeconds"`, округлённые до 0,1.

| Путь | Тело | Когда |
|---|---|---|
| `POST /rotor/session/<sessionId>/feedback` | `{"event": {…}, "batchId": "…"}` (пустой `batchId` не отправляется) | обычно (TRK-09) |
| `POST /rotor/station/<stationId>/feedback?batch-id=<batchId>` | голое событие | после ответа 4xx от сессии; сессия запоминается, и дальше её события идут сразу сюда (TRK-10). Без `stationId` ничего не отправляется |

5xx, таймауты и сетевые ошибки глушатся и не повторяются (TRK-11): фидбек — best-effort.

**Traps:** запоминание «сессия идёт через станцию» — в памяти `Library` (`ConcurrentHashMap.newKeySet`), до перезапуска процесса. 401 от сессии тоже 4xx: событие уйдёт на станцию, а `tokenRejections` сообщит `Session` об отклонённом токене.

## `Session`

```kotlin
sealed interface SessionState { LoggedOut; Connecting; Online(account); Offline; Expired }

interface AccountGateway {
    var token: String;  val tokenRejections: Flow<HttpException>
    suspend fun connectAccount(): Account;  suspend fun preloadLikes();  fun forget()
}

class Session(gateway: AccountGateway, connectivity: Flow<Boolean>, scope: CoroutineScope) {
    val state: StateFlow<SessionState>
    fun start()                                  // пустой токен → LoggedOut, иначе цикл подключения
    suspend fun signIn(token: String): Account   // ошибка → токен "", LoggedOut, исключение наружу
    fun signOut()                                // forget() и LoggedOut
    companion object { FIRST_RETRY_MS = 2_000; MAX_RETRY_MS = 60_000 }
}
```

Одна корутина в `scope` держит сессию, пока та не станет `Expired` или `LoggedOut`:

| Состояние | Что происходит | Переходы |
|---|---|---|
| `Offline` | ждём `connectivity == true` или конца паузы | сеть появилась → сразу `Connecting`; пауза кончилась → `Connecting` |
| `Connecting` | `connectAccount()` | успех → `Online`; `AuthException` или 401/403 → `Expired`; любой другой `YandexException` (сеть, 5xx, без `result`) → `Offline` с паузой |
| `Online(account)` | один раз `preloadLikes()`, затем ждём потери сети | сеть пропала → `Offline`, при возврате аккаунт перепроверяется |
| `Expired` | ничего; цикл остановлен | только `signIn`/`signOut` |

Пауза между неудачными попытками при живой сети: 2, 4, 8, 16, 32, 60, 60… с. Пропавшая и вернувшаяся сеть сбрасывает её на 2 с и запускает попытку сразу.

`tokenRejections` в любом состоянии, кроме `LoggedOut`, останавливает цикл и ставит `Expired`. `signIn` проверяет новый токен в состоянии `LoggedOut`, поэтому неверный токен при входе даёт исключение, а не `Expired`.

`connectivity` на устройстве — `NetworkMonitor.available` из корневого пакета (default network с `NET_CAPABILITY_INTERNET`); в тестах — `MutableStateFlow<Boolean>`.

**Traps:**
- `Session` не хранит токен на диске и не чистит `TokenStore`: это делает `ui/AppViewModel`, получив `Expired` или по «Выйти».
- При переходе `Online → Offline` аккаунт в `Library` не стирается: экран продолжает показывать имя, а `likedIds` — последние известные лайки.
- Исключения, не наследующие `YandexException`, из `connectAccount()` не ловятся и роняют `scope`: это баг, а не состояние сети.
- `preloadLikes()` глушит свои `YandexException`: при 401 сессия всё равно станет `Expired` через `tokenRejections`.

## `DeviceAuth`

```kotlin
class DeviceAuth(client: OkHttpClient, baseUrl = "https://oauth.yandex.ru", deviceName = "QiYaa", clock: () -> Long) {
    data class Code(deviceCode, userCode, verificationUrl, intervalMs, deadlineMs)
    suspend fun requestCode(): Code            // POST /device/code
    suspend fun waitForToken(code: Code): String   // POST /token, опрос до токена, OAuthException или CodeExpiredException
    companion object { CLIENT_ID, CLIENT_SECRET, BROWSER_LOGIN_URL, DEFAULT_VERIFICATION_URL, DEFAULT_INTERVAL_SECONDS = 5, DEFAULT_EXPIRES_IN_SECONDS = 300, SLOW_DOWN_STEP_MS = 2_000 }
}
```

`device_name` отправляется как `QiYaa (<deviceName>)`. Интервал опроса не меньше 1 с; `slow_down` прибавляет 2 с; `authorization_pending` продолжает; любой другой `error` — `OAuthException(status, "POST", "/token", error_description | error)`. `/device/code` со статусом ≥ 400 — `OAuthException` со статусом и `error_description`; ответ 2xx без `device_code` или `user_code` — `MalformedResponseException`. Сеть — `NetworkException`, а не ошибка входа. Истёкший код — `CodeExpiredException`. Дедлайн — `clock() + expires_in`. Клиент — публичный клиент Яндекс Музыки, тот же, что в Yaamp и yandex-music-api.

## `TokenNormalizer`

Принимает сырой токен, JSON-строку или объект с `access_token`, URL `…#access_token=…` и префикс `OAuth `; возвращает токен, если он совпадает с `^[A-Za-z0-9._\-]{10,}$`, иначе `""`.

## Not here

- Хранение токена — `data/TokenStore`. Очередь и отметки прослушивания по событиям плеера — `queue/QueueController`. Тексты для пользователя — `ui/`.

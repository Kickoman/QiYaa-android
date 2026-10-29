# `spotify/` — Spotify

Клиент Spotify, порт `src/spotify` десктопа (Kickoman/QiYaa) файл в файл; план — [Kickoman/QiYaa-android#113](https://github.com/Kickoman/QiYaa-android/issues/113). Поведение — `spec/player/spotify.md` (SPOT-01…22) и `spec/player/errors.md`, общие тест-векторы протокола — `spec/spotify/`. В пакете — ядро протокола `protocol/` и сеть с входом поверх него: соединение с точкой доступа (AP) и ключи файлов, токены client-token и login5, запросы к spclient и Pathfinder, вход кодом и в браузере, состояние входа, а над ними — каталог: выборы SPOT-07…12, метаданные, Pathfinder, станция и запрос автоплея (#113 PR 3b). Над ними же — файл трека (#113 PR 4): `SpotifyFiles` готовит его (метаданные `TRACK_V4`, Ogg Vorbis по битрейтам, ключ, `storage-resolve`, кэш на процесс), `SpotifyStream` читает его диапазонами с CDN, расшифровывает и переходит по ссылкам; к Media3 их подключает `playback/SpotifyDataSource`. Пакет ничего не показывает и не знает очереди: склейка с ней в корне, как `YandexLyrics.kt`. Сохранённый вход лежит в `data/SpotifyStore`, к `SavedCredentials` его подключает корневой `SpotifySavedCredentials`. В графе (`AppGraph`, #113 PR 3c, PR 4) — `spotifySession`, `spotifyWeb`, `spotifyLibrary`, `spotifyAccount` (сохранённый вход — при старте, SPOT-04), `spotifyPairLogin`, `spotifyBrowserLogin` и `spotifyFiles` (битрейты — `playback/SpotifyBitrates` по `qualityInEffect`); экран входа, раздел Library, выборы и радио — `ui/SpotifyViewModel` (`ui/README.md`, «Spotify», #113 PR 5). Продолжение очереди (автоплей и станция как `queue.Continuation`) — корневой `SpotifyContinuations` (верхний `README.md`, строка «корень»; правила очереди — `queue/README.md`, «Своё продолжение»), вид ошибки для статуса — корневой `SpotifyErrorKinds` («Вид ошибки для статуса» ниже).

Чистая JVM; из приложения пакет может импортировать только `track`, а `protocol/` — без ввода-вывода и не импортирует из приложения ничего:

```bash
grep -rlnE --include='*.kt' '^import (android|androidx)' app/src/main/java/io/github/kickoman/qiyaa/spotify/   # ничего не печатает
grep -rlnE --include='*.kt' 'qiyaa\.(yandex|audio|data|jam|lyrics|local|queue|playback|ui|telemetry)\.' app/src/main/java/io/github/kickoman/qiyaa/spotify/   # ничего не печатает
grep -rnE --include='*.kt' '^import (okhttp3|kotlinx\.coroutines|java\.net)\.' app/src/main/java/io/github/kickoman/qiyaa/spotify/protocol/   # ничего не печатает
grep -rn --include='*.kt' '^import io\.github\.kickoman\.qiyaa\.' app/src/main/java/io/github/kickoman/qiyaa/spotify/protocol/ | grep -v 'qiyaa\.spotify\.protocol\.'   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `Errors.kt` | `SpotifyException` и его дети («Исключения» ниже) |
| `SpotifyProblem.kt` | `SpotifyProblem` — что не удалось, для телеметрии (TEL-18) |
| `Endpoints.kt` | `Endpoints` — адреса Spotify; тесты подменяют их mock-сервером |
| `SpotifyIdentity.kt` | `SpotifyIdentity` — что приложение говорит Spotify о себе на Android |
| `SpotifySession.kt` | `SpotifySession` — соединение с AP: apresolve, сокет, вход, keep-alive, переподключение, ключи файлов; `SpotifyState`, `SessionListener`, `SessionTiming` |
| `SpotifyWeb.kt` | `SpotifyWeb` — токены client-token и login5, запросы к spclient и Pathfinder; `WebRequest` |
| `PairLogin.kt` | `PairLogin` — вход кодом на spotify.com/pair (SPOT-01) |
| `BrowserLogin.kt` | `BrowserLogin` — вход в браузере: PKCE и переход на `127.0.0.1` (SPOT-02) |
| `SpotifyAccount.kt` | `SpotifyAccount` — вход сохранённый и по токену, отказы, выход (SPOT-03…06); `SavedCredentials` |
| `SpotifyLibrary.kt` | `SpotifyLibrary` — каталог: выборы SPOT-07…12, метаданные пачками, Pathfinder с хешами, станция и автоплей; `SpotifyItem`, `SpotifyFound`, `SpotifyFoundAlbum`, `SpotifyPick`, `ContextPage` |
| `SpotifyTracks.kt` | `SpotifyTracks` — трек Spotify как `Track`: id, доступность, обложка (SPOT-15, SPOT-19) |
| `SpotifyFiles.kt` | `SpotifyFiles` — файл трека для игры: `TRACK_V4`, Ogg Vorbis по битрейтам, ключ, `storage-resolve`; кэш на процесс и `askedInOtherQuality` (SPOT-13, SPOT-15, QUAL-05); `SpotifyFile` |
| `SpotifyStream.kt` | `SpotifyStream` — байты файла с CDN диапазонами: позиции без заголовка 167 байт, расшифровка, ответ 200, переход по ссылкам и второй `storage-resolve` (SPOT-14), отмена — не сбой ссылки; `ReadGuard` |
| `CatalogJson.kt` | `CatalogJson` — разбор JSON каталога: контекст (станция, автоплей, следующая страница), `data` Pathfinder, «Made For You», поиск; `ContextUris` |
| `Http.kt` | внутреннее: запрос OkHttp с отменой и пределом тела, форма OAuth и её ответ, `closingOnCancel` |
| `protocol/AccessPoint.kt` | `AccessPoint` — сеанс с точкой доступа (AP): рукопожатие, пакеты, вход, keep-alive, ключи файлов; `ClientIdentity`, `ApPlatform`, `ApCpuFamily`, `ApOs`, `Credentials`, `AuthenticationType`, `ServerKey`, `LoginError`, `ApEvent` |
| `protocol/Login.kt` | `Login` — тела запросов client-token и login5 и разбор их ответов; `Hashcash`; `ClientPlatform`, `ClientTokenParams`, `ClientTokenReply`, `Login5Reply`, `HashcashChallenge`, `HashcashSolution` |
| `protocol/Metadata.kt` | `Metadata` — запрос и ответ `extended-metadata`, разбор `TRACK_V4`, `ALBUM_V4`, `ARTIST_V4`, топ исполнителя, выбор Ogg Vorbis, `storage-resolve`; `ExtensionKind`, `AudioFormat`, `SpotifyTrack`, `SpotifyAlbum`, `SpotifyArtist`, `MetadataEntity`, `PlayableFile`, `StorageUrls` и их части |
| `protocol/CatalogMessages.kt` | `CatalogMessages` — запрос и страница `collection/v2/paging`, страница rootlist и плейлиста, плейлисты rootlist, запрос автоплея; `CollectionPage`, `ListPage` |
| `protocol/AudioFile.kt` | `AudioDecryptor`, `AudioFile` — расшифровка файла трека, заголовок 167 байт |
| `protocol/Crypto.kt` | `Crypto` — SHA-1, HMAC-SHA1, `modPow`, проверка подписи RSA; `AesCtr` |
| `protocol/Shannon.kt` | `Shannon` — шифр и MAC пакетов AP |
| `protocol/Protobuf.kt` | `ProtoWriter`, `ProtoReader`, `ProtoWire` — формат protobuf без схемы |
| `protocol/Ids.kt` | `Ids` — base62 ↔ gid, URI трека |
| `protocol/Bytes.kt` | `Bytes` — неизменяемые байты; hex, big-endian |

Соответствие ПК: `client/session` → `SpotifySession.kt`, `client/web` → `SpotifyWeb.kt`, `client/login` → `PairLogin.kt` и `BrowserLogin.kt`, `client/identity` и `endpoints` → `SpotifyIdentity.kt` и `Endpoints.kt`, `client/credentials_file` → `data/SpotifyStore.kt` (через корневой `SpotifySavedCredentials.kt`), `app/spotify_account` → `SpotifyAccount.kt`, `sources/spotify/library` → `SpotifyLibrary.kt` и `CatalogJson.kt`, `sources/spotify/tracks` → `SpotifyTracks.kt`; ядро: `access_point` → `AccessPoint.kt`, `login` → `Login.kt`, `metadata` и `catalog` → `Metadata.kt` и `CatalogMessages.kt`, `audio_file` → `AudioFile.kt`, `crypto`, `modpow.c` и подмножество BearSSL → `Crypto.kt`, `shannon` → `Shannon.kt`, `protobuf` → `Protobuf.kt`, `ids` и `bytes` → `Ids.kt` и `Bytes.kt`.

## Исключения

```
SpotifyException : IOException
├── SpotifyHttpException(status, method, path)   // Spotify ответил не 2xx: "HTTP 503 on GET /thing"
├── SpotifyNetworkException(what, cause)         // ответа нет; cause — исходное IOException
├── SpotifyContentException(detail)              // ответ не читается: разбор, нет полей, тело больше предела, hashcash не решён
├── LoginRefusedException(code, where)           // вход отклонён: AP (кроме BadCredentials) или ошибка login5; isTransient — login5 4, 6, 8
├── CdnStatusException(status, path)             // все ссылки CDN упали, последняя — этим статусом (SpotifyStream)
├── AuthorizationException(error, path)          // OAuth не дал токена: access_denied, expired_token, state_mismatch…
├── KeyRefusedException                          // AesKeyError 1: ключей этому аккаунту не дают (SPOT-17)
├── KeyFailedException(detail)                   // две попытки без ключа (SPOT-16)
├── NotLoggedInException                         // нет сохранённого входа
├── QueryOutdatedException(operation)            // все хеши Pathfinder отвергнуты, последний — ответом 2xx без data
└── NoPlayableFileException(trackId, detail)     // нечего играть: нет Ogg Vorbis, нет метаданных, нет ссылки CDN (SpotifyFiles)
```

- `LoginRefusedException.isTransient` — отказ login5 с кодом `TIMEOUT` (4), `TOO_MANY_ATTEMPTS` (6) или `TRY_AGAIN_LATER` (8) (`Login5Reply.Error.TRANSIENT_CODES`; номера — `LoginError` в `protocol/proto/spotify/login5/v3/login5.proto:84–94` librespot 7289e92, у ПК имён нет). Остальные коды login5 и любой отказ AP — не временные. Что с этим делает воспроизведение — `playback/README.md`, «Классификация ошибок».

- Корень наследует `IOException`: Media3 пропускает из источника данных только `IOException` (`playback/SpotifyDataSource`).
- `NotLoggedInException` — только «нет сохранённого входа»: ещё не входили, вышли или `BadCredentials` (он удаляет сохранённое). Пока AP подключается или переподключается, ключ ждёт входа до 20 с и падает `SpotifyNetworkException`, а не этой ошибкой: иначе холодный старт и обрыв давали бы ERR-08 вместо ожидания (SPOT-04, SPOT-18).
- У `SpotifyNetworkException` причина есть всегда: исключение OkHttp или сокета; у таймаутов — `SocketTimeoutException`, у оборванного соединения — `EOFException` или ошибка сокета. Поэтому `yandex.ErrorKind.of` видит в ней сеть по цепочке причин и без классификатора Spotify.
- `AuthorizationException` в списке плана нет: она — как `OAuthException` у `yandex`. Экран входа на неё отвечает «войдите снова», а не «нет сети» или «сервер упал», и ошибки OAuth — строки, а не коды.
- Данные, а не исключения: `SpotifyState` (`Offline`, `Expired`, `PremiumRequired`, `Refused`), `null` от `SavedCredentials.load()`, `SpotifyProblem`. В каталоге: трек без метаданных (не 200 или без тела) или с телом, которое не разбирается, выпадает из списка; альбом или исполнитель без метаданных — пустой список; сохранённый альбом или исполнитель без имени выпадает; поиск без треков — `SpotifyFound.Tracks(emptyList())`; трек без Ogg Vorbis — `available = false`.
- Весь ответ, который нельзя прочесть (конверт `extended-metadata`, страница коллекции или списка, контекст, поиск и главная Pathfinder), — `SpotifyContentException` и `Content` в `problems` («Два вида нечитаемого» спеки, `expected/spotify/README.md`). Pathfinder, отвергнувший все хеши ответом 2xx без `data`, — `QueryOutdatedException` и `QueryOutdated` вместо `Content`; отвергнувший последний хеш статусом 400, 405 или 412 — `SpotifyHttpException` с этим статусом и тоже `QueryOutdated`.

### Вид ошибки для статуса

`yandex.ErrorKind` — общий вид ошибки приложения (`yandex/README.md`), а `yandex` не знает исключений Spotify, и `ErrorKind.of` дал бы им `Unknown`. Их переводит корневой `SpotifyErrorKinds.kt`:

```kotlin
fun errorKindOf(failed: Throwable): ErrorKind   // первое звено цепочки cause, которое узнаёт; иначе ErrorKind.of(failed)
```

| Звено | Вид |
|---|---|
| `SpotifyNetworkException` | `NoNetwork` |
| `SpotifyHttpException(status)`, `CdnStatusException(status)` | `ServerError(status)` при любом статусе: 401 и 403 Spotify — не `TokenRejected` Яндекса |
| `SpotifyContentException`, `QueryOutdatedException` | `Malformed` (ПК: «Spotify sent an answer QiYaa cannot read») |
| `NotLoggedInException` | `SpotifyLoggedOut` (ERR-08, SPOT-06) |
| `KeyRefusedException` | `SpotifyKeyRefused` (SPOT-17) |
| `KeyFailedException` (SPOT-16), `NoPlayableFileException` (SPOT-15) | `TrackUnplayable` |
| `LoginRefusedException` с `where` = `/v3/login` (ошибка login5) | `SpotifyTokenRefused`, и у временных кодов тоже: статус выбора или автоплея говорит, что токена не дали; трек же с таким отказом ждёт сети (`playback/PlaybackFailures`) |
| `LoginRefusedException` от AP (кроме `BadCredentials`: например, `PremiumAccountRequired` у ключа) | `SpotifyLoginRefused` |
| остальное (`AuthorizationException`, исключения `yandex`, сеть Java) | как `ErrorKind.of` |

Его получает конструктор `QueueController` из `AppGraph` (статусы источников, автоплея, радио и поиска); `ui/ListLoader` принимает классификатор параметром (по умолчанию `ErrorKind.of`), его передают списки Spotify в `ui/SpotifyViewModel`; вид отказа воспроизведения (`FailureKind`: `TRACK`, `SESSION`, `NETWORK`) у трека и `ErrorKind` его статуса решает `playback/PlaybackFailures` (`playback/README.md`, «Классификация ошибок»), а какие отказы загрузчик не повторяет — `playback/LoadRetries`. Тексты четырёх видов Spotify — в `ui/ErrorText.kt`, по переводам ПК (`qiyaa_ru.ts`, `qiyaa_be.ts`):

| Вид | Строка | Текст ПК |
|---|---|---|
| `SpotifyLoggedOut` | `error_spotify_logged_out` | «not logged in to Spotify» (`source.cpp`) |
| `SpotifyKeyRefused` | `error_spotify_key_refused` | «Spotify does not let this account play here» (`file_download.cpp`) |
| `SpotifyLoginRefused` | `error_spotify_login_refused` | «Spotify refused the login» (`spotify_account.cpp:110`, без `: %1`) |
| `SpotifyTokenRefused` | `error_spotify_token_refused` | «Spotify did not give an access token» (`web.cpp:187–191`) |

`SignInRefused` Яндекса («Яндекс отклонил вход») для Spotify не годится.

Экрану входа нужен ещё один вид — отказ OAuth, который `errorKindOf` оставляет `Unknown`:

```kotlin
fun spotifyLoginErrorKindOf(failed: Throwable): ErrorKind   // AuthorizationException в цепочке: expired_token — CodeExpired, иначе SpotifyLoginRefused; без неё — errorKindOf
```

`expired_token` — и ответ Spotify, и местный срок кода (`PairLogin.EXPIRED_TOKEN`); «код устарел» — `error_code_expired`, общий с Яндексом. `access_denied`, `state_mismatch`, `no_code` — `error_spotify_login_refused`. Тест — `SpotifyErrorKindsTest`.

## `SpotifyIdentity`

```kotlin
class SpotifyIdentity(appVersion: String, deviceId: String, osRelease: String, androidVersion: String, abi: String) {
    val accessPoint: ClientIdentity       // рукопожатие и вход в AP
    val clientToken: ClientTokenParams    // тело client-token
    val userAgent: String                 // Spotify/124200290 Linux/0 (QiYaa X.Y.Z)
    companion object { const val CLIENT_ID = "65b708073fc0480ea92a077233ca87bd"; const val CLIENT_VERSION = "1.2.52.442"; fun newDeviceId(random: SecureRandom = SecureRandom()): String }
}
```

Приложение представляется как ПК на Linux (решение 5 плана; абзац Android в «What the app tells Spotify» спеки). Параметры — простые строки, их подаёт `AppGraph`: `System.getProperty("os.version")` (версия ядра), `Build.VERSION.RELEASE`, `Build.SUPPORTED_ABIS[0]`, `BuildConfig.VERSION_NAME` и device id из `data/SpotifyStore.deviceId(SpotifyIdentity::newDeviceId)`.

| ABI | Платформа AP | CPU входа | `hardware` client-token |
|---|---|---|---|
| `arm64-v8a` (всё на `arm64`) | `LINUX_ARM` | `ARM` | `aarch64` |
| `armeabi-v7a`, `armeabi` | `LINUX_ARM` | `ARM` | `arm` |
| `x86_64` | `LINUX_X86_64` | `X86_64` | `x86_64` |
| `x86` | `LINUX_X86` | `X86` | `i386` |
| другое (`riscv64`) | `LINUX_X86` | `UNKNOWN` | сам ABI |

ОС входа — всегда `ApOs.LINUX`; платформа client-token — `ClientPlatform.Linux(release = osRelease, version = androidVersion, hardware)`; `system_information_string` и `version_string` — `QiYaa X.Y.Z`. Строки `x86` и «другое» следуют правилу ПК (`identity.cpp`: всё, что не ARM и не x86-64, — `LinuxX86`, CPU `i386` — `X86`); спека называет только `aarch64`, `arm` и `x86_64`. `newDeviceId` — 20 байт `SecureRandom` в 40 цифрах hex.

**Traps:**
- Если AP или login5 откажут на телефоне (#118), значения меняются здесь и только здесь.
- Device id — «один на установку»: новый id для Spotify — новое устройство. Делает его хранилище один раз.
- `toString()` не печатает device id.

## `Endpoints`

`Endpoints(apresolve, accounts, clientToken, login5, pathfinder, spclientScheme)` — по умолчанию адреса Spotify и `https`; запасные `FALLBACK_ACCESS_POINT = "ap.spotify.com:443"` и `FALLBACK_SPCLIENT = "spclient.wg.spotify.com:443"`, как у ПК. `spclientScheme = "http"` — только в тестах. Сокет AP — не TLS, шифрует Shannon: `usesCleartextTraffic="false"` голый `java.net.Socket` не касается — по документации `NetworkSecurityPolicy` API сокетов этот флаг не соблюдает (на телефоне не проверено, #118).

## `SpotifySession`

```kotlin
class SpotifySession(
    client: OkHttpClient,                          // AppGraph.httpClient; для apresolve — копия с callTimeout 10 с
    identity: SpotifyIdentity,
    network: Flow<Boolean>,                        // NetworkMonitor.available
    inUse: Flow<Boolean>,                          // приложение на экране или играет: AppGraph — ScreenPresence.onScreen || QueueActivity.PlayingChanged
    scope: CoroutineScope,                         // applicationScope
    endpoints: Endpoints = Endpoints(),
    timing: SessionTiming = SessionTiming(),
    serverKey: ServerKey = ServerKey.SPOTIFY,      // другой — только у фальшивой AP
    pause: suspend (Long) -> Unit = { delay(it) }, // паузы переподключения; тесты их записывают
    log: (String) -> Unit = {},
) {
    val state: StateFlow<SpotifyState>
    val problems: SharedFlow<SpotifyProblem>       // KeyRefused, KeyFailed, AccessPoint, LoginRefused(code)
    val credentials: Credentials?                  // данные входа: до APWelcome — данные login() (токен или сохранённые), после — многоразовые; null — не вошли
    val country: String                            // страна аккаунта от AP; "" до неё
    val spclients: List<String>                    // хосты spclient от apresolve
    fun login(credentials: Credentials, listener: SessionListener? = null)
    fun logout()
    fun loggedInUsername(): String                 // имя входа; без входа — NotLoggedInException, после отказа AP — LoginRefusedException
    suspend fun audioKey(track: Bytes, file: Bytes): Bytes   // gid 16 байт, id файла 20; ключ 16 байт
}
sealed interface SpotifyState { LoggedOut; Connecting; Online(username, country, product); Offline; Expired; PremiumRequired; Refused(code) }
interface SessionListener { fun loggedIn(reusable: Credentials); fun refused(error: LoginError) }
data class SessionTiming(keyTimeoutMs = 5_000, keyRetryMs = 1_000, loginWaitMs = 20_000,
    reconnectMs = [2_000, 4_000, 8_000, 16_000, 32_000, 60_000], connectTimeoutMs = 10_000, handshakeTimeoutMs = 20_000)
```

| Состояние | Когда |
|---|---|
| `LoggedOut` | до `login`, после `logout` |
| `Connecting` | apresolve и попытки по адресам |
| `Online(username, country, product)` | после `APWelcome`; `country` и `product` дописываются, когда придут |
| `Offline` | нет сети, пауза перед новой попыткой, упавшее соединение в фоне |
| `Expired` | `BadCredentials` (SPOT-04) |
| `PremiumRequired` | `PremiumAccountRequired` (SPOT-05) |
| `Refused(code)` | другой отказ входа (`LoginError.code`) |

Три последних — конец входа: сессия больше не подключается до нового `login`. `toString()` у `Online` печатает длину имени, а не имя.

### Подключение

1. apresolve: `GET {apresolve}/?type=accesspoint&type=spclient`, тело до 64 КиБ, 10 с на весь запрос. Ответ — `{"accesspoint": ["host:port", …], "spclient": ["host:port", …]}`; разбирает его `SpotifySession.resolvedOf(body, knownSpclients)` (внутреннее, проверяется на `spec/fixtures/spotify/apresolve`). Пустые строки отбрасываются, адрес AP без хоста или с портом вне 1…65535 — тоже, адрес без порта — порт 443. Статус не смотрится, как у ПК (`session.cpp:120–138`): любое тело разбирается одинаково, а сбой сети — пустое тело. Пустой список AP — `ap.spotify.com:443`; хостов spclient нет — остаются прежние, а без прежних — `spclient.wg.spotify.com:443`. Не 200 пишется в лог.
2. Адреса по очереди: `java.net.Socket`, `connect` с таймаутом 10 с, `TCP_NODELAY`. Отказ соединения, обрыв до входа, `TryAnotherAP`, сбой протокола до входа (`ApEvent.Failure`: подпись, MAC, размер пакета) и молчание 20 с до входа — сразу к следующему адресу.
3. Вход — `AccessPoint` ядра с текущими `credentials`. На `APWelcome` сессия запоминает многоразовые данные (ими входит следующее соединение, SPOT-03, SPOT-18), зовёт `listener.loggedIn` и переходит в `Online`.
4. Отказ входа, кроме `TryAnotherAP`, — конец без повторов: `credentials = null`, `listener.refused(error)`, состояние из таблицы, `LoginRefused(code)` в `problems`, ждущие ключи падают.

### Потоки и отмена

- Всё, что трогает `AccessPoint` (ядро не потокобезопасно), идёт на одном потоке: `Dispatchers.IO.limitedParallelism(1)`. На нём же цикл соединения: `select` между куском из сокета и таймером. Таймер — `deadline()` ядра (keep-alive) или 20 с до входа; по нему зовётся `advance(now)`. Время — `System.nanoTime()`.
- Блокирующее чтение — своя корутина на `Dispatchers.IO`: куски до 16 КиБ уходят в `Channel` на 16 кусков. Запись — на потоке AP, прямо в поток сокета; ошибка записи закрывает сокет.
- `close()` сокета будит читающего. Так кончается соединение при обрыве, при пропаже сети (корутина-сторож ждёт `network == false` и закрывает сокет) и при отмене: `closingOnCancel` закрывает сокет, когда отменяют корутину соединения, в том числе посреди `connect` на потоке IO.
- `login` и `logout` синхронны: под замком они меняют поколение входа, слушателя, `credentials` и состояние, забирают из списка ждущие ключи и отменяют цикл прежнего входа; забранные ключи роняются уже вне замка. Отменённый цикл закрывает сокет на своём потоке чуть позже; что он успеет сделать до этого, проверяет поколение и ничего не меняет.
- Слушатель зовётся под тем же замком и до смены состояния. Поэтому кто увидел `Online` или `Expired`, видит и сохранённые или удалённые данные. А после возврата `logout()` слушатель прежнего входа уже не позовут: на этом стоит порядок `SpotifyAccount.logout()` (сначала сессия, потом хранилище).
- Ключи ждут ответа вне потока AP, на `CompletableDeferred`: поток AP только регистрирует запрос, шлёт его и отдаёт ответ.
- Запрос стоит в списке ждущих от регистрации до выхода из попытки: его убирает `finally`, который охватывает и саму регистрацию. Поэтому запрос, чьего вызывающего отменили сразу после регистрации (пока ответ `withContext` возвращался к нему), не остаётся в списке навсегда и не держит `keysWaiting` выше нуля — иначе правило фона перестало бы работать. Вход, выход и отказ входа убирают из списка всё, что роняют, и завершают ожидания вне замка: продолжение, которое запустится на месте (`Dispatchers.Main.immediate`), не изменит список посреди перебора.

### Ключи файлов

- `audioKey` делает до двух попыток. На ответ — 5 с. `AesKeyError` с кодом 2 (и любым, кроме 1) или молчание — ещё раз через 1 с, потом `KeyFailedException` и `KeyFailed` в `problems` (SPOT-16). Код 1 — сразу `KeyRefusedException` и `KeyRefused` (SPOT-17).
- Пока входа нет (`Connecting`, `Offline`), запрос ждёт его до 20 с с начала каждой попытки, потом — `SpotifyNetworkException` с причиной `SocketTimeoutException`. Вошли — запрос уходит сразу, ждавшие уходят на `APWelcome`.
- Запрос в полёте при обрыве соединения сразу падает `SpotifyNetworkException`, без второй попытки.
- Без входа: `NotLoggedInException` (`LoggedOut`, `Expired`), `LoginRefusedException(code)` (`PremiumRequired`, `Refused`). `logout` роняет ждущие запросы так же, новый `login` — сетевой ошибкой.
- Ответ на номер запроса, который уже не ждут (после таймаута), пропускается.

### Переподключение (SPOT-18)

- Перед новым кругом — пауза 2, 4, 8, 16, 32 с, потом раз в 60 с (`SessionTiming.reconnectMs`, последняя повторяется). Счёт начинается заново после входа и после возврата сети.
- Соединение, которое вошло и упало, — снова с первого адреса того же списка. Круг, в котором упали все адреса, — следующий начинается с apresolve.
- Нет сети (`network == false`) — `Offline`, ждём сеть; с её возвратом — попытка сразу, остаток паузы не ждётся. Пропажа сети при открытом соединении закрывает его.
- Протокол сдался (`ApEvent.Failure`: подпись, MAC, keep-alive) — `AccessPoint` в `problems`. После входа — пауза, как при обрыве. До входа — сразу к следующему адресу, как `TryAnotherAP`, а круг, где упали все, ведёт к apresolve. Это отступление от ПК: там сбой протокола в любой момент — пауза и снова первый адрес (`scheduleReconnect`), так что до других адресов и до apresolve дело не доходит никогда.

### Фон

Когда приложение не на экране и ничего не играет (`inUse == false`) и ни один ключ не ждёт, упавшее соединение не восстанавливается: `Offline`, без паузы и без попыток. Поднимает его `inUse == true` (возврат на экран, старт игры) или первый же запрос ключа — сразу, без паузы. Первое подключение после `login` правило не касается, а открытое соединение в фоне не закрывается: его рвут сеть, Doze или keep-alive. Держать соединение ради ничего — батарея. На ПК такого состояния нет; это оговорка «On Android…» в SPOT-18.

**Traps:**
- `network` и `inUse` должны быть `StateFlow` или другими потоками, которые не кончаются: сессия читает текущее значение через `first()`.
- `Online` приходит раньше страны и продукта. Кому нужна страна, ждёт `Online` с непустым `product` или читает `country` позже. Страна переживает переподключения и сбрасывается новым входом.
- До первого `APWelcome` входа по токену `credentials` — токен (`SPOTIFY_TOKEN`), а с ним login5 не работает: `SpotifyWeb` отвечает `NotLoggedInException`.
- Молчание AP до входа на ПК ничем не ограничено; здесь 20 с (`handshakeTimeoutMs`), иначе AP, которая приняла TCP и молчит, держала бы сессию вечно.
- `limitedParallelism(1)` — один поток за раз из пула IO, а не один и тот же поток: ядру нужна только последовательность вызовов.

## `SpotifyWeb`

```kotlin
data class WebRequest(
    val path: String, val method: String = "GET", val body: Bytes? = null,
    val contentType: String = PROTOBUF, val accept: String = PROTOBUF,
    val accountQuery: Boolean = true,              // product=0&country=…&salt=…
    val label: String = path.substringBefore('?'), // что попадёт в лог и в исключения
)
class SpotifyWeb(
    client: OkHttpClient, session: SpotifySession, identity: SpotifyIdentity, endpoints: Endpoints = Endpoints(),
    clock: () -> Long = System::currentTimeMillis,       // сроки токенов
    pause: suspend (Long) -> Unit = { delay(it) },       // ожидание Retry-After
    maxBodyBytes: Long = MAX_BODY_BYTES,                 // 8 МиБ
    solver: CoroutineDispatcher = Dispatchers.Default,   // hashcash
    elapsedNanos: () -> Long = System::nanoTime,         // предел hashcash
    log: (String) -> Unit = {},
) {
    val problems: SharedFlow<SpotifyProblem>             // Http(status), Content
    suspend fun spclient(request: WebRequest): ByteArray // тело ответа 2xx
    suspend fun pathfinder(query: String): ByteArray     // POST {pathfinder}/pathfinder/v2/query, JSON
    fun report(problem: SpotifyProblem)                  // для тех, кто разбирает ответы (SpotifyLibrary, SpotifyFiles)
    fun unreadable(method: String, label: String, detail: String): SpotifyContentException   // в лог и Content в problems; исключение бросает вызывающий
}
```

**Токены.**
- client-token: `POST {clientToken}/v1/clienttoken`, `Accept` и `Content-Type` — `application/x-protobuf`, `User-Agent`; тело — `Login.clientTokenRequest(identity.clientToken)`. Вызов hashcash решается с пустым контекстом и отвечается тем же адресом (`Login.clientTokenAnswer(state, suffix)`), не больше трёх решений. Токен живёт `refresh_after_seconds` − 60 с.
- login5: `POST {login5}/v3/login` с заголовком `client-token`; тело — `Login.login5Request(CLIENT_ID, deviceId, credentials, context, solution)`. Нужны многоразовые данные сессии (`STORED_CREDENTIALS` и непустые), иначе — `NotLoggedInException`. Вызов hashcash решается с контекстом ответа (`login_context`), и следующий запрос несёт контекст, решение и сколько оно заняло; не больше трёх решений. Bearer живёт `expires_in` − 60 с и принадлежит имени пользователя: вход под другим именем берёт новый. ПК при выходе сбрасывает токены (`web->forgetTokens()`); здесь `SpotifyAccount` не знает `SpotifyWeb`, и аккаунты разделяет эта проверка (`SpotifyWebTest`: после выхода и входа под другим именем bearer прежнего не уходит). Ошибка login5 (`error`) — `LoginRefusedException(code, "/v3/login")` и `Http(401)` в `problems`, как у ПК.
- Выдача токенов — под `Mutex`: запросы, пришедшие во время выдачи, ждут её. Неудачу выдачи, как у ПК (`finishTokens`), получают все, кто её ждал под тем же именем пользователя, и в `problems` от неё одно событие. Кто ждал, узнаёт по счётчику законченных выдач: он запомнил его до замка, и если за это время выдача кончилась неудачей для того же имени, получает её исключение без новой выдачи. Запрос другого аккаунта (выход и вход, пока шла выдача) выдаёт токены сам. `NotLoggedInException` не делится никогда: это местная проверка данных входа (токен до `APWelcome`), а не ответ Spotify, и запрос, пришедший уже с многоразовыми данными, выдаёт bearer сам. Заново выдачу начинает только запрос, пришедший после неудачи. Выдача, чьего вызывающего отменили, неудачей не считается: следующий ждавший пробует сам.
- Hashcash решается на `solver` (`Dispatchers.Default`) и бросается через `Hashcash.GIVE_UP_AFTER_MS` (5 с) или при отмене корутины — `SpotifyContentException` и `Content`. Время решения на слабом телефоне — #118.

**Запрос к spclient.**
- Хост — первый из `session.spclients` (до apresolve — `spclient.wg.spotify.com:443`), схема — `endpoints.spclientScheme`.
- Заголовки: `Authorization: Bearer <login5>`, `client-token`, `User-Agent`, `Accept` запроса; `Content-Type` — у запросов с телом (у `GET` и `HEAD` тела нет).
- При `accountQuery` в запрос после его собственных параметров добавляются `product=0`, `country=<session.country>` и `salt=<случайное uint32>`. У `context-resolve` и Pathfinder — `accountQuery = false`.
- 401 — забыть bearer (если он всё ещё тот же) и послать ещё раз с новым. 429 с `Retry-After` не больше 5 с (без заголовка — 0, как у ПК) — ещё раз после паузы. Повтор один на запрос, какой бы ни была причина.
- Остальные ответы не 2xx — `SpotifyHttpException(status, method, label)` и `Http(status)` в `problems`; так же у ответов client-token и login5. Сеть — `SpotifyNetworkException`, в `problems` не идёт (как у ПК).
- Тело читается с пределом, как у LRCLIB, а не `body.string()`: `Content-Length` больше предела или больше байт, чем предел, — `SpotifyContentException` и `Content`. Предел — 8 МиБ у spclient и Pathfinder, 64 КиБ у токенов.

**Traps:**
- `label` уходит в лог и в исключения. Путь с именем пользователя обязан получить `label` без него: у rootlist это `/playlist/v2/user/{username}/rootlist`, у следующей страницы контекста — последний сегмент `{page}` (станция Liked Songs — `spotify:station:user:<имя>:collection`).
- login5 бесполезен до входа в AP с токеном: ему нужны многоразовые данные (та же ловушка у ПК). У сохранённого входа они есть с самого начала, и запросы идут, пока AP ещё подключается.
- Сроки токенов — по часам стены (`System.currentTimeMillis`), как у ПК: после глубокого сна устаревший bearer лечится ответом 401.

## `PairLogin` (SPOT-01)

```kotlin
class PairLogin(client: OkHttpClient, endpoints: Endpoints = Endpoints(), clock: () -> Long = System::currentTimeMillis, pause: suspend (Long) -> Unit = { delay(it) }) {
    data class Code(val deviceCode: String, val userCode: String, val verificationUrl: String, val intervalMs: Long, val deadlineMs: Long)
    suspend fun requestCode(): Code
    suspend fun waitForToken(code: Code): String   // токен OAuth для SpotifyAccount.login
}
```

RFC 8628, как `yandex/DeviceAuth`. `requestCode`: `POST {accounts}/oauth2/device/authorize`, форма `client_id`, `scope=streaming`. Из ответа — `device_code`, `user_code`, страница `verification_uri_complete` (иначе `verification_uri`, иначе `https://spotify.com/pair`; не `http(s)://` — следующий вариант), `interval` (по умолчанию 5 с, не меньше 1) и `expires_in` (по умолчанию 600 с). `waitForToken` ждёт `interval` и шлёт `POST {accounts}/api/token` с формой `grant_type=urn:ietf:params:oauth:grant-type:device_code`, `device_code`, `client_id`: `authorization_pending` — ждать дальше, `slow_down` — интервал на 5 с длиннее с этого раза, `access_token` — готово. Любая другая ошибка (`access_denied`, `expired_token`) или истёкший срок кода — `AuthorizationException(error)`, ничего не сохраняется. Отмена корутины (диалог закрыли) обрывает и ожидание, и запрос.

Отказы разбора общие с `BrowserLogin`: поле `error` в ответе — `AuthorizationException`, иначе статус не 2xx — `SpotifyHttpException`, иначе нет нужных полей — `SpotifyContentException`. Ответ — JSON до 64 КиБ. `Code.toString()` не печатает коды.

## `BrowserLogin` (SPOT-02)

```kotlin
class BrowserLogin(client: OkHttpClient, endpoints: Endpoints = Endpoints(), random: SecureRandom = SecureRandom(), readTimeoutMs: Int = 10_000) {
    data class Pages(val loggingIn: String, val refused: String)   // тексты страницы для браузера: из R.string (ui/SpotifyViewModel)
    suspend fun login(pages: Pages, openPage: (String) -> Unit): String   // openPage — открыть адрес в браузере; результат — токен OAuth
    companion object { fun challengeOf(verifier: String): String }        // base64url(SHA-256(verifier)) без '='
}
```

1. `ServerSocket` на свободном порту `127.0.0.1` с очередью 1, на `Dispatchers.IO`.
2. Verifier PKCE — 64 знака, `state` — 24 знака из `A–Z a–z 0–9 - . _ ~` (`SecureRandom`).
3. `openPage` получает `{accounts}/authorize?response_type=code&client_id=…&redirect_uri=http://127.0.0.1:<port>/login&scope=streaming&code_challenge_method=S256&code_challenge=…&state=…`. Открывает её `ui` через `Intent.ACTION_VIEW`.
4. Каждое соединение читается в своей корутине: заголовки до 16 КиБ или до пустой строки, 10 с на чтение. Сокет соединения закрывается, когда корутина кончится (`invokeOnCompletion`), — и тогда, когда вход кончился раньше, чем она успела стартовать. Считается только `GET /login`, на остальное (favicon, `POST`) — `404` и ожидание дальше. Переход с `code` и своим `state` — страница `pages.loggingIn`; с `error`, чужим `state` или без `code` — страница `pages.refused` и `AuthorizationException` (`error`, `state_mismatch` или `no_code`): ничего не сохраняется, токен не спрашивается. Страница — `200`, `text/html; charset=utf-8`, текст экранируется, `Connection: close`.
5. Порт закрывается сразу после перехода, отмены или ошибки; потом `POST {accounts}/api/token` с формой `grant_type=authorization_code`, `code`, `redirect_uri`, `client_id`, `code_verifier` даёт `access_token`.

**Traps:**
- `redirect_uri` — только `http://127.0.0.1:<port>/login`: другого Spotify для ID десктопа не принимает. Поэтому вход с другого устройства — кодом (SPOT-01).
- Пока пользователь в браузере, Android может убить процесс: порт и ожидание пропадают, вход — заново (оговорка SPOT-02).
- Вернуть приложение на передний план из фона нельзя (ограничения запуска активностей с Android 10): пользователь возвращается сам, страница `loggingIn` так и пишет.
- `accounts.spotify.com` может открыться в приложении Spotify, если оно стоит (не проверено, #118).

## `SpotifyAccount`

```kotlin
interface SavedCredentials { fun load(): Credentials?; fun save(credentials: Credentials); fun forget() }
class SpotifyAccount(session: SpotifySession, saved: SavedCredentials) {
    val state: StateFlow<SpotifyState>   // состояние сессии: «вошли как …», «вход истёк», «нужен Premium»
    fun hasSavedLogin(): Boolean
    fun start()                          // при запуске: сохранённый вход → session.login, нет — ничего
    fun login(accessToken: String)       // токен из PairLogin или BrowserLogin
    fun logout()
}
```

- Хранилище — интерфейс, как `AccountGateway` у `yandex/Session`. В приложении это корневой `SpotifySavedCredentials` над `data/SpotifyStore`; `data` и `spotify` друг друга не импортируют.
- Сохранённым входом считаются только `STORED_CREDENTIALS` с непустым именем и данными; токен (`SPOTIFY_TOKEN`) не загружается и не сохраняется.
- SPOT-03: на каждый `APWelcome` многоразовые данные сохраняются, если они другие, чем в хранилище; токен нигде не хранится.
- SPOT-04: `start()` входит сохранёнными данными без диалога. `BadCredentials` удаляет их (`Expired`); сбой сети их не трогает, сессия повторяет, как ERR-02.
- SPOT-05: `PremiumAccountRequired` ничего не сохраняет. Сохранённый вход, которому отказали так, остаётся (как у ПК): следующий запуск попробует снова.
- SPOT-06: `logout()` — `saved.forget()`, `session.logout()` (соединение закрывается, состояние — `LoggedOut`, ждущие ключи падают `NotLoggedInException`) и ещё раз `saved.forget()`. Порядок важен. Первое `forget()` — до `LoggedOut`: сборщик `state` на `Dispatchers.Main.immediate` (`ui/SpotifyViewModel`) получает `LoggedOut` прямо внутри `session.logout()`, если выход идёт с главного потока, и должен уже не видеть сохранённого входа (`SpotifyAccountTest`: при первом `forget()` состояние ещё `Online`). Второе — для `APWelcome`, пришедшего между ними: слушатель сохраняет многоразовые данные на каждом `APWelcome`, если в хранилище другие, а после `session.logout()` (новое поколение, `listener = null` под тем же замком) старых вызовов слушателя уже не бывает.

## Каталог: `SpotifyLibrary`

```kotlin
class SpotifyLibrary(
    web: SpotifyWeb,
    session: SpotifySession,
    timeZone: () -> String = { TimeZone.getDefault().id },   // для «Для вас»; тесты подают свой
    maxPages: Int = MAX_PAGES,                               // 1000 страниц коллекции или списка; тесты ставят меньше
) {
    val username: String                                     // имя из session.credentials; "" без входа
    val likedSongsUri: String                                // spotify:user:<username>:collection — контекст автоплея Liked Songs
    suspend fun tracks(uris: List<String>): List<Track>      // TRACK_V4 в порядке uris, с повторами
    suspend fun likedTracks(): List<Track>                   // SPOT-07
    suspend fun playlists(): List<SpotifyItem>               // SPOT-08
    suspend fun playlistTracks(uri: String): List<Track>
    suspend fun savedAlbums(): List<SpotifyItem>             // SPOT-09
    suspend fun followedArtists(): List<SpotifyItem>
    suspend fun albumTracks(uri: String): List<Track>
    suspend fun artistTopTracks(uri: String): List<Track>
    suspend fun forYou(): List<SpotifyItem>                  // SPOT-10
    suspend fun search(text: String): SpotifyFound           // SPOT-11
    suspend fun searchPick(text: String): SpotifyPick        // SPOT-11: лучший исполнитель, альбом или найденные треки
    suspend fun station(trackUri: String): ContextPage       // SPOT-12
    suspend fun autoplay(contextUri: String, recentTrackUris: List<String>): ContextPage   // SPOT-20
    suspend fun nextPage(pageUrl: String): ContextPage       // hm://… из ContextPage.next
}
data class SpotifyItem(val uri: String, val name: String)
data class SpotifyFound(kind: Kind = TRACKS, best: SpotifyItem? = null, trackUris: List<String>, artists: List<SpotifyItem>, albums: List<SpotifyFoundAlbum>) { enum class Kind(key) { ARTIST, ALBUM, TRACK, TRACKS } }   // key — как kind в expected/spotify
data class SpotifyFoundAlbum(uri: String, name: String, artists: List<String>, year: Int? = null, single: Boolean = false)   // year null — нет или 0
data class SpotifyPick(val best: SpotifyItem?, val contextUri: String, val tracks: List<Track>)   // best == null — найденные треки
data class ContextPage(val tracks: List<Track>, val next: String)                                  // next == "" — страница последняя
```

Порт `Library` ПК (`src/sources/spotify/library.cpp`). Каждый вызов спрашивает Spotify заново, ничего не запоминается. Пакет не показывает текстов: заголовки «Liked Songs», «Search: <текст>», «<трек>: radio» и статусы — у очереди и `ui/SpotifyViewModel`; имена плейлистов, альбомов и исполнителей — данные с сервера. Отмена корутины отменяет все запросы вызова.

| Выбор | Запрос | Что берётся |
|---|---|---|
| `tracks(uris)` | `POST /extended-metadata/v0/extended-metadata` (`Metadata.request`, `TRACK_V4`), `product`, `country`, `salt` | по 100 URI на запрос, все пачки сразу, каждый URI один раз; ответ сопоставляется по URI, а список — в порядке `uris`, с повторами (трек дважды в плейлисте играет дважды, `library.cpp:246–261`). Сущность не 200 или без тела и тело, которое не разбирается, — трек выпадает |
| SPOT-07 `likedTracks` | `POST /collection/v2/paging`: тело `CatalogMessages.collectionPageRequest(username, "collection", token, 300)`, `Content-Type` и `Accept` — `application/vnd.collection-v2.spotify.proto`, без `product`, `country`, `salt`; следующая — с токеном ответа, пока не `last` | `spotify:track:` элементы в порядке Spotify (новые первыми) |
| SPOT-08 `playlists` | `GET /playlist/v2/user/<имя>/rootlist?decorate=revision,attributes,length,owner,capabilities,status_code&from=<n>&length=1000`, `product`, `country`, `salt`; имя — percent-encoding RFC 3986 (всё, кроме `A–Z a–z 0–9 - . _ ~`), как `QUrl::toPercentEncoding` | `spotify:playlist:` элементы (`CatalogMessages.playlistsOf`): маркеры папок отпадают, папки сплющиваются |
| `playlistTracks(uri)` | `GET /playlist/v2/playlist/<id>?decorate=revision,attributes,length,owner&from=<n>&length=1000`, `product`, `country`, `salt`; `<id>` — третья часть URI через `:` | `spotify:track:` элементы, потом `tracks`; эпизоды и свои файлы не спрашиваются |
| SPOT-09 `savedAlbums`, `followedArtists` | коллекция: `spotify:album:` элементы набора `collection` (набора `albums` нет: 403), набор `artist`; имена — `ALBUM_V4`, `ARTIST_V4` (`library.cpp:424–449`) | без метаданных, с телом, которое не разбирается, или с пустым именем — выпадают |
| `albumTracks(uri)` | `ALBUM_V4` | треки диск за диском в порядке полей 11; альбом без метаданных — пустой список без ошибки |
| `artistTopTracks(uri)` | `ARTIST_V4` | `Metadata.topTracksIn(artist, session.country)`, первые 10 (`kTopTracks`) |
| SPOT-10 `forYou` | Pathfinder `home`: `homeEndUserIntegration` `INTEGRATION_DESKTOP`, `timeZone` (пустой — `UTC`), `sp_t` и `facet` пустые, `sectionItemsLimit` 20, `includeEpisodeContentRatingsV2` | плейлисты раздела «Made For You» (`CatalogJson.madeForYou`) |
| SPOT-11 `search(text)` | Pathfinder `searchDesktop`: `searchTerm` — обрезанный текст, `offset` 0, `limit` 50, `numberOfTopResults` 5, пять `include…` — `false`. Пустой текст — без запроса, `SpotifyFound()` | `CatalogJson.found` |
| `searchPick(text)` | `search`, потом `artistTopTracks` или `albumTracks` лучшего исполнителя или альбома, иначе (лучший трек тоже) `tracks` найденных | `contextUri` — URI лучшего или первого найденного трека (`catalog.cpp:101–115`), `""` без треков. Пустые `tracks` (или ни одного доступного) — «ничего не найдено» (SRC-12): это решает вызывающий |
| SPOT-12 `station(trackUri)` | `GET /context-resolve/v1/spotify:station:track:<id>`, `Accept: application/json`, без `product`, `country`, `salt` | первая страница контекста, её `next` |
| SPOT-20 `autoplay(context, recent)` | `POST /context-resolve/v1/autoplay`, тело — protobuf `{1 context_uri, 2 recent_track_uri…}` (`CatalogMessages.autoplayRequest`, вектор `messages.json`), `Content-Type: application/x-protobuf`, `Accept: application/json`, без `product`, `country`, `salt` | то же. `recent` отбирает вызывающий: начавшиеся треки Spotify, старые первыми, до 50 (корневой `SpotifyContinuations.recentUris`) |
| `nextPage(url)` | `GET` на spclient по пути `url` без `hm:/` (`hm://radio-apollo/v5/…` → `/radio-apollo/v5/…`), с его запросом как есть, без `product`, `country`, `salt` | страница контекста. Не `hm://` — `SpotifyContentException` без запроса и без `Content`, как у ПК |

`label` запросов (лог и исключения, «Лог» ниже) — путь без запроса, кроме двух: `/playlist/v2/user/{username}/rootlist` и у `nextPage` — путь без последнего сегмента и `/{page}`.

### Pathfinder

Тело запроса — `{"variables": {…}, "operationName": "<операция>", "extensions": {"persistedQuery": {"version": 1, "sha256Hash": "<хеш>"}}}`: Spotify знает запрос по хешу. Хеши — константы `SpotifyLibrary.HOME_HASHES` (один) и `SEARCH_HASHES` (четыре), новые первыми, те же, что `kHomeHashes` и `kSearchHashes` в `library.cpp:38–46` ПК (b99c1df). В спеку они не вынесены: это деталь клиента, а не поведение.

Ответ разбирается в четыре шага, как у ПК (`library.cpp:499–512`, «Pathfinder» в `expected/spotify/README.md`):

1. **Устарел:** `SpotifyHttpException` со статусом 400, 405 или 412 от самого Pathfinder (путь `/pathfinder/v2/query`; отказ выдачи токена с тем же статусом — не он) или ответ 2xx, у которого `data` не объект. Есть следующий хеш — спрашиваем с ним. Хешей нет — `QueryOutdated` в `problems`, и дальше шаги 2 и 3.
2. Другой статус не 2xx — `SpotifyHttpException`, тело не читается (`500-with-data`). Сеть — `SpotifyNetworkException`, к следующему хешу не переходит.
3. 2xx без `data`-объекта (сюда доходит только устаревший без хешей) — `QueryOutdatedException(operation)`.
4. Иначе — `data`; поиск без `searchV2` и главная без `home.sectionContainer` — `SpotifyContentException` и `Content`.

Каждый ответ не 2xx `SpotifyWeb` сообщает и сам — `Http(status)`, в том числе устаревшие 412, как `reportFailed` у ПК.

**Как обновить хеши.** Когда Spotify обновляет свои клиенты, хеши устаревают: «Для вас» и поиск падают («HTTP 412» или «непонятный ответ сервера»), а телеметрия показывает `error spotify/query-outdated`.
1. Новый хеш операции — в веб-плеере `open.spotify.com` (DevTools → Network → запрос `pathfinder/v2/query` с нужным `operationName`, поле `extensions.persistedQuery.sha256Hash` тела) или у клиентов на librespot.
2. Хеш дописывается первым в оба списка: `kHomeHashes`/`kSearchHashes` ПК и `HOME_HASHES`/`SEARCH_HASHES` здесь; старые остаются за ним — их спросят, только если новый отвергнут. Оба приложения — в одном выпуске.
3. Если с новым хешем меняются переменные или форма ответа, это изменение спеки (`fixtures/spotify/pathfinder-*` и `expected/`) и разбора `CatalogJson` в обоих приложениях. Тесты берут хеши из тех же констант, перезаписывать в них нечего.

**Traps:**
- Страна аккаунта приходит с AP чуть после входа (`SpotifySession.country`); топ исполнителя до неё — по `""`, затем мировой (ловушка ПК).
- `searchPick` для лучшего исполнителя или альбома не смотрит найденные треки: у пустого топа исполнителя (страна, где топа нет и мирового тоже) выбор пуст, хотя треки нашлись. Так же у ПК.
- Коллекция и список читаются страница за страницей, пока сервер говорит «ещё», но не больше `maxPages` (1000) страниц: дальше — `SpotifyContentException` («a collection of more than 1000 pages», «a list of more than 1000 pages») и `Content`. Это сверх ПК, где предела нет и сервер, который всегда отвечает токеном или огромным `length`, держит цикл вечно. 1000 страниц — 300 000 сохранённых треков или 1 000 000 записей списка.

## `SpotifyTracks`

```kotlin
object SpotifyTracks {
    const val COVER_HOST = "https://i.scdn.co/image/";  const val COVER_PIXELS = 400;  const val ANY_BITRATE_KBPS = 320
    fun trackOf(track: SpotifyTrack): Track
    fun isAvailable(track: SpotifyTrack): Boolean           // есть Ogg Vorbis, свой или у замены (SPOT-15)
    fun coverUrlOf(covers: List<Image>, pixels: Int): String?
    fun uriOf(track: Track): String                         // spotify:track:<id>
}
```

`trackOf` — порт `TrackOf` ПК (`tracks.cpp`), и только он строит `Track` трека Spotify:
- `id` — base62 gid, `title` — имя, `artists` — имена всех исполнителей по порядку (как у ПК, и пустые), `durationMs`;
- `albumId` пуст, `source = TrackSource.SPOTIFY`, `bitrateKbps = 0` (битрейт файла сообщает `playback/SpotifyDataSource`, когда готовит файл);
- `available` — `Metadata.pickOggVorbis(track, 320) != null`: трек без Ogg Vorbis, свой или у замены, остаётся в списке недоступным (SPOT-15, SRC-05);
- `coverUrl` — `coverUrlOf(album.covers, 400)`: `https://i.scdn.co/image/<hex>` наименьшей обложки не меньше 400 px, иначе самой большой; среди равных — первая; без обложек — `null` (SPOT-19). 400 — как `kCoverPixels` ПК и 400×400 Яндекса.

Альбом, год и обложки других размеров `Track` не хранит; тест сравнивает их по `SpotifyTrack`.

## `CatalogJson`

```kotlin
object CatalogJson {
    const val MADE_FOR_YOU = "spotify:section:0JQ5DAUnp4wcj0bCb3wh3S"
    fun context(body: ByteArray): ContextUris?               // null — {"loading": true} или не объект
    fun pathfinderData(body: ByteArray): JsonObject?         // data ответа Pathfinder; null — не объект
    fun madeForYou(data: JsonObject): List<SpotifyItem>?     // null — нет home.sectionContainer
    fun found(data: JsonObject): SpotifyFound?               // null — нет searchV2
}
data class ContextUris(val trackUris: List<String>, val next: String)
```

Порт `ContextOf`, `MadeForYouOf`, `FoundOf` ПК; правила — «Contexts» и «Pathfinder» в `expected/spotify/README.md`. Поле, которого нет или у которого другой тип, — пустое, как `toString()`, `toObject()`, `toArray()` у `QJsonValue`: так поиск, где `searchV2` есть, но не объект, — «ничего не найдено», а не ошибка. Только `contains` различает «нет» и «пусто»: `pages`, `data` обёртки, `searchV2`, `sectionContainer`.

- Контекст: при `pages` читается первая страница, иначе сам корень; `trackUris` — `uri` из `tracks` с `spotify:track:`, `next` — `next_page_url` или `""`.
- Главная: плейлисты — `sectionItems.items[].content.data` раздела `MADE_FOR_YOU` с `__typename` `Playlist`; без раздела — пустой список.
- Поиск: `trackUris` — `tracksV2.items[].item.data.uri` (или `item.uri`, когда у `item` нет `data`) с `spotify:track:`; лучший — только `topResultsV2.itemsV2[0]`: `Artist` с `spotify:artist:` — `ARTIST` (имя — `profile.name`), `Track` с `spotify:track:` — `TRACK` (имя — `name`), `Album` с `spotify:album:` — `ALBUM`, иначе `TRACKS` без лучшего. Исполнители — `artists.items[]` (через `item`, когда он есть, затем `data`) с `spotify:artist:`: `uri` и `profile.name`; альбомы — так же `albumsV2.items[]` с `spotify:album:`: `uri`, `name`, `profile.name` каждого из `artists.items`, `date.year` (не число, дробное или 0 — `null`), `single` при `type` `SINGLE`. Форма `artists` и `albumsV2` написана по веб-клиенту Pathfinder и в живом ответе не проверена (SPOT-11 «not checked»).

## `SpotifyFiles`

```kotlin
class SpotifyFile(val trackId: String, val fileId: Bytes, val kbps: Int, val key: Bytes, val urls: List<String>)   // toString — без ключа и ссылок
class SpotifyFiles(
    web: SpotifyWeb, session: SpotifySession,
    bitrates: () -> List<Int>,                 // действующие, по порядку; строит playback/SpotifyBitrates
    clock: () -> Long, ttlMs: Long = TTL_MS, capacity: Int = CAPACITY, log: (String) -> Unit = {},
) {
    val userAgent: String                                      // SpotifyWeb.userAgent, для запросов к CDN
    suspend fun prepare(trackId: String): SpotifyFile          // первое открытие игры
    suspend fun storageUrls(file: SpotifyFile): List<String>   // storage-resolve того же файла ещё раз
    fun askedInOtherQuality(trackId: String): Boolean          // QUAL-05
    fun report(problem: SpotifyProblem)                        // в SpotifyWeb.problems
    companion object {
        const val TTL_MS = 15 * 60 * 1_000L; const val CAPACITY = 64
        const val STORAGE_RESOLVE_PATH = "/storage-resolve/files/audio/interactive/"
        fun pick(track: SpotifyTrack, bitrates: List<Int>): PlayableFile?
    }
}
```

`prepare(id)` — порт `FileDownload::prepare` ПК (`file_download.cpp`), по шагам SPOT-13. Сначала, до кэша, — вход: `SpotifySession.loggedInUsername()` с логикой проверки ключа (`credentials` нет — `NotLoggedInException`, отказ AP, кроме `BadCredentials`, — `LoginRefusedException`), как `hasAccount()` у каждого открытия ПК (`src/sources/spotify/source.cpp:72–74`). Пока AP переподключается, `credentials` не сбрасываются, так что ложного «нет входа» нет.
1. `POST /extended-metadata/v0/extended-metadata` с `TRACK_V4` одного URI (`SpotifyLibrary.METADATA_PATH`). Конверт, который не читается, — `unreadable`: `SpotifyContentException` и `Content`. Сущность не 200, без тела или с телом, которое не разбирается, — `NoPlayableFileException(id, "no metadata to play")`, как трек без файла.
2. Файл — `pick(track, bitrates)`: первый битрейт списка, который у трека есть точно, а без него — ближайший к первому Ogg Vorbis (`Metadata.pickOggVorbis`). Ищется среди своих файлов трека, а когда своих Ogg Vorbis нет, — у замен по порядку. MP3, AAC и FLAC не берутся никогда; без Ogg Vorbis — `NoPlayableFileException(id)` (SPOT-15). Списки — `playback/SpotifyBitrates`: стандарт `[320, 160]`, экономия `[96, 160]`, дальше любой Ogg Vorbis. Список, а не `yandex.Quality`: пакет не импортирует `yandex`.
3. Ключ — `SpotifySession.audioKey(gid трека, у которого файл, id файла)`; у трека с заменой это gid замены (`PlayableFile.track`). Отказы идут как есть: `KeyRefusedException` (SPOT-17), `KeyFailedException` (SPOT-16), `NotLoggedInException`, `LoginRefusedException`, `SpotifyNetworkException` (вход AP не дождались, SPOT-18).
4. `GET /storage-resolve/files/audio/interactive/<hex id файла>` через spclient. Ответ, который не читается, — `unreadable` (`Content`, трековый сбой; ПК — `file_download.cpp:155–160`). Результат не 0 (storage, restricted) или без ссылок — `NoPlayableFileException(id, "no CDN link to its file: …")`, без `Content`: ответ прочитан (правило `usable`, `expected/spotify/README.md`). Ссылки — в порядке ответа; строка, которая не URL `http(s)` (`toHttpUrlOrNull`), отбрасывается, и если не осталось ни одной — тоже `NoPlayableFileException`.

В `log` — одна строка на подготовку: `the file of track <id>: Ogg Vorbis <kbps> kbps, <n> CDN links`.

**Кэш на процесс**, как `playback/TrackUrlCache` у Яндекса: ключ — имя входа, трек и список битрейтов, 64 записи по давности использования, 15 минут от начала подготовки; часы — `SystemClock.elapsedRealtime` (`AppGraph`). Попадание отдаёт тот же `SpotifyFile` без запросов. Упавшая подготовка не кэшируется. Так частые первые открытия одного трека (следующий круг, следующая строка, `reload`, повтор после ожидания сети) обходятся без метаданных, ключа и `storage-resolve`. Запись прежнего аккаунта новому не отдаётся: у него другое имя входа, и его подготовка спрашивает свой ключ (SPOT-17 «until the account changes»); старые записи уходят по давности. Ключ файла не меняется; ссылки CDN, которые истекли раньше 15 минут (срок не проверен), `SpotifyStream` спросит заново сам.

**Какие битрейты просили** (QUAL-05). Карта `asked: trackId → битрейты` той же ёмкости. `prepare` пишет её в том же замке, где читает `bitrates()`, до сети: попадание в кэш, идущая и упавшая подготовка тоже считаются. `askedInOtherQuality(id)` — `true`, если последняя подготовка трека просила не те битрейты, что действуют сейчас; трек, который не готовили (или вытесненный), — `false`. Подготовка либо записала старые битрейты до проверки, либо прочла уже новые; середины нет.

`storageUrls(file)` — шаг 4 для готового файла (второй `storage-resolve`, SPOT-14 на Android). Новые ссылки он пишет и в записи этого файла у текущего входа (возраст записи прежний), так что следующая игра трека в пределах 15 минут не проходит истёкшие ссылки заново.

**Traps:**
- Сигнатура в плане — `prepare(id, bitrates)`. Здесь битрейты — поставщик в конструкторе, и `prepare` читает его в замке кэша: иначе смена качества между чтением битрейтов вызывающим и записью `asked` осталась бы незамеченной, как избегает и `TrackUrlCache`.
- Ключ кэша — список целиком: экономия — другая запись, и новый старт в новом качестве не трогает файл игры, которая играет в старом.
- Тест — `SpotifyFilesTest` на `support/SpotifyStack` (фальшивая AP и mock-сервер) и фикстурах `storage-resolve-files-audio-interactive`.

## `SpotifyStream`

```kotlin
interface ReadGuard {
    fun <T> during(call: Call?, block: () -> T): T
    companion object { val NONE: ReadGuard }                       // просто block()
}
class SpotifyStream(
    calls: Call.Factory, file: SpotifyFile, userAgent: String,
    relink: () -> List<String>,                                    // второй storage-resolve
    guard: ReadGuard = ReadGuard.NONE, report: (SpotifyProblem) -> Unit = {}, log: (String) -> Unit = {},
) : Closeable {
    val oggLength: Long                                          // длина Ogg файла; UNKNOWN, пока CDN не сказал
    fun open(oggPosition: Long, length: Long = UNKNOWN): Long     // сколько байт Ogg осталось; UNKNOWN — CDN не сказал
    fun read(buffer: ByteArray, offset: Int, length: Int): Int    // END в конце
    override fun close()
    companion object { const val UNKNOWN = -1L; const val END = -1; val EXPIRED = setOf(403, 410) }
}
```

Байты одного файла с CDN: порт второй половины `FileDownload` ПК (`start`, `fetchFrom`, `deliver`), но диапазонами по запросу плеера, а не целиком. Чистая JVM на OkHttp, без Media3 и `OkHttpDataSource`, поэтому весь перебор ссылок проверяется на `MockWebServer`. Переходник к Media3 — `playback/SpotifyDataSource`; он же отдаёт `CallTracker` и `ReadGuard`, через которые работает защита от зависшего чтения (TR-09). Один поток — одна игра: файл, ключ и ссылки её. Загрузки игры идут по одной, поэтому замков нет.

- **Позиции.** Байт `p` Ogg — байт `p + 167` файла. `open(p, n)` просит `Range: bytes=<p+167>-`, с длиной — `bytes=<p+167>-<p+167+n−1>`; `User-Agent` — `userAgent` (тот же, что у spclient, как на ПК). Расшифровка — `AudioDecryptor.apply` с абсолютного смещения файла, поэтому любой ответ расшифровывается со своего первого байта, а заголовок Spotify наружу не попадает.
- **Длина.** У 206 — число после `/` в `Content-Range`, у 200 — `Content-Length`: длина файла. Без итога (`Content-Range: bytes a-b/*`) на открытый диапазон `bytes=p-` длина файла — `p + Content-Length` ответа (ревью PR #140), а у открытия с длиной итог остаётся неизвестным: конец диапазона — не конец файла. `oggLength` — длина файла без заголовка или `UNKNOWN`. `open` отдаёт остаток от позиции, а у открытия с длиной — её; без длины файла — `UNKNOWN`, и конец ответа — конец файла. Как у `DataSource.open`: открытие ровно в конце известного файла — 0 без запроса и сразу `END`, за концом — `require` (переходник проверяет раньше и бросает `POSITION_OUT_OF_RANGE`), диапазон за концом кончается с файлом.
- **200 на запрос диапазона** — ответ с начала файла: поток сам пропускает байты до нужного в первом чтении (ловушка ПК, `src/spotify/client/README.md:117–118`).
- **Сбой ссылки** (SPOT-14): сеть (`IOException` открытия или чтения, в том числе `SocketTimeoutException`) или статус не 200/206, в том числе 401 и 403 — это не ERR-08. Поток берёт следующую ссылку списка, по кругу, и продолжает с того же байта. Ответ, который кончился раньше конца без ошибки (206 короче, чем просили), продолжается на той же ссылке с байта, где кончился, если принёс хоть байт; пустой — сбой ссылки. Ответ, который принёс байты и оборвался с ошибкой, — тоже не сбой ссылки (второе ревью PR #140): поток идёт к следующей ссылке с того же байта, не считая сбоя и не снимая признак «все истекли». Так продолжение после долгой паузы, когда CDN закрыл молчащий ответ, а ссылки истекли, доходит до второго `storage-resolve`, и с одной ссылкой тоже; при настоящем обрыве сети следующие ссылки упадут сами.
- **Все упали.** Сбои считаются с последнего прочитанного байта и с каждого `open` заново. Когда их столько же, сколько ссылок: если все были ответами 403 или 410 и в этой серии `relink` ещё не звали, поток спрашивает `relink()` (второй `storage-resolve`) и идёт по новым ссылкам с того же байта; они остаются для следующих открытий. Это оговорка «On Android…» в SPOT-14: перемотка после долгой паузы идёт по ссылкам, которые истекли. Иначе итог: если в серии был хоть один сбой по сети — `SpotifyNetworkException("GET <хост><путь>", причина)` последнего такого сбоя (ERR-01…03, загрузчик повторяет); если все сбои — статусы, `CdnStatusException(последний статус, "<хост><путь>")` и `report(Http(статус))` (ERR-04), как у ПК. На ПК решает последний сбой; на Android смешанная серия — сеть, чтобы окончательный статус не отнял у неё повтор и ожидание сети (оговорка Android в SPOT-14). Отдельный тип статуса — потому что для загрузчика он окончателен, а статус spclient — нет (`playback/README.md`, «`LoadRetries`»).
- **Отмена — не сбой ссылки.** Если поток прерван (`Thread.currentThread().isInterrupted`) или вызов отменён (`call.isCanceled()`), исключение уходит наверх как есть: без следующей ссылки и без `relink`. `SocketTimeoutException` — тоже `InterruptedIOException`, но поток не прерван и вызов не отменён, так что это обычный сбой ссылки.
- **`during` не вкладываются.** Открытие ссылки (`newCall`, `execute`, до заголовков ответа) идёт внутри `guard.during(null)`, каждое чтение тела вместе с пропуском при 200 — внутри `guard.during(call)` её вызова. Переход к следующей ссылке делается после выхода из `during` упавшего чтения, а чтение новой ссылки — в своём `during`. Почему это важно — `playback/README.md`, «`SpotifyDataSource`».
- **Тела** целиком не читаются: тело 200 или 206 — кусками не больше того, что просил плеер, и не дальше конца диапазона; тело ответа с другим статусом не читается вовсе.
- **Лог** — `GET <хост><путь>: HTTP 503` или `GET <хост><путь>: SocketTimeoutException` на каждый сбой ссылки и строка о втором `storage-resolve`. Запрос ссылки (в нём токен CDN) и ключ в лог и в сообщения исключений не попадают.

**Traps:**
- Ссылки — `HttpUrl` из `SpotifyFile.urls`: их уже проверил `SpotifyFiles`, а пустой список — `require`.
- `relink` зовут на потоке загрузчика, и он может бросить (сеть, `NoPlayableFileException`, `Content`): это уходит наверх как есть.
- Перебор идёт по кругу с последней ссылки, которая работала, а не с первой: следующая перемотка не спрашивает снова ту, что упала.
- Тесты — `SpotifyStreamTest` на `support/FakeCdn` (файл, зашифрованный тем же `AudioDecryptor`, ссылки с поведением: 206, 200, без длины, обрыв посреди тела, короткий ответ, молчание, статус, отказ соединения) и `playback/SpotifyStalledReadTest` с настоящими `ReadInterrupts` и `CallTracker`.

## `SpotifyProblem`

| Член | Откуда | Код TEL-18 (`TelemetryCodes.error`) |
|---|---|---|
| `LoginRefused(code)` | `SpotifySession`: отказ входа в AP | `login-expired` (12), `premium-required` (11) |
| `KeyRefused` | `SpotifySession`: ключ с кодом 1 | `key-refused` |
| `KeyFailed` | `SpotifySession`: две попытки без ключа | `key-failed` |
| `AccessPoint` | `SpotifySession`: протокол сдался (`ApEvent.Failure`), не ошибка сокета | `access-point` |
| `Http(status)` | `SpotifyWeb`: ответ не 2xx у токенов, spclient и Pathfinder; ошибка login5 — 401; `SpotifyStream` через `report`: все ссылки CDN упали, последняя — этим статусом | `http` + `httpStatus` |
| `Content` | `SpotifyWeb`: ответ не читается или больше предела, hashcash не решён; `unreadable` из `SpotifyLibrary` и `SpotifyFiles` | `content` |
| `QueryOutdated` | `SpotifyLibrary`: Pathfinder отверг все хеши | `query-outdated` |

У сессии и у `SpotifyWeb` свои потоки `problems` (буфер 16, `tryEmit`); телеметрия (корневой `AppTelemetry`, `telemetry/README.md`) слушает оба. Сеть не сообщается, как у ПК.

## Лог

`SpotifySession` и `SpotifyWeb` (и через `SpotifyWeb.unreadable` — `SpotifyLibrary` и `SpotifyFiles`), а также `SpotifyFiles` и `SpotifyStream` сами пишут в `log` только хост и порт AP, id трека, битрейт и число ссылок, хост и путь без запроса (`label`), статус, код ошибки и класс исключения: `access point ap-gew1.spotify.com:4070: ConnectException`, `GET spclient.wg.spotify.com/thing: HTTP 503`, `the access point refused the login: code 12`, `POST /collection/v2/paging: a collection page that does not parse`. В лог и в сообщения исключений не попадают токены (OAuth, bearer, client-token, login5), многоразовые данные, имя пользователя, device id, `user_code`, `state`, PKCE и ссылки CDN. `toString()` классов с такими строками печатает их длину (`SpotifyState.Online`, `SpotifyIdentity`, `PairLogin.Code`; классы ядра — в «`Ids`, `Bytes`» ниже). Проверка — `SpotifyLogTest`; у потока — `SpotifyStreamTest` (в логе и сообщении нет запроса ссылки CDN и ключа), у `SpotifyFile` — `SpotifyFilesTest` (`toString`). Входы (`PairLogin`, `BrowserLogin`) не пишут в лог ничего.

## Тесты клиента

`app/src/test/.../spotify/*Test.kt`, без настоящего Spotify:

- `support/FakeAccessPoint.kt` — порт `tests/support/fake_access_point` ПК: `FakeAccessPoint` — сторона AP одного соединения (DH, подпись, ключи Shannon, пакеты), `FakeAccessPointServer` — `ServerSocket` на `127.0.0.1`, поток на соединение. Ключ DH, открытый ключ и подпись сервера, тестовый ключ RSA — из `spec/spotify/handshake.json` (`server`, первый случай). Сервер отвечает на вход `APWelcome`, страной и продуктом или отказом с заданным кодом, на запрос ключа — ключом или `AesKeyError`; умеет держать вход (`loginGate`), молчать на ключи, подписать `gs` неверно (`breakSignature`), прислать пакет с чужим MAC (`sendBrokenPacket`), рвать соединения, открытые на момент вызова, — по одному, а принятые во время обрыва (переподключение сессии) остаются в очереди и не теряются (`dropConnections`), — и ждать пакетов без сна (`awaitPackets`, `awaitConnections`, `awaitClosed`).
- `support/SpotifyStack.kt` — `MockWebServer` (apresolve, client-token, login5, остальное — по маршрутам теста) и фальшивая AP, сессия и `SpotifyWeb` на них; паузы переподключения и `Retry-After` записываются и не ждутся, часы токенов подаются. `refusingAddress()` держит порт `127.0.0.1` привязанным, но не слушает: соединение с ним отказано, и никто другой этот порт не займёт до конца теста. `onLog` — крючок на строки лога (тест отмены ключа держит на нём поток AP).
- Тесты ждут событий (`state.first { … }`, `awaitPackets`) с пределом 10 с, а не спят; короткая пауза в 200 мс стоит только перед проверкой «ничего не случилось» и уронить верную реализацию не может.
- Блокирующего `takeRequest` нет (ловушка `runBlocking` + `takeRequest`): mock-сервер записывает запросы, тест читает записанное. Вход в браузере, чья неудача — ожидаемый результат, запускается в своей области с `SupervisorJob`, чтобы упавший `async` не отменял `runBlocking`.

Каталог (#113 PR 3b):
- `support/SpotifyFixtures.kt` — фикстуры `spec/fixtures/spotify`: обёртка protobuf (`base64` отдаётся как есть с её `contentType`, а `fields`, записанные `ProtoWriter`, обязаны дать те же байты), JSON — как есть; статус — из имени случая, `requestedFrom` — из `page-<n>`. `forEachCase(endpoint)` проходит каждый случай папки и сравнивает весь объект с `expected`.
- `CatalogFixturesTest` — все 11 папок фикстур, каждый случай: protobuf и контексты — разбором напрямую, apresolve — `SpotifySession.resolvedOf`, Pathfinder — через `SpotifyLibrary` на mock-сервере (устаревший ответ — каждый хеш по очереди и `QueryOutdated`, нечитаемый — `Content`). Папка без теста роняет `every endpoint … has a test here`. Сравнивается весь объект: модель ядра хранит все поля спеки, а `Track` — id, имя, длительность, доступность и обложку 400 px.
- `SpotifyLibraryTest` — выборы на `MockWebServer` через `SpotifyWeb` и фальшивую AP: SPOT-07…12, 15, 20, Pathfinder (следующий хеш, все устарели, сеть, 500 и отказ токена — без следующего), пачки по 100 и повторы, `label` без имени, нечитаемые ответы; тело трека, альбома или сохранённого альбома, которое не разбирается, выпадает без ошибки и без `Content` (фикстуры такого нет, «Two kinds of unreadable» спеки); предел страниц. Метаданные отвечает фальшивый сервер: сущности фикстур `extended-metadata` по URI, остальные треки — синтетические (имя — base62 id, Ogg 320), в обратном порядке.
- `protocol/MetadataTest` — первый из равных файлов, предел глубины замен (на 4 читается, на 5 — нет, 100 000 вложенных — без `StackOverflowError`); `protocol/CatalogMessagesTest` — граница `hasMoreAfter` и счёт без переполнения.
- `SpotifyTracksTest` — обложка SPOT-19 и `Track` трека Spotify; `SpotifyWebTest` — неудача выдачи токена не уходит другому аккаунту, а местная `NotLoggedInException` — никому; `SpotifyErrorKindsTest` (корень) — виды ошибок.

Файл трека (#113 PR 4):
- `SpotifyFilesTest` — на `support/SpotifyStack`: выбор файла SPOT-13 (стандарт, экономия, 160, любой; MP3, AAC и FLAC — никогда), ключ — для файла и gid трека, у которого он есть (и у замены), ссылки в порядке ответа, фикстуры `storage-resolve-files-audio-interactive` (`restricted`, `storage`, `cdn-without-urls` — `NoPlayableFileException` без `Content`), нечитаемые `storage-resolve` и `extended-metadata` — `SpotifyContentException` и `Content`, отказы ключа как есть и без кэша, кэш 15 минут и по битрейтам, вытеснение, `askedInOtherQuality`, второй `storage-resolve` и его ссылки в записи; после выхода и после отказа AP подготовленный трек — `NotLoggedInException` и `LoginRefusedException` без запросов, другой аккаунт не получает файл первого.
- `SpotifyStreamTest` — на `support/FakeCdn` (`MockWebServer`, файл зашифрован тем же ключом): смещения и 167 байт, 206 и 200, длина, длина из `Content-Length` без итога и её отсутствие у ограниченного открытия, открытие в конце и за концом, диапазон за концом, короткий ответ, следующая ссылка с того же байта при статусе и при обрыве посреди тела, `during` без вложений, итог (только статусы — `CdnStatusException`, хоть один сбой сети — `SpotifyNetworkException`), каждый прочитанный байт начинает счёт сбоев заново, обрыв ответа после байтов — не сбой ссылки (после долгой паузы: обрыв, затем 403 — второй `storage-resolve`, на двух ссылках и на одной), 401 и 403 как сбой ссылки, второй `storage-resolve` (и после перемотки через час — новые ссылки остаются; истекли снова — спрашивается снова), новые 403 — отказ, смесь сбоев — без него, тайм-аут чтения — сбой ссылки, отменённый вызов и прерванный поток — без следующей ссылки.
- `playback/SpotifyStalledReadTest` — зависшее чтение потока отменяется через настоящие `ReadInterrupts` и `CallTracker` за доли секунды: на первой ссылке и на следующей после обрыва первой.

Живой тест `SpotifyLiveTest` выполняется, только когда задана `QIYAA_SPOTIFY_CREDENTIALS`: JSON сохранённого входа ПК (`{"username": …, "type": 1, "data": "<base64>"}`) или путь к файлу с ним; иначе он пропускается (`Assume`). Он входит в настоящую AP с device id из 40 `d`, как живой тест ПК, проверяет, что `Online` — тот же аккаунт (имя не печатается ни в сообщениях проверок, ни в отчёте; ошибка разбора `QIYAA_SPOTIFY_CREDENTIALS` называет только класс исключения), берёт `TRACK_V4` трека (`QIYAA_SPOTIFY_TRACK`, по умолчанию `6PCoZdjSPbRMehHviTrpB3`) через spclient (а с ним client-token и login5), выбирает Ogg Vorbis и получает ключ файла. Затем готовит файл через `SpotifyFiles` (битрейты `[320, 160]`, ключ ещё раз и `storage-resolve`), открывает `SpotifyStream` с начала (CDN обязан сказать длину: без неё не было бы перемотки), читает первый КиБ и проверяет, что расшифрованное после 167 байт начинается с `OggS`. В выводе — лог сессии, `SpotifyWeb`, `SpotifyFiles` и потока по правилу «Лог» выше (у ссылок CDN — только хост и путь без запроса, где токен), страна, битрейт файла и число ссылок; ни данных входа, ни токенов, ни ключа, ни ссылок CDN целиком. В CI не выполняется; запуск с настоящим аккаунтом (сохранённым входом ПК) владелец разрешил, запускает его координатор; работает ли вход ПК при другом device id, не проверено.

## `protocol/` — ядро протокола без ввода-вывода

Протокол клиента Spotify от входа до расшифрованного Ogg Vorbis. Ядро не открывает сокетов, не ходит по HTTP, не читает часы и не держит потоков: байты носит, тела отправляет, время и случайность подаёт вызывающий (`spotify/`, #113 PR 2). Поэтому векторы `spec/spotify/` и фальшивая точка доступа проверяют на JVM то же, что работает на телефоне, и то же, что проверяет у себя ПК.

- Данные из сети ядро не роняет: битый ответ — событие `ApEvent.Failure`, `Failure` у ответов client-token и login5 или `null`. `require` и `check` — только на ошибки вызывающего: id не той длины, второй `start()`. Причина `Failure` несёт значения — длину, команду и nonce пакета, срок keep-alive, — но не байты ключей и не токены.
- Ничего не потокобезопасно, кроме `AesCtr.apply` (`@Synchronized`, а с ним и `AudioDecryptor`): один объект — на одном потоке.
- Номера полей protobuf — именованные константы рядом с сообщением (`private object ClientHelloFields` и т. п.), имена — как в `.proto` Spotify у librespot. Сами номера и порядок байтов — в `spec/spotify/README.md` («The wire», «Protobuf field numbers», таблица тел `messages.json`); здесь они не повторяются.
- Значения, которые проверяет Spotify (client ID, платформа, версия), ядро не выбирает: их подаёт `ClientIdentity` и параметры запросов. Что именно шлёт Android — решение 5 плана #113 (как ПК на Linux ARM).

### `AccessPoint`

```kotlin
class AccessPoint(
    identity: ClientIdentity,
    credentials: Credentials,
    random: (ByteArray) -> Unit,               // заполняет массив: в приложении SecureRandom::nextBytes
    serverKey: ServerKey = ServerKey.SPOTIFY,  // другой ключ — только фальшивой AP в тестах
) {
    fun start()                                // ClientHello уходит в takeOutgoing()
    fun receive(bytes: ByteArray, nowMs: Long) // что пришло из сокета, любыми кусками
    fun advance(nowMs: Long)                   // keep-alive: звать в deadline()
    fun deadline(): Long?                      // когда нужен advance(), пока вошли; иначе null
    fun takeOutgoing(): ByteArray              // что записать в сокет
    fun takeEvents(): List<ApEvent>
    val loggedIn: Boolean
    val failed: Boolean                        // соединение мертво: закрыть и начать новое
    fun requestAudioKey(track: Bytes, file: Bytes): Int?   // gid 16 байт, id файла 20; номер запроса или null до входа
    companion object { const val FIRST_PING_MS = 20_000L; PONG_DELAY_MS = 60_000L; PONG_ACK_MS = 20_000L; NEXT_PING_MS = 80_000L; val DH_PRIME: Bytes }
}
data class ClientIdentity(platform: ApPlatform, cpuFamily: ApCpuFamily, os: ApOs, systemInformation: String, versionString: String, deviceId: String, protocolVersion: Long = 124_200_290)
data class Credentials(username: String, type: AuthenticationType, data: Bytes)   // fromAccessToken(token) — вход по токену OAuth
sealed interface ApEvent {   // LoggedIn(reusable), LoginFailed(error, description), Country(code), Product(type), AudioKey(sequence, key), AudioKeyRefused(sequence, code), Failure(reason)
}
```

- Один объект — одно TCP-соединение. После каждого вызова вызывающий пишет `takeOutgoing()` в сокет, разбирает `takeEvents()` и ставит таймер на `deadline()`.
- **Вход.** `Credentials.fromAccessToken` — вход токеном OAuth (SPOT-03); `LoggedIn.reusable` — многоразовые данные (имя, тип, blob), их и хранят вместо токена. Отказ — `LoginFailed` с кодом Spotify (`BAD_CREDENTIALS` SPOT-04, `PREMIUM_ACCOUNT_REQUIRED` SPOT-05, `TRY_ANOTHER_AP`) из рукопожатия или после него; сеанс после этого `failed`, без `Failure`.
- `AuthenticationType` и `LoginError` — `value class` над кодом Spotify, а не `enum`: незнакомый код из ответа сохраняется как есть и уходит обратно при следующем входе, как у ПК (`static_cast` к enum). Известные коды — константы в их `companion object`.
- **Keep-alive** (SPOT-18), как у librespot: после `APWelcome` Ping должен прийти за `FIRST_PING_MS`; на Ping `advance` через `PONG_DELAY_MS` шлёт Pong; PongAck — за `PONG_ACK_MS`, следующий Ping — за `NEXT_PING_MS`. Пропуск — `Failure`.
- **Ключи** (SPOT-16, SPOT-17): `AudioKey` или `AudioKeyRefused` с номером запроса и кодом. Повторы и смысл кода (1 — отказ аккаунту насовсем, 2 — стоит повторить через секунду) решает вызывающий.
- `Country` и `Product` (текст первого `<type>…</type>` из ProductInfo, `premium` или `free`) приходят один раз после входа. Незнакомые команды пропускаются.

**Traps:**
- Стенограмма для ключей — весь ClientHello (с `00 04` и длиной) и весь `APResponse` (с длиной). Длины рукопожатия — u32 big-endian, и длина ClientHello считает свой префикс `00 04`.
- Материал ключей — HMAC-SHA1(общий секрет, стенограмма ‖ i) для i = 1…5, 100 байт; ответ на вызов — HMAC(материал[0..20], стенограмма); ключ Shannon на отправку — материал[20..52], на приём — [52..84].
- Открытый ключ `gc` и общий секрет теряют ведущие нулевые байты, как `to_bytes_be` у librespot, а `gs` хешируется для проверки подписи как пришёл. Иначе падает примерно одно рукопожатие из 256 — у векторов на это два случая.
- `gs` ≥ простого отвергается сам: `BigInteger.modPow` молча взял бы остаток, а BearSSL ПК (`br_i31_decode_mod`) такой ключ не принимает. Это делает `Crypto.modPow`; `p − 1` проходит, как на ПК.
- Принятый пакет расшифровывается как 3 байта заголовка, потом тело, под одним nonce; пакет — команда (1), длина u16 big-endian, тело, MAC (4).
- Pong — четыре нулевых байта, а не тело Ping, и уходит из `advance` через минуту, а не сразу.
- `RequestKey` — id файла (20) ‖ gid трека (16) ‖ номер u32 ‖ `00 00`. `AesKey` с телом не в 20 байт пропускается, `AesKeyError` короче 6 байт — тоже.
- Пустое имя пользователя в `Login` не пишется совсем (вход токеном).

### `Login`, `Hashcash`

```kotlin
object Login {
    fun clientTokenRequest(params: ClientTokenParams): ByteArray   // POST clienttoken.spotify.com/v1/clienttoken
    fun clientTokenAnswer(state: String, suffix: Bytes): ByteArray
    fun parseClientToken(body: ByteArray): ClientTokenReply        // Granted(token, refreshAfterSeconds), Challenge(state, hashcash?), Failure
    fun login5Request(clientId: String, deviceId: String, credentials: Credentials, context: Bytes, solution: HashcashSolution?): ByteArray   // POST login5.spotify.com/v3/login
    fun parseLogin5(body: ByteArray): Login5Reply                  // Ok(username, accessToken, expiresInSeconds), Challenge(context, hashcash), Error(code), Failure
}
object Hashcash {
    const val GIVE_UP_AFTER_MS = 5_000L   // предел ПК; считает вызывающий
    fun solve(context: Bytes, challenge: HashcashChallenge, giveUp: () -> Boolean): Bytes?   // суффикс 16 байт; null — бросили
}
data class HashcashSolution(val suffix: Bytes, val tookNanos: Long)   // для login5: сколько решали
sealed interface ClientPlatform { Linux(release, version, hardware); Windows(…); Mac(systemVersion, cpuType) }
```

- Ядро не читает часы: «пора бросать» — функция `giveUp`, её `solve` спрашивает перед первой попыткой и потом каждые 65 536 попыток (`CHECK_EVERY`), как ПК проверяет часы. Вызывающий отдаёт в неё и предел 5 с, и отмену корутины. Сколько решали, для ответа login5 он меряет сам и кладёт в `HashcashSolution.tookNanos`.
- Android шлёт `ClientPlatform.Linux` (решение 5 плана). `Windows` и `Mac` — формы ПК; они здесь, потому что `messages.json` проверяет все три.
- Разбор ответов — как у ПК: в client-token выигрывает первый верный `granted_token` или `challenges`; в login5 — токен, затем код ошибки, затем hashcash, иначе `Failure`. Ответы покрыты только тестами `LoginRepliesTest` по коду ПК: в векторах спеки ответов нет.

**Traps:**
- Цель hashcash — последние 8 байт SHA-1(`context`), число big-endian; суффикс — BE64(цель + n) ‖ BE64(n); решение — первый n, у которого последние 8 байт SHA-1(префикс ‖ суффикс) кончаются не меньше чем `length` нулевыми битами.
- Суффикс в ответ client-token уходит текстом в верхнем регистре hex, а префикс client-token приходит текстом hex. У login5 и префикс, и суффикс — сырые байты.
- В теле login5 сохранённые данные — поле 100; `context` пишется, только если не пуст; секунды и наносекунды длительности опускаются, когда равны 0, а сама длительность пишется и пустой.

### `Crypto`, `AesCtr`

```kotlin
object Crypto {
    fun sha1(vararg parts: ByteArray): ByteArray
    fun hmacSha1(key: ByteArray, vararg parts: ByteArray): ByteArray
    fun modPow(base: ByteArray, exponent: ByteArray, modulus: ByteArray): ByteArray?   // длина модуля; null — основание ≥ модуля, модуль чётный, пустой или > 512 байт
    fun rsaPublic(modulus: ByteArray, exponent: Int, signature: ByteArray): ByteArray? // s^e mod n длиной модуля; null — s ≥ n или подпись длиннее модуля
    fun verifyRsaSha1(modulus: ByteArray, exponent: Int, digest: ByteArray, signature: ByteArray): Boolean
}
class AesCtr(key: ByteArray, iv: ByteArray) {
    fun apply(data: ByteArray, offset: Long, from: Int = 0, length: Int = data.size - from)   // на месте, offset — байт потока
}
```

От провайдера JCA берутся только примитивы, а всё, в чём провайдеры могут разойтись, написано здесь: на JVM-тестах провайдер — SunJCE, на телефоне — Conscrypt.

| Что | Чем |
|---|---|
| SHA-1, HMAC-SHA1 | `MessageDigest`, `Mac("HmacSHA1")` |
| AES-128-CTR с любого смещения | своё: счётчик блока n — всё 128-битное число (IV + n) mod 2^128, big-endian; блоки шифруются пачкой до 4096 штук одним `Cipher("AES/ECB/NoPadding")` |
| `modPow`, Диффи — Хеллман | `BigInteger.modPow` и свои проверки ПК (нечётный модуль, основание ниже модуля), результат — ровно длины модуля |
| RSA PKCS#1 v1.5 + SHA-1 | своё на `BigInteger`: `s^e mod n` сравнивается с `00 01 FF…FF 00 ‖ DigestInfo ‖ hash`, `DigestInfo` в обеих формах (с `05 00` и без), как принимает BearSSL |

- **`@Suppress("GetInstance")`** у `Cipher.getInstance("AES/ECB/NoPadding")` в `AesCtr`: lint ругается на ECB как на режим шифрования, а здесь ECB — блочный примитив, из которого собран свой CTR. Не `AES/CTR/NoPadding`: у одних провайдеров счётчик переносится только в младшем слове (ловушка ПК «BearSSL counts in the last 32 bits»), и на JVM-тестах поведение Conscrypt не проверить. `@Suppress` котлиновский: пакет — чистая JVM, `@SuppressLint` из `android.annotation` сюда не импортируется.
- Короткая подпись читается как число (слева дополняется нулями); подпись длиннее модуля отвергается, даже если её число меньше модуля, — как у ПК.

**Traps:**
- Пустой ключ HMAC: `SecretKeySpec` его не принимает, а BearSSL считает. HMAC дополняет ключ нулями до блока, поэтому пустой ключ — то же, что один нулевой байт; так и сделано. Пустым общий секрет был бы при `gs = 0`.
- `BigInteger.toByteArray()` даёт лишний знаковый `00` и теряет ведущие нули; длину модуля возвращает `toBytes` внутри `Crypto`.

### `Shannon`

```kotlin
class Shannon(key: ByteArray) {
    fun nonce(value: Int)                                                   // каждый раз начинает шифр заново
    fun encrypt(data: ByteArray, from: Int = 0, length: Int = data.size - from)
    fun decrypt(data: ByteArray, from: Int = 0, length: Int = data.size - from)
    fun finish(length: Int = MAC_SIZE): ByteArray                           // MAC; MAC_SIZE = 4
}
```

Порт `shannon.cpp` ПК (после крейта `shannon`, MIT, и эталона Qualcomm) на `Int` с `ushr` и `Integer.rotateLeft`.

**Traps:**
- Ключ и данные читаются словами little-endian, а nonce — как четыре байта big-endian.
- MAC считается по открытому тексту и при шифровании, и при расшифровке.
- Слово, разбитое на несколько вызовов, даёт те же байты и MAC: шаг MAC — когда слово собралось.

### `ProtoWriter`, `ProtoReader`

```kotlin
class ProtoWriter { fun varint(field: Int, value: Long); fun bytes(field: Int, value: ByteArray | Bytes); fun string(field: Int, value: String); fun message(field: Int, value: ProtoWriter); fun toByteArray(): ByteArray }
class ProtoReader(input: ByteArray, start: Int = 0, end: Int = input.size) {
    fun next(): Boolean;  val failed: Boolean
    val field: Int;  val wire: ProtoWire;  val varint: Long;  val zigzag: Long   // varint — 64 бита без знака; у fixed32/fixed64 — значение little-endian
    fun bytes(): ByteArray;  fun string(): String;  fun message(): ProtoReader     // message() — читатель поля без копии
}
```

Свой поднабор, как на ПК: varint, 64 бита, длина, 32 бита; без схемы и без зависимостей. Неизвестные поля пропускает вызывающий. Читатель отвергает группы (типы 3 и 4), типы 6 и 7, поле 0, номера выше 2^29 − 1, varint длиннее 10 байт и всё оборванное; после отказа `next()` всегда `false`. Знаковые поля Spotify (длительность трека, ширина картинки, год, статус элемента списка) — `zigzag`.

Что значит упавший читатель, решает разбор, как на ПК:
- верхний уровень ответа отбрасывается целиком: `APResponse` — `Failure`, `APWelcome` — `Failure`, ответ client-token или login5 — их `Failure`;
- `APLoginFailed` отбрасывается целиком, и поле считается отсутствующим: в `APResponse` (поле 30) рукопожатие идёт дальше к ключу, как с `nullopt` из `ParseLoginFailed` ПК, а битый пакет `AuthFailure` (0xad) — отказ входа с кодом 0;
- `granted_token` и `challenges` client-token с упавшим читателем отбрасываются, разбор переходит к следующему полю ответа;
- остальные вложенные сообщения берутся до места обрыва: цепочка вызова в `APResponse` (10 → 10 → 10 → Диффи — Хеллман), `ok` и `challenges` login5, вызов hashcash в client-token и его параметры.

**Traps:** длина поля — 64 бита без знака, в `Long` она может стать отрицательной; такая длина — обрыв, а не переход назад.

### `Ids`, `Bytes`

```kotlin
object Ids { fun gidFromBase62(text: String): Bytes?; fun base62FromGid(gid: Bytes): String; fun trackUri(base62: String): String? }   // spotify:track:<base62>
class Bytes { val size: Int; fun hex(): String; fun toByteArray(): ByteArray; companion object { fun of(bytes: ByteArray); fun ofHex(hex: String): Bytes? } }
```

- base62 — 22 цифры `0-9a-zA-Z` (строчные раньше прописных, не как у многих библиотек); число 2^128 и больше, не 22 знака и чужой знак — `null`.
- `Bytes` — байты в `data class` (ключ, blob входа, gid): у массива нет `equals`. `toString()` не печатает содержимое, чтобы ключи и данные входа не попали в лог через `toString` событий.
- По той же причине свой `toString` у классов со строками-секретами: имя пользователя (`Credentials`, а с ним `ApEvent.LoggedIn`; `Login5Reply.Ok`), bearer login5 (`Login5Reply.Ok`), токен client-token (`ClientTokenReply.Granted`), `state` вызова client-token (`ClientTokenReply.Challenge`), device id (`ClientIdentity`, `ClientTokenParams`). Вместо строки — её длина, `<N chars>`; контекст login5, blob, ключ и суффикс hashcash — `Bytes`. Проверка — `SecretsTest`.

### `AudioDecryptor`, `AudioFile`

```kotlin
object AudioFile { const val HEADER_SIZE = 167; val IV: Bytes }   // IV 72e067fbddcbcf77ebe8bc643f630d93
class AudioDecryptor(key: Bytes) { fun apply(data: ByteArray, fileOffset: Long, from: Int = 0, length: Int = data.size - from) }
```

AES-128-CTR с постоянным IV Spotify с любого байта файла. Ogg Vorbis начинается после 167 байт заголовка Spotify: байт x звука — байт 167 + x файла, и для перемотки, и для диапазона HTTP. Громкость из заголовка не читается: SPOT-19 её не применяет.

### `Metadata`, `CatalogMessages`

```kotlin
object Metadata {
    const val WORLDWIDE = "ZZ"
    const val MAX_ALTERNATIVE_DEPTH = 4                                  // замены глубже не читаются
    fun request(uris: List<String>, kind: ExtensionKind): ByteArray        // TRACK_V4 10, ALBUM_V4 9, ARTIST_V4 8
    fun parseReply(body: ByteArray): List<MetadataEntity>?               // в порядке сервера; null — конверт не читается
    fun parseTrack(payload: Bytes): SpotifyTrack?
    fun parseAlbum(payload: Bytes): SpotifyAlbum?
    fun parseArtist(payload: Bytes): SpotifyArtist?
    fun topTracksIn(artist: SpotifyArtist, country: String): List<Bytes>  // gid: страна, ZZ, ""
    fun pickOggVorbis(track: SpotifyTrack, preferredKbps: Int): PlayableFile?
    fun parseStorageResolve(body: ByteArray): StorageUrls?
}
data class MetadataEntity(val uri: String, val status: Int, val payload: Bytes) { val found: Boolean }   // 200 и непустое тело
data class SpotifyTrack(gid, name, artists: List<ArtistRef>, album: AlbumRef, durationMs, files: List<AudioFileRef>, alternatives: List<SpotifyTrack>)
data class SpotifyAlbum(gid, name, artists, tracks: List<Bytes>);   data class SpotifyArtist(gid, name, topTracks: List<TopTracks>)
data class AlbumRef(gid, name, year, covers: List<Image>);   ArtistRef(gid, name);   Image(id, pixels);   TopTracks(country, tracks)
data class AudioFileRef(val id: Bytes, val format: AudioFormat);   value class AudioFormat(val code: Int) { val oggVorbisKbps: Int }   // 0 → 96, 1 → 160, 2 → 320, другое — 0
data class PlayableFile(val track: Bytes, val file: AudioFileRef)   // track — gid того, чей файл: у перепривязанного — замены
value class StorageResult(val code: Int);   data class StorageUrls(val result: StorageResult, val urls: List<String>) { val usable: Boolean }   // CDN 0, STORAGE 1, RESTRICTED 3
object CatalogMessages {
    const val COLLECTION_CONTENT_TYPE = "application/vnd.collection-v2.spotify.proto"   // и Content-Type, и Accept
    fun collectionPageRequest(username: String, set: String, pageToken: String, limit: Int): ByteArray
    fun parseCollectionPage(body: ByteArray): CollectionPage?     // items(uri, addedAt), nextPageToken; last
    fun parseListPage(body: ByteArray): ListPage?                 // name, length, from, truncated, items(uri, name); hasMoreAfter(requestedFrom, pageLength)
    fun playlistsOf(rootlist: List<ListItem>): List<ListItem>
    fun autoplayRequest(contextUri: String, recentTrackUris: List<String>): ByteArray
}
```

Порт `metadata.cpp` и `catalog.cpp` ПК. Тела запросов проверяет `spec/spotify/messages.json`, разбор — фикстуры `spec/fixtures/spotify` против `expected/spotify` (номера полей и правила — «Shapes» и «The parsing rules» в README спеки; здесь не повторяются). Что делает порт:
- `null` от `parse…` — ответ или тело, которое не читается целиком. Ошибка ли это, решает вызывающий: конверт и страница — да, тело одного трека — пропуск («Исключения» выше).
- Ядро отдаёт сущности в порядке сервера; сопоставление по URI — у `SpotifyLibrary`.
- Id не той длины остаётся нулевым (у gid — 16 нулей, base62 `0000000000000000000000`); файл и обложка без id в 20 байт выпадают. Упавший читатель альбома, исполнителя или даты внутри трека — берётся прочитанное; упавший файл, обложка или замена — выпадает; упавший трек — `null`; упавший диск или запись топа — весь альбом или исполнитель `null`.
- Обложка: `pixels` — ширина (поле 3, zigzag), если она больше 0, иначе по классу размера (поле 2): 1 — 64, 2 — 640, 3 — 1280, другое — 300.
- `pickOggVorbis`: ближайший битрейт Ogg Vorbis, первый среди равных; без своих — замены по порядку, каждая так же и в глубину. MP3, AAC, FLAC не берутся никогда (SPOT-13).
- **Глубина замен — не больше `MAX_ALTERNATIVE_DEPTH` (4), сверх ПК.** Замена глубже четвёртого уровня не разбирается, и `pickOggVorbis` глубже не смотрит: трек, чей единственный Ogg Vorbis лежит глубже, — без файла (`available = false`). У ПК разбор и выбор рекурсивны без предела, и ответ в сотни КБ с тысячами вложенных замен дал бы `StackOverflowError` — это не `IOException`, его не ловит никто, и приложение падает. В фикстурах спеки и тестах ПК замена — один уровень (на настоящем Spotify не проверено).
- Zigzag: длительность, ширина, год, статус элемента списка.
- Страница списка: если мета-элементов столько же, сколько элементов, элемент берёт имя своего мета-элемента и выпадает при статусе не 0 и не 200; иначе остаются все, без имён. Есть ли следующая — `hasMoreAfter(from, 1000)`: `from + 1000 < length` по смещению, которое спросили; `from` и `truncated` ответа читаются, но не решают. Сумма считается в `Long`, сверх ПК: в `Int` при `from` около 2^31 она стала бы отрицательной, и «ещё» не кончилось бы.
- Коллекция: удалённые элементы, элементы без URI и упавшие выпадают; `last` — пустой токен или страница без элементов.
- `storage-resolve`: `usable` — только CDN с хотя бы одной ссылкой; файл по ним готовит `SpotifyFiles` (ниже).

Номера полей — константы рядом с сообщением, имена — как в `.proto` librespot: `extended_metadata.proto`, `metadata.proto`, `collection2v2.proto`, `playlist4_external.proto`, `storage-resolve.proto`.

### Тесты

`app/src/test/.../spotify/protocol/` читают все файлы `spec/spotify/*.json` через `support/SpotifyVectors.kt`: каждый случай каждого файла, и поле случая, которое ни одна проверка не прочла, роняет тест файла (`VectorFilesTest` следит, чтобы у каждого файла был свой тест).

- `ShannonTest`, `CryptoTest`, `AudioFileTest`, `ProtobufTest`, `IdsTest`, `MessagesTest` — по файлу векторов. CTR проверяется ещё побайтно и внутри большего массива; hashcash — ещё перебором по определению из README спеки.
- `AccessPointTest` проигрывает каждый сеанс `handshake.json` клиентом: целыми кусками и по одному байту, со сверкой исходящих байтов, событий, `deadline`, `loggedIn` и `failed` после каждого шага. Сторону фальшивой AP он сверяет независимо от `AccessPoint`: ключи DH и подпись сервера, `keys` из стенограммы, каждый пакет — расшифровка ключом своего направления.
- Чего нет в векторах: байты до `start()`, длина рукопожатия меньше 4 и больше 64 КиБ, ключ до входа, чужой MAC, пропущенный PongAck (SPOT-18: `Failure`, а не второй Pong), `AesKey` не в 20 байт и короткий `AesKeyError`, подписанный `gs` = p (отказ) и p − 1 (проходит; подпись — тестовым ключом из `crypto.json` с закрытой экспонентой); у отказов сверяется и текст причины. `LoginRepliesTest` — ответы client-token и login5 в формах ПК, приоритеты разбора (токен login5 важнее ошибки в том же ответе, оборванный `granted_token` отбрасывается, берётся первый hashcash client-token) и как часто `Hashcash.solve` спрашивает `giveUp`. `SecretsTest` — секреты не печатаются через `toString`.

## Not here

- Продолжение очереди — автоплей и станция как `queue.Continuation`, поиск для очереди — корневой `SpotifyContinuations`; их правила — `queue/README.md`, «Своё продолжение». Заголовки выборов и вызовы `loadSource`, `playContinuation`, `search` — `ui/SpotifyViewModel`, статусы — очередь.
- Файл трека в Media3 (маршрут под диском, память игры, `getUri`, битрейт, политика повторов, вид ошибки) — `playback/SpotifyDataSource`, `SchemeRoute`, `LoadRetries`, `PlaybackFailures` (`playback/README.md`).
- Экран входа, открытие браузера, тексты страницы `BrowserLogin.Pages` — `ui/SpotifyViewModel` и `ui/screens/SpotifyLoginScreen` (`ui/README.md`, «Spotify»); коды TEL-18 — корневой `TelemetryCodes` (`telemetry/README.md`).
- Ключи хранения входа — `data/README.md` (`SpotifyStore`).

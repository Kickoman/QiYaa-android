# `spotify/` — Spotify

Клиент Spotify, порт `src/spotify` десктопа (Kickoman/QiYaa) файл в файл; план — [Kickoman/QiYaa-android#113](https://github.com/Kickoman/QiYaa-android/issues/113). Поведение — `spec/player/spotify.md` (SPOT-01…22), общие тест-векторы протокола — `spec/spotify/`. Пока в пакете только ядро протокола `protocol/`: сеть (точка доступа по TCP, client-token, login5, spclient), вход, каталог и файл трека появятся следующими шагами #113. Пакет ничего не показывает и не знает очереди: склейка с ней будет в корне, как `YandexLyrics.kt`.

Чистая JVM; из приложения пакет может импортировать только `track`, а `protocol/` — без ввода-вывода и не импортирует из приложения ничего:

```bash
grep -rlnE --include='*.kt' '^import (android|androidx)' app/src/main/java/io/github/kickoman/qiyaa/spotify/   # ничего не печатает
grep -rlnE --include='*.kt' 'qiyaa\.(yandex|audio|data|jam|lyrics|local|queue|playback|ui|telemetry)\.' app/src/main/java/io/github/kickoman/qiyaa/spotify/   # ничего не печатает
grep -rnE --include='*.kt' '^import (okhttp3|kotlinx\.coroutines|java\.net)\.' app/src/main/java/io/github/kickoman/qiyaa/spotify/protocol/   # ничего не печатает
grep -rn --include='*.kt' '^import io\.github\.kickoman\.qiyaa\.' app/src/main/java/io/github/kickoman/qiyaa/spotify/protocol/ | grep -v 'qiyaa\.spotify\.protocol\.'   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `protocol/AccessPoint.kt` | `AccessPoint` — сеанс с точкой доступа (AP): рукопожатие, пакеты, вход, keep-alive, ключи файлов; `ClientIdentity`, `ApPlatform`, `ApCpuFamily`, `ApOs`, `Credentials`, `AuthenticationType`, `ServerKey`, `LoginError`, `ApEvent` |
| `protocol/Login.kt` | `Login` — тела запросов client-token и login5 и разбор их ответов; `Hashcash`; `ClientPlatform`, `ClientTokenParams`, `ClientTokenReply`, `Login5Reply`, `HashcashChallenge`, `HashcashSolution` |
| `protocol/Metadata.kt` | `Metadata.request`, `ExtensionKind` — запрос `extended-metadata` |
| `protocol/CatalogMessages.kt` | `CatalogMessages` — запросы `collection/v2/paging` и автоплея |
| `protocol/AudioFile.kt` | `AudioDecryptor`, `AudioFile` — расшифровка файла трека, заголовок 167 байт |
| `protocol/Crypto.kt` | `Crypto` — SHA-1, HMAC-SHA1, `modPow`, проверка подписи RSA; `AesCtr` |
| `protocol/Shannon.kt` | `Shannon` — шифр и MAC пакетов AP |
| `protocol/Protobuf.kt` | `ProtoWriter`, `ProtoReader`, `ProtoWire` — формат protobuf без схемы |
| `protocol/Ids.kt` | `Ids` — base62 ↔ gid, URI трека |
| `protocol/Bytes.kt` | `Bytes` — неизменяемые байты; hex, big-endian |

Соответствие ПК: `access_point` → `AccessPoint.kt`, `login` → `Login.kt`, `metadata` и `catalog` → `Metadata.kt` и `CatalogMessages.kt` (пока только запросы), `audio_file` → `AudioFile.kt`, `crypto`, `modpow.c` и подмножество BearSSL → `Crypto.kt`, `shannon` → `Shannon.kt`, `protobuf` → `Protobuf.kt`, `ids` и `bytes` → `Ids.kt` и `Bytes.kt`.

## `protocol/` — ядро протокола без ввода-вывода

Протокол клиента Spotify от входа до расшифрованного Ogg Vorbis. Ядро не открывает сокетов, не ходит по HTTP, не читает часы и не держит потоков: байты носит, тела отправляет, время и случайность подаёт вызывающий (`spotify/`, #113 PR 2). Поэтому векторы `spec/spotify/` и фальшивая точка доступа проверяют на JVM то же, что работает на телефоне, и то же, что проверяет у себя ПК.

- Данные из сети ядро не роняет: битый ответ — событие `ApEvent.Failure`, `Failure` у ответов client-token и login5 или `null`. `require` и `check` — только на ошибки вызывающего: id не той длины, второй `start()`.
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

Свой поднабор, как на ПК: varint, 64 бита, длина, 32 бита; без схемы и без зависимостей. Неизвестные поля пропускает вызывающий. Читатель отвергает группы (типы 3 и 4), типы 6 и 7, поле 0, номера выше 2^29 − 1, varint длиннее 10 байт и всё оборванное; после отказа `next()` всегда `false`, а сообщение, у которого читатель упал, отбрасывается целиком. Знаковые поля Spotify (длительность трека, ширина картинки, год, статус элемента списка) — `zigzag`.

**Traps:** длина поля — 64 бита без знака, в `Long` она может стать отрицательной; такая длина — обрыв, а не переход назад.

### `Ids`, `Bytes`

```kotlin
object Ids { fun gidFromBase62(text: String): Bytes?; fun base62FromGid(gid: Bytes): String; fun trackUri(base62: String): String? }   // spotify:track:<base62>
class Bytes { val size: Int; fun hex(): String; fun toByteArray(): ByteArray; companion object { fun of(bytes: ByteArray); fun ofHex(hex: String): Bytes? } }
```

- base62 — 22 цифры `0-9a-zA-Z` (строчные раньше прописных, не как у многих библиотек); число 2^128 и больше, не 22 знака и чужой знак — `null`.
- `Bytes` — байты в `data class` (ключ, blob входа, gid): у массива нет `equals`. `toString()` не печатает содержимое, чтобы ключи и данные входа не попали в лог через `toString` событий.

### `AudioDecryptor`, `AudioFile`

```kotlin
object AudioFile { const val HEADER_SIZE = 167; val IV: Bytes }   // IV 72e067fbddcbcf77ebe8bc643f630d93
class AudioDecryptor(key: Bytes) { fun apply(data: ByteArray, fileOffset: Long, from: Int = 0, length: Int = data.size - from) }
```

AES-128-CTR с постоянным IV Spotify с любого байта файла. Ogg Vorbis начинается после 167 байт заголовка Spotify: байт x звука — байт 167 + x файла, и для перемотки, и для диапазона HTTP. Громкость из заголовка не читается: SPOT-19 её не применяет.

### `Metadata`, `CatalogMessages`

```kotlin
object Metadata { fun request(uris: List<String>, kind: ExtensionKind): ByteArray }   // TRACK_V4 10, ALBUM_V4 9, ARTIST_V4 8
object CatalogMessages {
    const val COLLECTION_CONTENT_TYPE = "application/vnd.collection-v2.spotify.proto"   // и Content-Type, и Accept
    fun collectionPageRequest(username: String, set: String, pageToken: String, limit: Int): ByteArray
    fun autoplayRequest(contextUri: String, recentTrackUris: List<String>): ByteArray
}
```

Только тела запросов, которые проверяет `spec/spotify/messages.json`. Разбор ответов каталога (`TRACK_V4`, альбомы, исполнители, коллекция, плейлисты, `storage-resolve`) придёт вместе с фикстурами спеки (#113, S3 и PR 3b).

### Тесты

`app/src/test/.../spotify/protocol/` читают все файлы `spec/spotify/*.json` через `support/SpotifyVectors.kt`: каждый случай каждого файла, и поле случая, которое ни одна проверка не прочла, роняет тест файла (`VectorFilesTest` следит, чтобы у каждого файла был свой тест).

- `ShannonTest`, `CryptoTest`, `AudioFileTest`, `ProtobufTest`, `IdsTest`, `MessagesTest` — по файлу векторов. CTR проверяется ещё побайтно и внутри большего массива; hashcash — ещё перебором по определению из README спеки.
- `AccessPointTest` проигрывает каждый сеанс `handshake.json` клиентом: целыми кусками и по одному байту, со сверкой исходящих байтов, событий, `deadline`, `loggedIn` и `failed` после каждого шага. Сторону фальшивой AP он сверяет независимо от `AccessPoint`: ключи DH и подпись сервера, `keys` из стенограммы, каждый пакет — расшифровка ключом своего направления.
- Чего нет в векторах: байты до `start()`, длина рукопожатия меньше 4 и больше 64 КиБ, ключ до входа, чужой MAC, `AesKey` не в 20 байт и короткий `AesKeyError`, подписанный `gs` = p (отказ) и p − 1 (проходит; подпись — тестовым ключом из `crypto.json` с закрытой экспонентой). `LoginRepliesTest` — ответы client-token и login5 в формах ПК и как часто `Hashcash.solve` спрашивает `giveUp`.

### Not here

- Сокет AP, apresolve, повторы ключей, переподключение, client-token и login5 по HTTP, spclient — #113 PR 2 (`SpotifySession`, `SpotifyWeb`).
- Разбор метаданных и каталога — #113 PR 3b; файл трека с CDN и его расшифровка в Media3 — PR 4.
- Хранение входа — `data/SpotifyStore` (PR 2).

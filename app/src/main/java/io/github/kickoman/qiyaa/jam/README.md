# `jam/` — клиент джема

Связь с сервером джема (Kickoman/QiYaa-jam) по протоколу `spec/jam/protocol`: модель сообщений, их
разбор с проверкой по схеме, соединение с рукопожатием и переподключением, `outbox` и хранение сессии
хозяина. Пакет **не** знает, что играет плеер и как выглядит экран: хозяин джема в очереди — задача
`queue/` (QiYaa-android#60), связь очереди, библиотеки и сервера — `playback/JamHost` (#61), экран — `ui/` (#62). Сокетов
здесь тоже нет: транспорт — интерфейс `JamTransport`, в приложении — `playback/OkHttpJamTransport`.

Чистая JVM, ничего не импортирует из других пакетов приложения:

```bash
grep -rlnE '^import (android|androidx|io\.github\.kickoman)' app/src/main/java/io/github/kickoman/qiyaa/jam/   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `Protocol.kt` | `ClientMessage`, `ServerMessage` и все их типы; `JamTrack`, `JamRoom`, `JamSettings`… |
| `JamCodec.kt` | `JamCodec` — JSON сообщений, проверки схемы, `Decoded` |
| `JamClient.kt` | `JamClient`, `JamTransport`, `JamSocket`, `JamHandler`, `JamStatus` |
| `JamSessionStore.kt` | `JamSession`, `JamSessionStore`, `JamSessionCodec`, `JamStore` |
| `JamLink.kt` | `JamLink` — ссылка для входа гостя: сервер, комната, секрет |
| `JamGuestStore.kt` | `GuestRoom`, `GuestMembership`, `GuestMemory`, `JamGuestStore`, `JamGuestCodec` — членства гостя на диске |

## `Protocol.kt`

Классы повторяют `spec/jam/protocol/schemas` поле в поле. Сообщения — `@Serializable` запечатанные
интерфейсы с дискриминатором `type` (`@SerialName("hello")`…), необязательные поля — `null` по
умолчанию. Имена Kotlin там, где слово занято: `settings` → `ChangeSettings`, `searchResult` →
`SearchResult`, `snapshot` → `Snapshot`, `state` → `State`.

- `JamTrack.coverUri` — шаблон Яндекса с `%%`, как в протоколе; это не `yandex/Track.coverUrl`.
- `JamTrack.source` — `null` (по умолчанию схемы, `yandex`), `YANDEX` или `HOST`: трек, который может
  сыграть только хозяин, например локальный файл десктопа (протокол, «Tracks»; HOST-37, LISTEN-14).
  `null` при кодировании пропадает (`explicitNulls = false`), так что трек каталога уходит без ключа
  `source`, как и раньше, и старый сервер его не видит. `JamTrack.isCatalog` — не `HOST`. Хозяин на
  Android сам `host`-треков не шлёт: он играет только каталог Яндекса (`playback/JamTracks.toJam`
  оставляет `source` пустым). `host`-треки приходят только от хозяина на десктопе; гостя в
  приложении пока нет (#63), ему придётся их показывать.
- `Rejected.reason` — строка, а не enum: причину, которой приложение не знает, показываем как общую
  ошибку (протокол, «Reasons»). Известные — `JamCodec.REASONS`.
- `Resume.snapshot` и `Snapshot.data` — `JsonElement`: снимок хозяин не читает, только хранит и
  возвращает. В `Resume` без снимка уходит явный `"snapshot": null` — поле обязательное.

## `JamCodec`

```kotlin
object JamCodec {
    val json: Json                                   // ignoreUnknownKeys, classDiscriminator = "type", explicitNulls = false, encodeDefaults
    val REASONS: Set<String>;  val CLIENT_TYPES: Set<String>;  val SERVER_TYPES: Set<String>
    fun encode(message: ClientMessage): String
    fun decodeServer(text: String): Decoded<ServerMessage>
    fun decodeClient(text: String): Decoded<ClientMessage>
    fun isUnknownReason(decoded: Decoded<ServerMessage>): Boolean
}
sealed interface Decoded<out T> { Message(message); UnknownType; Invalid(problem) }
```

Разбор: JSON-объект → известный `type` (иначе `UnknownType`) → kotlinx.serialization (обязательные
поля, типы, значения enum) → проверки, которых kotlinx не делает, — шаблоны id и секретов,
длины имён и текстов, пределы чисел и списков, «ровно одно из» (`add`, `searchResult`,
`validateResult`), условные поля `playing` и `nowPlaying`, `outbox` только из `started`, формат
снимка и **ни одного поля с именем секрета** на любой глубине `state` и снимка. Неизвестные поля
пропускаются. Тест прогоняет все `spec/jam/protocol/examples`: правильные разбираются, `invalid-*` —
нет.

Треки (`track.schema.json`): у `host`-трека нет `albumId` и `coverUri`. Только треки каталога
(`#/$defs/catalogTrack` в схемах) допускаются в `searchResults.tracks`, `searchResult.tracks`,
`validateResult.results[].track` и в `queue[]` и `recent[]` комнаты в `state`. Рядом с `host`-треком
в `playing` и `nowPlaying` нет `listenUrl` и `listenNextUrl` (LISTEN-14). `add.track` проверяется как
любой трек: схема `add` допускает `host`, а отказывает в нём сервер (ROOM-66).

**Ловушки:**
- Числа и шаблоны — копия `defs.schema.json` и `spec/jam/limits.md`. Меняется спецификация — меняется
  `JamCodec`, и тест на примерах это ловит.
- У `source` три значения в Kotlin (`null`, `YANDEX`, `HOST`) на два смысла. Сравнивать через
  `isCatalog`, а не `== null` или `== YANDEX`. Принятый `"source": "yandex"` уйдёт обратно как
  `"source":"yandex"`; треки, которые строит само приложение, — без ключа.
- `\s` в регулярках Java — только ASCII-пробелы, у Ajv на сервере — все пробелы Юникода. Имя с
  неразрывным пробелом на краю сервер отвергнет, а `JamCodec` пропустит.

## `JamClient`

```kotlin
class JamClient(transport: JamTransport, connectivity: Flow<Boolean>, scope: CoroutineScope, appVersion: String,
                handler: JamHandler, clock: () -> Long = System::currentTimeMillis, log: (String) -> Unit = {}) {
    val status: StateFlow<JamStatus>        // IDLE, CONNECTING, ONLINE, OFFLINE, STOPPED
    val outbox: StateFlow<List<String>>     // itemId, ещё не отправленные started
    val clockOffsetMs: Long;  fun serverNow(): Long
    fun start(url: String);  fun stop()
    fun send(message: ClientMessage): Boolean   // false — нет соединения после welcome
    fun started(itemId: String, sendNow: Boolean = true)   // отправить или отложить в outbox; false — роль ещё не принята
    fun restoreOutbox(itemIds: List<String>);  fun clearOutbox()
    companion object { val RECONNECT_DELAYS_MS; fun socketUrl(serverUrl: String): String }
}
interface JamHandler { fun onWelcome(); fun onMessage(message: ServerMessage) }
```

- Открылся сокет — `hello{protocol: 1, app: android}`. `welcome` — `ONLINE`, смещение часов,
  `onWelcome()`: владелец шлёт `create`, `resume` или `join`. Смещение обновляется и по каждому `state`.
- Обрыв — `OFFLINE` и новое соединение через 1, 2, 4, 8, 16, 30, 30 … с; `welcome` сбрасывает счёт.
  Сеть появилась (`connectivity` стал `true`, в приложении — `NetworkMonitor.available`) — сразу.
- После `ended`, `kicked` и `rejected{update-required}` сообщение доходит до `onMessage`, потом
  `STOPPED`: больше никаких переподключений.
- Все события сокета идут через один `Channel` и обрабатываются в `scope` по порядку; событие старого
  соединения после нового игнорируется.
- Сообщение, не прошедшее `JamCodec`, пропускается (в `log`); `rejected` с неизвестной причиной
  доходит как `Rejected` с этой строкой.
- `outbox` клиент только копит. Уходит он в `resume`, который собирает владелец, и тот же владелец
  вызывает `clearOutbox()` после `resumed`.

`socketUrl`: `https://jam.example.org` → `wss://jam.example.org/ws`, `http://` → `ws://`, без схемы —
`wss://`.

## `JamSessionStore`

Что хозяин держит на диске, пока джем идёт (HOST-22): `roomId`, `hostSecret`, `joinUrl`, последний
снимок и `outbox`. Формат — компактный JSON, `version` = 1:

```json
{"version":1,"roomId":"7k3m9q2x","hostSecret":"…","joinUrl":"https://…/j/7k3m9q2x#…","snapshot":{"format":1,"room":{…}},"outbox":["i4"]}
```

`decode` возвращает `null` для пустого, битого файла и другой версии. `JamStore.write(null)` удаляет
сессию. В приложении хранилище — `data/JamFile` (`filesDir/jam.json`, `AtomicFile`).

## `JamLink`

```kotlin
data class JamLink(val serverUrl: String, val roomId: String, val joinSecret: String?) {
    companion object { fun find(text: String): JamLink? }   // null — в тексте нет ссылки на джем
}
```

Ссылка для входа гостя (GUEST-01; протокол, «Identifiers and secrets»): `https://<сервер>/j/<roomId>#<joinSecret>`.
`find` берёт **первую** такую ссылку где угодно в тексте, поэтому подходит и текст «Join my jam: https://…», которым
делится хозяин, и вставка с переводами строк вокруг. Разбор:

| Часть | Правило |
|---|---|
| схема | только `https://` (в любом регистре); `http://`, `wss://`, ссылка без схемы — не ссылка: в приложении `usesCleartextTraffic="false"`, `ws://` не соединится никогда |
| хост | `[A-Za-z0-9.-]+` или IPv6 в `[…]`; `user@` не допускается |
| порт | необязателен; `:443` отбрасывается |
| префикс пути | всё перед последним `/j/`, как в `JamCodec.joinUrl`: `https://example.org/jam/j/…` → сервер `https://example.org/jam` |
| `roomId` | ровно 8 знаков Crockford в нижнем регистре `[0-9a-hjkmnp-tv-z]`; `7K3M9Q2X`, 7 или 9 знаков — не ссылка |
| после `roomId` | необязательные `/`, `?…` и `#…`; любой другой путь (`/j/<roomId>/more`) — не ссылка |
| секрет | всё после `#`; если это не ровно 22 знака base64url `[A-Za-z0-9_-]`, `joinSecret = null` (GUEST-05) |
| конец ссылки | пробел, любой пробел Юникода (`\p{Z}`, в том числе U+00A0) или конец текста; знаки `.,;:!?)]}>'"»` в самом конце отрезаются |

`serverUrl` нормализован, чтобы у одной комнаты было одно членство (GUEST-01, GUEST-03): `https://`, хост и порт в
нижнем регистре, без `:443`, префикс пути без `/` на конце (регистр префикса сохраняется). Сокет — `JamClient.socketUrl(serverUrl)`,
то есть `wss://<сервер>/ws`.

**Ловушки:**
- Сервер гостя — источник ссылки, а не `Settings.jamServer`: вставленная ссылка на чужой сервер тоже работает.
- Секрет живёт в `JamLink.toString()` (это `data class`): не писать ссылку в лог целиком.
- Мессенджеры иногда теряют `#…`. Тогда `joinSecret = null`, и войти можно только в комнату, членство в которой уже есть.

## `JamGuestStore`

```kotlin
data class GuestRoom(val serverUrl: String, val roomId: String)          // serverUrl — нормализованный, как в JamLink
data class GuestMembership(val room: GuestRoom, val participantId: String, val name: String, val joinSecret: String,
                           val firstJoinedAt: Long, val joined: Boolean = false, val ended: Boolean = false)
data class GuestMemory(val current: GuestRoom? = null, val memberships: List<GuestMembership> = emptyList(),
                       val lastName: String? = null) {
    fun membership(room: GuestRoom): GuestMembership?
    fun with(membership: GuestMembership): GuestMemory   // новое — первым, известное — на своём месте
    fun without(room: GuestRoom): GuestMemory            // и current, если это он
    fun alive(now: Long): GuestMemory                    // срок жизни и 20 новейших
}
class JamGuestStore(store: JamStore) {
    fun load(now: Long): GuestMemory;  fun save(memory: GuestMemory);  fun clear()
    companion object { const val MEMBERSHIP_LIFE_MS = 43_200_000L; const val MAX_MEMBERSHIPS = 20 }
}
object JamGuestCodec { const val VERSION = 1; fun encode(memory: GuestMemory): String; fun decode(text: String?): GuestMemory? }
```

Что гость в приложении помнит о комнатах (GUEST-03, GUEST-19). **Членство** — одна комната одного сервера:
`participantId` (UUID v4 в нижнем регистре, по нему сервер узнаёт того же участника, ROOM-05), имя, секрет входа,
время первого входа, `joined` (сервер хоть раз ответил `joined`) и `ended` (хозяин закончил джем, `ended{host-ended}`).
`current` — комната открытой сессии гостя: по ней приложение после перезапуска входит снова (GUEST-19). `lastName` —
последнее имя гостя, им заполняется поле имени в новой комнате (GUEST-02).

Членство живёт `MEMBERSHIP_LIFE_MS` = 12 ч от первого входа (`limits.md`, «Connections», «Membership life»): комната
не живёт дольше (максимальный возраст комнаты — те же 12 ч, `limits.md`, «Rooms»), а сама она существовала до входа.
`alive(now)` (его зовёт `load`) отбрасывает членства с возрастом от 12 ч, оставляет 20 новейших по `firstJoinedAt` и
сбрасывает `current`, если его членство ушло. Забыть всё (выход из аккаунта) — `clear()`.

Формат файла — компактный JSON, `version` = 1; поля со значением по умолчанию (`null`, `false`) не пишутся:

```json
{"version":1,"current":{"serverUrl":"https://qiyaa.kanstancin.net","roomId":"7k3m9q2x"},"lastName":"Аня",
 "rooms":[{"serverUrl":"https://qiyaa.kanstancin.net","roomId":"7k3m9q2x","participantId":"3f6c1a2e-8b4d-4c7a-9e21-5d0b7f3a1c64",
           "name":"Аня","joinSecret":"WDkyFgMr5iV3hKwManPvsg","firstJoinedAt":1790730000000,"joined":true}]}
```

`decode` возвращает `null` для пустого, битого файла и другой версии (`load` — пустую память). Членство, с которым
сервер не принял бы `join`, отбрасывается: `serverUrl` не в нормальной форме `JamLink`, `roomId`, секрет,
`participantId` или имя не проходят `JamCodec` (кадр, нарушающий схему, закрывается с 1008 и идёт в счёт бана IP,
ROOM-56). `current` без своего членства — `null`. В приложении хранилище — `data/JamFile(context, JamFile.GUEST)`
(`filesDir/jam-guest.json`, `AtomicFile`).

**Ловушки:**
- В файле секреты: `joinSecret` и `participantId`. Он приватный, резервное копирование отключено (`allowBackup=false`,
  `data_extraction_rules`), но в лог содержимое не писать.
- Членство не забывается ни по `kicked`, ни по `ended`, ни по истёкшему ожиданию комнаты (GUEST-03, GUEST-17): только
  `clear()` и 12 ч. По тому же `participantId` сервер после кика снова ответит `rejected{kicked}` (ROOM-08).

## Тесты

`app/src/test/.../jam/`: `JamCodecTest` (все примеры спецификации, кодирование туда и обратно,
правила `source`: каждое — правильный пример с одной правкой),
`JamClientTest` (поддельный транспорт, виртуальное время `runTest`), `JamSessionCodecTest`, `JamLinkTest`
(GUEST-01, GUEST-05: текст вокруг ссылки, нормализация сервера, отказы; все `joinUrl` примеров `created` и `linkRotated`),
`JamGuestStoreTest` (GUEST-03, GUEST-19: формат, битые файлы и записи, срок жизни, 20 новейших).

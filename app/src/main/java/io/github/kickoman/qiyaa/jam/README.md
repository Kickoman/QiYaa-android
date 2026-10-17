# `jam/` — клиент джема

Связь с сервером джема (Kickoman/QiYaa-jam) по протоколу `spec/jam/protocol`: модель сообщений, их
разбор с проверкой по схеме, соединение с рукопожатием и переподключением, `outbox` и хранение сессии
хозяина. Здесь же весь гость в чужом джеме (`spec/jam/guest.md`, #63): ссылка, членства на диске, вход,
ожидание комнаты, `add{track}` с проверкой кадра. Пакет **не** знает, что играет плеер и как выглядит экран: хозяин джема в очереди — задача
`queue/` (QiYaa-android#60), связь очереди, библиотеки и сервера — `playback/JamHost` (#61), экран — `ui/` (#62). Гость
не может тронуть очередь и плеер даже случайно: пакет их не видит (GUEST-13). Поиск, лайки и плейлисты гостя — его
собственный аккаунт через `yandex/Library` в `ui/`; трек для `add` строит `playback/JamTracks.toJam`. Сокетов
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
| `JamGuest.kt` | `JamGuest`, `JamGuestState`, `GuestPhase`, `GuestAdd`, `GuestAddResult`, `AddAction`, `JamGuestEvent`, `GuestNames`, `positionMsAt` — сессия гостя |

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
  оставляет `source` пустым). `host`-треки приходят только от хозяина на десктопе; гость в приложении
  показывает их без обложки и ссылки (GUEST-14) и сам не добавляет (`JamGuest.add`, ROOM-66).
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
  неразрывным пробелом на краю сервер отвергнет, а `JamCodec` пропустит. Поэтому имя гостя сначала чистит
  `GuestNames.clean`.

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
- В файле секреты: `joinSecret` и `participantId`. Он приватный и не уходит ни в облачную копию, ни на новое
  устройство (`data/README.md`, «Резервное копирование»), но в лог содержимое не писать.
- Членство не забывается ни по `kicked`, ни по `ended`, ни по истёкшему ожиданию комнаты (GUEST-03, GUEST-17): только
  `clear()` и 12 ч. По тому же `participantId` сервер после кика снова ответит `rejected{kicked}` (ROOM-08).

## `JamGuest`

```kotlin
class JamGuest(transport: JamTransport, connectivity: Flow<Boolean>, store: JamGuestStore, scope: CoroutineScope,
               io: CoroutineContext, appVersion: String,
               newParticipantId: () -> String = { UUID.randomUUID().toString() },   // UUID v4 в нижнем регистре
               newId: () -> String = { UUID.randomUUID().toString() },              // id запросов, 36 знаков
               random: () -> Double = Math::random, clock: () -> Long = System::currentTimeMillis,
               log: (String, Throwable?) -> Unit = { _, _ -> }) : JamHandler {
    val state: StateFlow<JamGuestState>;  val events: SharedFlow<JamGuestEvent>
    fun open(link: JamLink)                   // GUEST-01, -02, -04, -05
    fun join(name: String): Boolean           // false — не NeedName, или имя не проходит GuestNames.clean и JamCodec
    fun restore(): Boolean                    // GUEST-19; true — сессия уже есть или восстановлена
    fun add(track: JamTrack): GuestAddResult  // SENT, NOT_CONNECTED, HOST_OFFLINE, INVALID
    fun remove(itemId: String): Boolean       // только свой ждущий элемент (GUEST-10)
    fun skip(itemId: String): Boolean         // только при canSkip и для текущего элемента (GUEST-11)
    fun leave()                               // GUEST-18; им же «Отмена» при входе и «Закрыть» после конца
    fun forgetAll()                           // выход из аккаунта: leave и пустой jam-guest.json
    fun serverNow(): Long
}
data class JamGuestState(val phase: GuestPhase, val serverUrl: String?, val roomId: String?, val lastName: String?,
                         val connection: JamStatus, val connected: Boolean, val room: JamRoom?,
                         val adds: Map<String, GuestAdd>) {   // trackId → Sending, Added, Failed(reason)
    val waitingOfYours: Int;  val canSkip: Boolean
    fun canRemove(item: JamQueueItem): Boolean;  fun addAction(trackId: String): AddAction?   // null — комнаты нет
}
sealed interface JamGuestEvent { data class Refused(val reason: String) }   // отказ в remove, skip, без id
fun JamNowPlaying.positionMsAt(serverNow: Long): Long
object GuestNames { const val MAX_NAME = 24; fun clean(name: String): String?; fun prefill(name: String): String }
```

Гость в чужой комнате (`spec/jam/guest.md`). Один на процесс: `AppGraph.jamGuest`, свой `OkHttpJamTransport`,
`JamGuestStore(JamFile(context, JamFile.GUEST))`, `scope` на `Dispatchers.Main.immediate`, записи на `Dispatchers.IO`.
Сервиса нет: гость ничего не играет. Когда Android замораживает приложение в фоне, гость для комнаты «не в сети», а
его элементы остаются (ROOM-11). Состояние без секретов: в `JamGuestState` нет ни `joinSecret`, ни `participantId`.

**Фазы** (`GuestPhase`; `isActive` — `Joining`, `InRoom`, `WaitingForRoom`):

| Фаза | Когда | Соединение |
|---|---|---|
| `None` | сессии нет; после `leave` и `forgetAll` | нет |
| `NoSecret` | у ссылки нет секрета, членства нет (GUEST-05) | нет |
| `NeedName` | новая комната, ссылка с секретом (GUEST-02); `lastName` — для поля имени | нет |
| `Joining` | ждём `welcome` и ответ на первый `join` | `JamClient` с его переподключением |
| `InRoom` | был `joined`; обрыв фазу не меняет, только `connected = false` (GUEST-15) | то же |
| `WaitingForRoom` | ожидание комнаты (GUEST-16); `room` остаётся на экране | открыто, `join` по таймеру |
| `Refused(reason)` | `join` отклонён окончательно | остановлено |
| `Ended(reason)` | `ended`; или `room-not-found` для завершённого членства | остановлено |
| `Kicked` | `kicked` или `rejected{join, kicked}` (ROOM-08) | остановлено |
| `UpdateRequired` | `rejected{update-required}` с любым `id` | остановлено |

`connected` — комната и соединение, чей `join` получил `joined`: только тогда уходят `add`, `remove`, `skip` и
принимаются `state`.

**`open(link)`.** Ссылка на комнату текущей сессии (`Joining`, `InRoom`, `WaitingForRoom`, `NeedName`) ничего не
меняет. Иначе текущая сессия уходит, как `leave` (закрытие 1000, членство остаётся), и: есть членство — `Joining` и
`join` сразу, с секретом ссылки, а без него — с сохранённым (GUEST-04; секрет ссылки сохраняется); нет членства и
секрета — `NoSecret`; иначе `NeedName`. **`join(name)`** делает `participantId`, сохраняет членство и `current`
**до** первого `join` (GUEST-03) и соединяется с `JamClient.socketUrl(serverUrl)`. **`restore()`** открывает
`current` из файла, если его членство моложе 12 ч (GUEST-19).

**Ответ на `join`** (на каждое соединение — свой `join` после `welcome`, с тем же `participantId`):

| Ответ | Членство не `joined` | `joined` | `joined` и `ended` |
|---|---|---|---|
| `joined` | `joined = true`, `InRoom` | `InRoom`, ожидание сброшено | то же |
| `rejected{not-allowed}` | соединение уже вошло: как `joined`, запись в лог | то же | то же |
| `rejected{room-not-found}` | удаляется, `Refused` | ожидание комнаты | `Ended(host-ended)` сразу |
| `rejected{rate-limited}` | удаляется, `Refused` | ожидание комнаты | `Refused` |
| `rejected{kicked}` | удаляется, `Kicked` | `Kicked` | `Kicked` |
| `rejected{update-required}` | удаляется, `UpdateRequired` | `UpdateRequired` | то же |
| любой другой отказ | удаляется, `Refused(reason)` | `Refused(reason)` | то же |

Каждое окончательное состояние останавливает `JamClient` и сбрасывает `current`; членство, если оно `joined`, остаётся
(GUEST-03, GUEST-17). `ended{host-ended}` делает членство `ended`: та комната не вернётся (ROOM-52).

**Ожидание комнаты** (REC-14, GUEST-16). `join` повторяется на открытом соединении через `ROOM_RETRY_MS` = 5, 10, 20,
30, 30 … с × (0.8 + 0.4·`random()`) — «Guest backoff» в `spec/jam/limits.md`, «Connections». Через `ROOM_WAIT_MS` =
10 мин от первого отказа («Room wait» там же) — `Refused(reason)` и стоп; членство остаётся, так что следующее открытие
ссылки ждёт снова: хозяин может поднять комнату из снимка (REC-06). Таймер — `Job` гостя: его отменяют `welcome`
(новое соединение шлёт свой `join`), `joined`, `leave`, `forgetAll` и любое окончание. По таймеру `join` уходит только
в `WaitingForRoom` и только когда другой `join` не ждёт ответа. Счётчик и начало ожидания сбрасывает `joined` и новая
сессия.

**Остальные сообщения.** `state` — только при `connected`; на одном соединении `version` не больше прошлой
игнорируется, первый `state` после переподключения берётся каким есть (REC-13). `ack` на `add` — `Added`; отказ на
`add` — `Failed(reason)`, неизвестные причины — строкой. Отказ на `remove`, `skip` или без `id` — событие
`JamGuestEvent.Refused`. `ended` — `Ended`, `kicked` — `Kicked`. `searchRequest`, `validateRequest`, `command`,
`snapshot` гостю не приходят и игнорируются. `welcome` забывает ждущие ответа `add` (`Sending`): их ответы не придут,
а следующий `state` покажет, дошли ли они (GUEST-15).

**`add(track)`** (GUEST-07), по порядку: нет `connected` или комнаты — `NOT_CONNECTED`; `hostOnline: false` —
`HOST_OFFLINE` (GUEST-09); `host`-трек (ROOM-66), сообщение, которое не проходит `JamCodec`, или кадр больше
`GUEST_FRAME_BYTES` = 4 096 байт UTF-8 («Frame size» в `limits.md`) — `INVALID`; иначе `add{id, track}` и `Sending`.
Кадр, нарушивший схему, сервер закрывает с 1008, слишком большой — с 1009, три таких — бан IP на 10 мин, а гости на
вечеринке делят один IP (ROOM-56, ROOM-57).

**`addAction(trackId)`** (GUEST-08) — первое, что подходит: трек ждёт в очереди — `Yours` или `QueuedBy(name)` (имя
из `participants`, `null` — неизвестный); `Sending`; хозяин не в сети — `HostOffline`; своих ждущих
`>= maxPendingPerGuest` — `Limit(max)`; последний `add` отклонён — `Failed(reason)`, кроме `duplicate` и
`queue-limit` (их показывает очередь); иначе `Add`.

`positionMsAt(serverNow)` — `positionMs`, а при игре плюс `serverNow − reportedAt`, в пределах `0…durationMs`;
без трека — 0 (GUEST-14). `GuestNames.clean` обрезает края и сворачивает каждую серию пробелов в один; пробел —
любой знак `\s` JavaScript (`\t \n \v \f \r`, пробел, U+00A0, U+1680, U+2000–U+200A, U+2028, U+2029, U+202F,
U+205F, U+3000, U+FEFF); результат — 1…24 кодовые точки или `null`. `GuestNames.prefill` — то же для поля имени
(GUEST-02): чистит так же, но длинное имя обрезает до 24 кодовых точек, а не отвергает; пустое — `""`.

**Ловушки:**
- Гость **никогда** не шлёт `search` (сервер ответит `not-allowed`, ROOM-27) и `add{trackId}` (ROOM-65): он ищет своим
  аккаунтом в `ui/`, а в `add` идёт готовый трек.
- `\s` Java — только ASCII, а сервер проверяет имя набором JavaScript. Без `GuestNames.clean` имя с U+00A0 или
  U+FEFF на краю проходит `JamCodec`, а сервер закрывает соединение с 1008.
- Трек из `playback/JamTracks.toJam` в 4 КиБ влезает всегда: худший случай — трёхбайтовые знаки (CJK) во всех полях,
  около 3,6 КБ (`JamTracksTest`). Проверка срабатывает только на треке с четырёхбайтовыми знаками, собранном мимо
  `toJam`: `JamCodec` считает кодовые точки, а не байты.
- `restore()` не знает про вход в аккаунт: зовёт его `ui/`, когда токен есть. Ссылку до входа держит `ui/` (GUEST-01),
  исключение хозяина и гостя (GUEST-20) — тоже `ui/`. «Лайк себе» (GUEST-12) — `ui/` через `Library`, не здесь.
- Отклонённый токен — не повод для `forgetAll` (GUEST-03): только явный выход из аккаунта.
- Правила `remove` и `skip` повторены локально, чтобы не тратить запросы; судья — сервер, его отказ приходит событием.

## Тесты

`app/src/test/.../jam/`: `JamCodecTest` (все примеры спецификации, кодирование туда и обратно,
правила `source`: каждое — правильный пример с одной правкой),
`JamClientTest` (поддельный транспорт, виртуальное время `runTest`), `JamSessionCodecTest`, `JamLinkTest`
(GUEST-01, GUEST-05: текст вокруг ссылки, нормализация сервера, отказы; все `joinUrl` примеров `created` и `linkRotated`),
`JamGuestStoreTest` (GUEST-03, GUEST-19: формат, битые файлы и записи, срок жизни, 20 новейших),
`JamGuestTest` (GUEST-01…19 и REC-13…15, ROOM-05, -08, -27, -28, -43…46, -65, -66: `FakeJamTransport`, виртуальное время,
`random = 0.5`; пример `state/guest.json` спецификации — комната, где приложение не хозяин).

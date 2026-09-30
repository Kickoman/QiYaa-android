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

## `Protocol.kt`

Классы повторяют `spec/jam/protocol/schemas` поле в поле. Сообщения — `@Serializable` запечатанные
интерфейсы с дискриминатором `type` (`@SerialName("hello")`…), необязательные поля — `null` по
умолчанию. Имена Kotlin там, где слово занято: `settings` → `ChangeSettings`, `searchResult` →
`SearchResult`, `snapshot` → `Snapshot`, `state` → `State`.

- `JamTrack.coverUri` — шаблон Яндекса с `%%`, как в протоколе; это не `yandex/Track.coverUrl`.
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

**Ловушки:**
- Числа и шаблоны — копия `defs.schema.json` и `spec/jam/limits.md`. Меняется спецификация — меняется
  `JamCodec`, и тест на примерах это ловит.
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

## Тесты

`app/src/test/.../jam/`: `JamCodecTest` (все примеры спецификации, кодирование туда и обратно),
`JamClientTest` (поддельный транспорт, виртуальное время `runTest`), `JamSessionCodecTest`.

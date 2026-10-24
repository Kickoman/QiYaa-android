# `track/` — трек и его источник

Модель трека, общая для источников, очереди, плеера и джема: `Track` и `TrackSource`. Пакет только описывает трек —
ничего не загружает, не играет и не знает адресов сервисов. Разбор ответов Яндекса в `Track` — `yandex/`, ссылка на
страницу трека в Яндекс Музыке — `yandex/Models.kt` (`Track.webUrl`), трек протокола джема — `playback/JamTracks`.

Чистая JVM, ничего не импортирует из приложения:

```bash
grep -rln --include='*.kt' '^import io\.github\.kickoman\.qiyaa\.' app/src/main/java/io/github/kickoman/qiyaa/track/   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `Track.kt` | `Track`, `TrackSource` |

## `Track`, `TrackSource`

```kotlin
enum class TrackSource(val key: String) { YANDEX("yandex"), LOCAL("local");  companion object { fun fromKey(key: String): TrackSource? } }

data class Track(
    val id: String, val title: String, val artists: List<String> = emptyList(), val albumId: String = "",
    val durationMs: Long = 0, val available: Boolean = true, val coverUrl: String? = null,
    val source: TrackSource = TrackSource.YANDEX,
) {
    val artistLine: String     // исполнители через ", "
    val displayTitle: String   // "исполнители - название", без исполнителей — название
}
```

`source` — откуда трек, как `Core::TrackKey::source` на ПК. Трек один и тот же, только когда совпадают и `source`, и
`id`: `id` своего у каждого источника, и id трека Яндекса может совпасть с id трека другого источника.

- `YANDEX` — каталог Яндекс Музыки: `id` — id трека, `albumId` — альбом, если известен.
- `LOCAL` — свой файл телефона (Kickoman/QiYaa-android#111). Ни один источник его пока не даёт; на нём проверяются
  правила джема для трека, который может сыграть только хозяин (HOST-37).

`key` — то, что пишется на диск (`queue/QueueSnapshot`) и уходит в данные HMAC id трека хозяина в джеме
(`playback/JamTracks.baseTrack`). Он не меняется; новое значение — новый ключ, а `fromKey` для незнакомого ключа
даёт `null`.

**Traps:**
- Голый `id` — не ключ трека. Всё, что ищет по `id` (ссылки, кэш файлов, лайки, `qiyaa://track/<id>`), сейчас знает
  только Яндекс; где источник надо различать, сказано в Kickoman/QiYaa-android#111.
- В `when (track.source)` не пишут `else`: новый источник (Spotify, #113) должен остановить компиляцию везде, где
  источник что-то решает.

## Not here

- Разбор треков из ответов API, ссылка на страницу трека — `yandex/`.
- Сохранение очереди с источником трека — `queue/QueueSnapshot`.
- Трек протокола джема (`JamTrack`) — `jam/Protocol.kt`, перевод — `playback/JamTracks`.

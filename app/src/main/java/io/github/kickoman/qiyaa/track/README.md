# `track/` — трек и его источник

Модель трека, общая для источников, очереди, плеера и джема: `Track`, `TrackSource` и `TrackKey`. Пакет только описывает трек —
ничего не загружает, не играет и не знает адресов сервисов. Разбор ответов Яндекса в `Track` — `yandex/`, ссылка на
страницу трека в браузере — `ui/TrackPages.kt` (`Track.webUrl`), трек протокола джема — `playback/JamTracks`.

Чистая JVM, ничего не импортирует из приложения:

```bash
grep -rln --include='*.kt' '^import io\.github\.kickoman\.qiyaa\.' app/src/main/java/io/github/kickoman/qiyaa/track/   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `Track.kt` | `Track`, `TrackSource`, `TrackKey` |

## `Track`, `TrackSource`

```kotlin
enum class TrackSource(val key: String) { YANDEX("yandex"), LOCAL("local"), SPOTIFY("spotify");  companion object { fun fromKey(key: String): TrackSource? } }
data class TrackKey(val source: TrackSource, val id: String)

data class Track(
    val id: String, val title: String, val artists: List<String> = emptyList(), val albumId: String = "",
    val durationMs: Long = 0, val available: Boolean = true, val coverUrl: String? = null,
    val source: TrackSource = TrackSource.YANDEX, val bitrateKbps: Int = 0,
) {
    val key: TrackKey          // источник и id: так сравниваются треки
    val isYandex: Boolean      // when (source) без else; только YANDEX
    val artistLine: String     // исполнители через ", "
    val displayTitle: String   // "исполнители - название", без исполнителей — название
}
```

`bitrateKbps` — известный заранее битрейт файла (свой файл: оценка по размеру и длительности, как на ПК), `0` — неизвестен; у трека Яндекса его сообщает подпись ссылки (`playback/CurrentBitrate`).

`source` — откуда трек, как `Core::TrackKey::source` на ПК. Трек один и тот же, только когда совпадают и `source`, и
`id`: `id` своего у каждого источника, и id трека Яндекса может совпасть с id трека другого источника.

- `YANDEX` — каталог Яндекс Музыки: `id` — id трека, `albumId` — альбом, если известен.
- `LOCAL` — свой файл из медиатеки телефона: `id` — `_ID` медиатеки, `albumId` пуст. Его даёт
  `local/LocalTracks.toTrack` (`local/README.md`); в джеме это трек, который может сыграть только хозяин (HOST-37).
- `SPOTIFY` — каталог Spotify (`spec/player/spotify.md`): `id` — base62 id трека, 22 знака, как в `spotify:track:<id>`.
  Его дают выборы Spotify (`spotify/SpotifyTracks`). Как и свой файл, в джеме это трек хозяина (HOST-37), а очередь о нём
  никому не сообщает и не лайкает его (SPOT-19).

`TrackSource.key` — то, что пишется на диск (`queue/QueueSnapshot`) и уходит в данные HMAC id трека хозяина в джеме
(`playback/JamTracks.baseTrack`). Он не меняется; новое значение — новый ключ, а `fromKey` для незнакомого ключа
даёт `null`.

**Traps:**
- Голый `id` — не ключ трека, ключ — `Track.key` (`TrackKey`). Всё, что ищет по `id` (ссылки, кэш файлов, лайки,
  `qiyaa://track/<id>`), знает только Яндекс. Где источник различается: `queue/README.md` («Трек не из Яндекса»:
  отчёты, сиды, лайки; «Очередь без волны по истории»: CONT-12), `playback/README.md` (`mediaId` с источником, URI
  `content://` и `spotify:track:`, `JamTracks`), `ui/README.md` («Трек не из Яндекса», «Свои файлы»), `lyrics/README.md`
  (`SourceLyrics`).
- В `when (track.source)` не пишут `else`: новый источник должен остановить компиляцию везде, где источник что-то
  решает. Так вошёл Spotify (#113). Сравнения не через `when` компилятор не останавливает, их проверяют руками:
  `Track.isYandex` (очередь, экраны, `PlaybackService.localBitrate`), `MediaItems.sourceOf` (префикс `mediaId`, кнопки
  уведомления), `!= TrackSource.YANDEX` в `queue/QueueSnapshot`, `== TrackSource.LOCAL` в `local/LocalCheck`.

## Not here

- Разбор треков из ответов API — `yandex/`; страница трека в браузере — `ui/TrackPages.kt`.
- Сохранение очереди с источником трека — `queue/QueueSnapshot`.
- Трек протокола джема (`JamTrack`) — `jam/Protocol.kt`, перевод — `playback/JamTracks`.

# `playback/` — воспроизведение

ExoPlayer внутри `MediaSessionService`, очередь треков поверх плеера, ленивое разрешение ссылок и два аудиопроцессора, которые вставляют `audio/` в звуковой конвейер. Пакет **не** ходит в сеть сам (это `yandex/`), **не** считает DSP (это `audio/`) и **не** показывает текст: всё, что должен увидеть пользователь, уходит типизированным `QueueEvent`, а `ui/QueueEventText.kt` превращает его в строку из ресурсов.

```bash
grep -rln 'qiyaa\.ui\.' app/src/main/java/io/github/kickoman/qiyaa/playback/   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `PlaybackService.kt` | `PlaybackService` — сборка ExoPlayer и `MediaSession`, уведомление |
| `QueueManager.kt` | `QueueState`, `QueueManager` — источники, волна, выделение, лайки, отметки прослушивания |
| `QueueEvent.kt` | `QueueEvent` — что очередь сообщает наружу |
| `PlaybackFailures.kt` | `FailureKind`, `PlaybackFailures.classify` — вид ошибки воспроизведения по коду и причине |
| `PlayOrder.kt` | `PlayOrder` — порядок воспроизведения из `Timeline` и сколько треков осталось после текущего |
| `ShuffleRule.kt` | `ShuffleRule` — shuffle не действует в волне, выбор пользователя возвращается для обычных очередей |
| `ErrorPolicy.kt` | `ErrorAction`, `ErrorPolicy` — что делать с ошибкой; ожидание сети перед повтором (без Media3-плеера) |
| `MediaItems.kt` | `MediaItems` — `Track` ↔ `MediaItem` |
| `TrackResolver.kt` | `TrackResolver` — `qiyaa://track/{id}` → подписанная ссылка |
| `EqualizerProcessor.kt` | `EqualizerProcessor` — PCM16 → float → EQ → баланс → PCM16 |
| `VisualizerTapProcessor.kt` | `VisualizerTapProcessor` — копия PCM в `VisualizerTap`, звук не меняет |

Зависит от `yandex`, `audio`, `data` и корневого `appGraph` (сервис — точка входа Android и получает граф через `Context.appGraph`).

## Конвейер

`Library` → `QueueManager.setQueue` → `MediaItems.toMediaItem` → ExoPlayer → `ResolvingDataSource(TrackResolver)` → `OkHttpDataSource` → декодер → `DefaultAudioSink[EqualizerProcessor, VisualizerTapProcessor]` → `AudioTrack`. Ссылка на mp3 подписывается в момент открытия потока, поэтому очередь из 300 треков не делает 300 запросов заранее.

## `PlaybackService`

Собирает `ExoPlayer` с `DefaultRenderersFactory`, у которого `buildAudioSink` подменён на `DefaultAudioSink` с двумя процессорами и `enableFloatOutput = false` (процессоры принимают только `ENCODING_PCM_16BIT`). Атрибуты `USAGE_MEDIA`/`AUDIO_CONTENT_TYPE_MUSIC` с `handleAudioFocus = true`, `handleAudioBecomingNoisy`, `WAKE_MODE_NETWORK`, User-Agent `QiYaa/Android`. Стартовая громкость — из `Settings.volume` через `AudioBus.volumeGain`. Тап по уведомлению открывает launcher-intent пакета (сервис не знает про `ui/`). Иконка уведомления — `R.drawable.ic_notification`.

Сервис объявлен `exported="true"` с `tools:ignore="ExportedService"`: так требует Media3, чтобы система и гарнитуры могли привязаться к `MediaSessionService`. `onTaskRemoved` останавливает сервис, если ничего не играет; `onDestroy` отвязывает `QueueManager`, освобождает плеер и сессию.

## `QueueManager`, `QueueState`, `QueueEvent`

```kotlin
data class QueueState(tracks: List<Track>, title: String, isWave: Boolean, loadingMore: Boolean, selected: Set<Int>, activeSourceId: String?)

class QueueManager(library: Library, api: YandexApi, connectivity: Flow<Boolean>) {   // connectivity = NetworkMonitor.available
    val state: StateFlow<QueueState>;  val events: SharedFlow<QueueEvent>;  val player: Player?
    fun attach(player: Player);  fun detach(player: Player)
    fun loadSource(title: String, sourceId: String? = null, autoplay: Boolean = true, loader: suspend () -> List<Track>)
    fun playWave(seeds: List<String>, title: String, sourceId: String = seeds.first())
    fun search(text: String, title: String)
    fun setQueue(tracks, title, isWave, autoplay, sourceId);  fun appendTracks(tracks);  fun clear()
    fun removeIndices(indices: Set<Int>);  fun toggleSelected(index: Int);  fun selectAllOrNone();  fun syncFromPlayer()
    fun toggleLike(track: Track);  fun dislikeAndSkip(track: Track)
    companion object { MY_WAVE_SEED = "user:onyourwave"; LOAD_MORE_WHEN_LEFT = 2; WAVE_HISTORY = 5 }
}

sealed interface QueueEvent {
    SourceLoading(title); SearchStarted(title); SourceLoaded(title, trackCount); SourceEmpty(title); WaveStarted(title)
    NothingFound; PlayerNotReady; LikeChanged(liked); DislikedAndSkipped
    WaitingForNetwork; StoppedAfterFailures(count)
    Failed(stage: Stage, message)   // Stage: SOURCE, WAVE, WAVE_MORE, SEARCH, LIKE, PLAYBACK
}
```

Порт очереди из `src/core/Player.cpp` десктопной версии. Все методы вызываются с главного потока (`Dispatchers.Main.immediate`); загрузчики выполняются на `Dispatchers.IO`.

Гарантии:
- **Только последний запрос источника применяется.** Каждый `loadSource`/`playWave`/`search` берёт билет; результат с устаревшим билетом отбрасывается молча, его ошибка тоже.
- **Недоступные треки** (`available == false`) не попадают в очередь.
- **Волна догружается**, когда текущий трек — один из двух последних в порядке воспроизведения (`LOAD_MORE_WHEN_LEFT = 2` считает текущий вместе с оставшимися, то есть после него ≤ 1): `moreWave(sessionId, последние 5 id)`. Остаток считает `PlayOrder` по порядку воспроизведения, а не по индексам таймлайна; если плеер уже дошёл до конца, воспроизведение продолжается с первого догруженного. Поколение очереди (`queueGeneration`) защищает от применения догрузки к уже заменённой очереди.
- **Отметка прослушивания** (`reportPlayStarted`) отправляется один раз на смену `mediaId`, best-effort, ошибки игнорируются.
- **Ошибка воспроизведения** разбирается по виду, см. «Ошибки воспроизведения» ниже: без сети очередь не проматывается.
- **Dislike** = запрос `dislike` + переход к следующему; в конечной очереди без следующего — стоп, в волне — ждём догрузку.
- **Shuffle в волне не действует**, как в приложении Яндекса: порядок задаёт сервер. `setQueue` ставит плееру `ShuffleRule.playerModeFor(isWave)`: волна — выключен, обычная очередь — последний выбор пользователя. `onShuffleModeEnabledChanged` возвращает выключенный режим, если shuffle включили во время волны с любого контроллера.
- `search`: если лучший результат — исполнитель или альбом, играют его треки под его именем, иначе найденные треки под `title`.

`events` — `MutableSharedFlow(extraBufferCapacity = 8)` с `tryEmit`: без подписчика события теряются, это нормально для тостов.

**Traps:**
- `title` — данные из UI или сервера (имя плейлиста, `getString(R.string.library_my_wave)`), не константа в этом пакете.
- `syncFromPlayer` синхронизирует `state.tracks` с таймлайном плеера только по числу элементов; порядок при shuffle плеер хранит сам.
- `removeIndices` удаляет по убыванию индексов, иначе сдвиг сломает выборку.
- `DefaultShuffleOrder` вставляет дописанные треки в случайные места перемешанного порядка: часть догрузки волны оказалась бы позади текущего трека и не сыграла. Поэтому в волне shuffle запрещён, а не «исправлен».
- Выбор shuffle хранится только в памяти `ShuffleRule` и после перезапуска процесса сбрасывается на «выключен».

## Ошибки воспроизведения

```kotlin
enum class FailureKind { NETWORK, SESSION, TRACK }
object PlaybackFailures { fun classify(errorCode: Int, cause: Throwable?): FailureKind }

sealed interface ErrorAction { WaitForNetwork; Hold; SkipToNext; Stop }
object ErrorPolicy {
    const val MAX_CONSECUTIVE_TRACK_FAILURES = 3
    fun decide(kind: FailureKind, consecutiveTrackFailures: Int, hasNext: Boolean): ErrorAction
    suspend fun awaitRetry(connectivity: Flow<Boolean>, attempt: Int)
    fun retryDelayMs(attempt: Int): Long         // 2, 4, 8 … 60 с, те же пределы, что у yandex/Session
}
```

Вид определяется по коду `PlaybackException` и по цепочке `cause`, никогда по тексту. Проверки идут сверху вниз:

| Вид | Признак | Действие в `QueueManager` |
|---|---|---|
| `SESSION` | в цепочке `HttpException` с `isTokenRejected` | `Hold`: остаться на месте, `Failed(PLAYBACK)`; `Session` уведёт на вход через `tokenRejections` |
| `NETWORK` | код 2001/2002 (`IO_NETWORK_CONNECTION_*`) или в цепочке `NetworkException`, `UnknownHost`, `Connect`, `SocketTimeout`, `NoRouteToHost` | `WaitForNetwork`: пауза на текущем треке, один раз `WaitingForNetwork`; когда `awaitRetry` вернулся — `prepare()` |
| `TRACK` | всё остальное: 2004 (статус хранилища), `HttpException` 4xx/5xx от API, `MalformedResponseException`, парсинг 3xxx, декодер 4xxx | 1-я и 2-я неудача подряд — `SkipToNext` с `Failed(PLAYBACK)`; 3-я или нет следующего трека — `Stop` и `StoppedAfterFailures(3)` |

`awaitRetry`: если сети нет, ждёт её и возвращается сразу, как только она появилась; если сеть есть, а загрузка всё равно падает, ждёт `retryDelayMs(attempt)`. После ошибки ExoPlayer в `STATE_IDLE` помнит позицию и `playWhenReady`, поэтому `prepare()` продолжает с того же места и играет, только если играло до ошибки: пауза, поставленная за время ожидания, сохраняется.

Счётчики (`consecutiveTrackFailures`, номер попытки сети) и ожидание сбрасываются, когда трек реально заиграл (`onIsPlayingChanged(true)`), и при `setQueue`/`clear`.

**Traps:**
- ExoPlayer сам повторяет сетевые ошибки загрузчика (около 3 попыток с паузой до 5 с) до `onPlayerError`, поэтому пауза наступает через несколько секунд после пропажи сети, а не сразу. Пока в буфере есть звук, трек доигрывает.
- Ошибки `TrackResolver` приходят с общим кодом 2000 (`IO_UNSPECIFIED`): отличить сеть от битого трека можно только по причине, поэтому `yandex` бросает типизированные исключения.
- При `Stop` плеер остаётся в `IDLE` на неигравшем треке; тап по треку или «плей» пробуют снова.
- Десктоп при ошибке получения ссылки останавливается; правило «сеть → пауза, трек → пропуск, не больше 3» нужно записать в общие сценарии (Kickoman/QiYaa#2), чтобы стороны не разошлись.

## `MediaItems`, `TrackResolver`

URI трека виртуальный: `qiyaa://track/{id}` (`MediaItems.SCHEME`). `mediaId = track.id`. В `MediaMetadata.extras` (`Bundle`) лежат `id`, `albumId`, `durationMs` (long), `artists` (string array list), `cover` (nullable string), `title`, чтобы `toTrack` восстанавливал `Track` из уведомления и после пересоздания процесса без сети. `artworkUri` = `coverUrl`.

`TrackResolver.resolveDataSpec` вызывается на loading-потоке ExoPlayer; `runBlocking { api.resolveTrackUrl(id) }` там допустим. Битрейт выбранного варианта публикуется в `AudioBus.bitrateKbps`. URI с другой схемой проходит без изменений. Исключения `yandex` — `IOException`, поэтому ExoPlayer превращает их в `PlaybackException`, а не падает (см. `yandex/README.md`).

## Процессоры

Оба — `BaseAudioProcessor`, только `ENCODING_PCM_16BIT` (иначе `UnhandledAudioFormatException`). `EqualizerProcessor.onConfigure` сообщает формат в `AudioBus.setFormat`. `queueInput`: пустой буфер возвращается сразу — `replaceOutputBuffer(0)` в Media3 1.4 отдаёт общий `EMPTY_BUFFER`, и `put(inputBuffer)` на нём бросает «The source buffer is this buffer». `onFlush`/`onReset` сбрасывают состояние EQ и очищают кольцо.

## Not here

- Тексты тостов — `ui/QueueEventText.kt` и `res/values*/strings.xml`. Подпись ссылки — `yandex/TrackUrl`. `MediaController` со стороны UI — `ui/PlayerViewModel`.

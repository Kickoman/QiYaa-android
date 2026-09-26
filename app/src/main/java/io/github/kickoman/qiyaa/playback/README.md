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

class QueueManager(library: Library, api: YandexApi) {
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
    Failed(stage: Stage, message)   // Stage: SOURCE, WAVE, WAVE_MORE, SEARCH, LIKE, PLAYBACK
}
```

Порт очереди из `src/core/Player.cpp` десктопной версии. Все методы вызываются с главного потока (`Dispatchers.Main.immediate`); загрузчики выполняются на `Dispatchers.IO`.

Гарантии:
- **Только последний запрос источника применяется.** Каждый `loadSource`/`playWave`/`search` берёт билет; результат с устаревшим билетом отбрасывается молча, его ошибка тоже.
- **Недоступные треки** (`available == false`) не попадают в очередь.
- **Волна догружается**, когда после текущего трека остаётся ≤ 2 (`LOAD_MORE_WHEN_LEFT`): `moreWave(sessionId, последние 5 id)`; если плеер уже дошёл до конца, воспроизведение продолжается с первого догруженного. Поколение очереди (`queueGeneration`) защищает от применения догрузки к уже заменённой очереди.
- **Отметка прослушивания** (`reportPlayStarted`) отправляется один раз на смену `mediaId`, best-effort, ошибки игнорируются.
- **Ошибка воспроизведения** → `Failed(PLAYBACK)` и переход к следующему треку, как в десктопной версии.
- **Dislike** = запрос `dislike` + переход к следующему; в конечной очереди без следующего — стоп, в волне — ждём догрузку.
- `search`: если лучший результат — исполнитель или альбом, играют его треки под его именем, иначе найденные треки под `title`.

`events` — `MutableSharedFlow(extraBufferCapacity = 8)` с `tryEmit`: без подписчика события теряются, это нормально для тостов.

**Traps:**
- `title` — данные из UI или сервера (имя плейлиста, `getString(R.string.library_my_wave)`), не константа в этом пакете.
- `syncFromPlayer` синхронизирует `state.tracks` с таймлайном плеера только по числу элементов; порядок при shuffle плеер хранит сам.
- `removeIndices` удаляет по убыванию индексов, иначе сдвиг сломает выборку.

## `MediaItems`, `TrackResolver`

URI трека виртуальный: `qiyaa://track/{id}` (`MediaItems.SCHEME`). `mediaId = track.id`. В `MediaMetadata.extras` (`Bundle`) лежат `id`, `albumId`, `durationMs` (long), `artists` (string array list), `cover` (nullable string), `title`, чтобы `toTrack` восстанавливал `Track` из уведомления и после пересоздания процесса без сети. `artworkUri` = `coverUrl`.

`TrackResolver.resolveDataSpec` вызывается на loading-потоке ExoPlayer; `runBlocking { api.resolveTrackUrl(id) }` там допустим. Битрейт выбранного варианта публикуется в `AudioBus.bitrateKbps`. URI с другой схемой проходит без изменений. Исключения `yandex` — `IOException`, поэтому ExoPlayer превращает их в `PlaybackException`, а не падает (см. `yandex/README.md`).

## Процессоры

Оба — `BaseAudioProcessor`, только `ENCODING_PCM_16BIT` (иначе `UnhandledAudioFormatException`). `EqualizerProcessor.onConfigure` сообщает формат в `AudioBus.setFormat`. `queueInput`: пустой буфер возвращается сразу — `replaceOutputBuffer(0)` в Media3 1.4 отдаёт общий `EMPTY_BUFFER`, и `put(inputBuffer)` на нём бросает «The source buffer is this buffer». `onFlush`/`onReset` сбрасывают состояние EQ и очищают кольцо.

## Not here

- Тексты тостов — `ui/QueueEventText.kt` и `res/values*/strings.xml`. Подпись ссылки — `yandex/TrackUrl`. `MediaController` со стороны UI — `ui/PlayerViewModel`.

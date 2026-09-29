# `playback/` — воспроизведение

ExoPlayer внутри `MediaSessionService`, адаптер между ExoPlayer и очередью, классификация ошибок воспроизведения, ленивое разрешение ссылок и два аудиопроцессора, которые вставляют `audio/` в звуковой конвейер. Пакет **не** решает, что играть дальше (это `queue/`), **не** ходит в сеть сам (это `yandex/`), **не** считает DSP (это `audio/`) и **не** показывает текст.

```bash
grep -rln 'qiyaa\.ui\.' app/src/main/java/io/github/kickoman/qiyaa/playback/   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `PlaybackService.kt` | `PlaybackService` — сборка ExoPlayer и `MediaSession`, уведомление, подключение `Media3Engine` |
| `Media3Engine.kt` | `Media3Engine` — `queue.PlayerEngine` поверх `Player`; пересылает события листенера в `QueueController` |
| `QueueForwardingPlayer.kt` | `QueueForwardingPlayer` — плеер, которого видит `MediaSession`: «вперёд» и «назад» уходят в `QueueController` |
| `NotificationButtons.kt` | `NotificationButtons` — команды сессии «лайк» и «дизлайк» и их кнопки в уведомлении |
| `PlaybackFailures.kt` | `PlaybackFailures.classify` — `queue.FailureKind` по коду `PlaybackException` и причине |
| `MediaItems.kt` | `MediaItems` — `Track` ↔ `MediaItem` |
| `TrackResolver.kt` | `TrackResolver` — `qiyaa://track/{id}` → подписанная ссылка |
| `EqualizerProcessor.kt` | `EqualizerProcessor` — PCM16 → float → EQ → баланс → PCM16 |
| `VisualizerTapProcessor.kt` | `VisualizerTapProcessor` — копия PCM в `VisualizerTap`, звук не меняет |

Зависит от `queue`, `yandex`, `audio`, `data` и корневого `appGraph` (сервис — точка входа Android и получает граф через `Context.appGraph`).

## Конвейер

`QueueController.setQueue` → `Media3Engine.setTracks` → `MediaItems.toMediaItem` → ExoPlayer → `ResolvingDataSource(TrackResolver)` → `OkHttpDataSource` → декодер → `DefaultAudioSink[EqualizerProcessor, VisualizerTapProcessor]` → `AudioTrack`. Ссылка на mp3 подписывается в момент открытия потока, поэтому очередь из 300 треков не делает 300 запросов заранее.

## `PlaybackService`

Собирает `ExoPlayer` с `DefaultRenderersFactory`, у которого `buildAudioSink` подменён на `DefaultAudioSink` с двумя процессорами и `enableFloatOutput = false` (процессоры принимают только `ENCODING_PCM_16BIT`). Атрибуты `USAGE_MEDIA`/`AUDIO_CONTENT_TYPE_MUSIC` с `handleAudioFocus = true`, `handleAudioBecomingNoisy`, `WAKE_MODE_NETWORK`, User-Agent `QiYaa/Android`. Стартовая громкость — из `Settings.volume` через `AudioBus.volumeGain`. Тап по уведомлению открывает launcher-intent пакета (сервис не знает про `ui/`). Иконка уведомления — `R.drawable.ic_notification`.

Сервис объявлен `exported="true"` с `tools:ignore="ExportedService"`: так требует Media3, чтобы система и гарнитуры могли привязаться к `MediaSessionService`. `onCreate` создаёт `Media3Engine(player, appGraph.queue)`, подключает его и отдаёт сессии `QueueForwardingPlayer(player, appGraph.queue)`; сессия получает `SessionCallback` (кнопки лайка и дизлайка, «играть» после выгрузки процесса); `onTaskRemoved` останавливает сервис, если ничего не играет; `onDestroy` отключает движок (контроллер запоминает, где остановились), освобождает плеер и сессию.

## Один хозяин плеера

У плеера один хозяин — `QueueController` из `AppGraph` (вариант «синглтон» из Kickoman/QiYaa-android#6; приложение однопроцессное, поэтому очередь не сериализуется в команды и `sessionExtras` сессии). Все пути к плееру сходятся в нём:

| Откуда | Путь |
|---|---|
| Кнопки приложения | `ui/PlayerViewModel` → `MediaController` → `MediaSession` → `QueueForwardingPlayer` → `QueueController.next()`/`previous()` |
| Уведомление, экран блокировки, гарнитура, Bluetooth | `MediaSession` → `QueueForwardingPlayer` → `QueueController` |
| Выбор источника, правка очереди, лайки | `ui/AppViewModel` → `QueueController` напрямую |
| События ExoPlayer | `Media3Engine` (листенер) → `QueueController` |

Сервис может пересоздаваться при живом процессе (смахнули из недавних → `onTaskRemoved` → `stopSelf`, потом приложение открыли снова). `onDestroy` отключает движок, и контроллер запоминает трек и позицию; новый `Media3Engine` получает ту же очередь на паузе в том же месте. Источник, выбранный, пока сервис ещё не поднялся, применяется, как только движок подключится. После выгрузки процесса очередь восстанавливает сам `QueueController` из `data/QueueFile` (см. `queue/README.md`), и новый сервис при `attach` получает её на паузе в том же месте.

## «Играть» после выгрузки процесса

Кнопка гарнитуры или карточка возобновления в системе доходят до приложения через `androidx.media3.session.MediaButtonReceiver` в манифесте (`exported`, `android.intent.action.MEDIA_BUTTON`). Он поднимает `PlaybackService`, `onCreate` подключает движок, и очередь уже стоит в плеере. Если плеер всё же пуст, Media3 вызывает `MediaSession.Callback.onPlaybackResumption`: `ResumptionCallback` отдаёт треки, индекс и позицию из `QueueController.resumePoint()`, а при пустой очереди — неудачный `Future`, и команда ничего не делает.

## Лайк и дизлайк в уведомлении

```kotlin
object NotificationButtons {
    const val ACTION_LIKE = "io.github.kickoman.qiyaa.LIKE";  const val ACTION_DISLIKE = "io.github.kickoman.qiyaa.DISLIKE"
    fun withCustomCommands(commands: SessionCommands): SessionCommands
    fun layout(context: Context, liked: Boolean): List<CommandButton>   // [♥ залитое или пустое, 👎]
}
```

Две пользовательские команды сессии без аргументов (`Bundle.EMPTY`). `SessionCallback.onConnect` добавляет их к `DEFAULT_SESSION_COMMANDS` каждому контроллеру (уведомление, экран блокировки, `ui/PlayerViewModel`) и отдаёт текущую раскладку. `onCustomCommand` передаёт `ACTION_LIKE` в `QueueController.likeCurrent()`, `ACTION_DISLIKE` — в `dislikeCurrent()`; сам трек выбирает очередь, поэтому дизлайк ведёт себя как кнопка на экране плеера (TR-07, TRK-07: запрос, `skip` в фидбек волны, «вперёд»). Незнакомая команда — `ERROR_NOT_SUPPORTED`.

Состояние ♥: сервис держит `mediaId` текущего трека (листенер `onMediaItemTransition`) и объединяет его с `Library.likedIds`; при изменении вызывает `MediaSession.setCustomLayout`. Так ♥ меняется при смене трека, при лайке из шторки и при лайке с экрана плеера. Экран плеера читает те же `likedIds`, поэтому лайк из шторки виден там сразу после ответа сервера.

Значки — встроенные `CommandButton.ICON_HEART_FILLED`/`ICON_HEART_UNFILLED` и `ICON_THUMB_DOWN_UNFILLED`. Подписи (для TalkBack) — строки `notification_like`, `notification_unlike`, `notification_dislike` из `res/values*/strings.xml`: сервис — точка входа Android и берёт ресурсы, как и иконку уведомления; событий и текста для тостов пакет по-прежнему не производит.

Где стоят кнопки, решает система: в развёрнутом уведомлении — после «назад», «плей», «вперёд»; в медиапанели Android 13+ и на экране блокировки — в двух свободных слотах.

**Traps:**
- `CommandButton.Builder(icon)` в Media3 1.4.1 помечен `@UnstableApi`, поэтому `NotificationButtons` помечен так же, как сервис.
- Команда, которой нет в `setAvailableSessionCommands`, не доходит до `onCustomCommand`, а кнопка с ней не показывается.

## `QueueForwardingPlayer`

`ForwardingPlayer` над ExoPlayer: `seekToNext`/`seekToNextMediaItem` → `QueueController.next()`, `seekToPrevious`/`seekToPreviousMediaItem` → `previous()`. Всё остальное (play, pause, seek по позиции, громкость) уходит в ExoPlayer как есть.

`getAvailableCommands` добавляет `COMMAND_SEEK_TO_NEXT(_MEDIA_ITEM)` и `COMMAND_SEEK_TO_PREVIOUS(_MEDIA_ITEM)`, когда в очереди есть треки: сам ExoPlayer убирает «вперёд» на последнем треке, и тогда сессия отбросила бы команду, а в конце волны она значит «догрузить» (WAVE-09), а в конце конечной очереди — «стоп» (TR-04).

**Traps:**
- `MediaSession` берёт набор команд и из `getAvailableCommands`, и из события `onAvailableCommandsChanged`; поэтому листенеры оборачиваются (`TransportCommandsListener`), и событие несёт тот же расширенный набор.
- `Media3Engine` управляет самим ExoPlayer, а не `QueueForwardingPlayer`: иначе `QueueController.next()` → `skipToNext()` вернулся бы в `next()`.

## `Media3Engine`

```kotlin
class Media3Engine(player: Player, controller: QueueController) : PlayerEngine {
    fun attach()     // addListener + controller.attach(this)
    fun detach()
}
```

Тонкий: ни одного решения, только перевод. Команды `PlayerEngine` → вызовы `Player` (`setTracks` = `setMediaItems(…, startIndex, startPositionMs)` + `prepare()` + `playWhenReady`; `clear` = `clearMediaItems`; `seekTo(i)` = `seekTo(i, 0)`; `seekToPosition` = `seekTo(ms)`; `skipToNext`/`skipToPrevious` = `seekToNext/PreviousMediaItem`). События `Player.Listener` → методы контроллера:

| Событие ExoPlayer | Вызов |
|---|---|
| `onMediaItemTransition(item, reason)` | `onItemChanged(MediaItems.toTrack(item), transition, player.isPlaying)`; причина: `AUTO` → `AUTO`, `REPEAT` → `REPEAT`, `SEEK` → `SEEK`, остальное (`PLAYLIST_CHANGED`) → `NEW_QUEUE` |
| `onPlaybackStateChanged(STATE_ENDED)` | `onEnded()` |
| `onShuffleModeEnabledChanged(on)` | `onShuffleChanged(on)` |
| `onRepeatModeChanged(mode)` | `onRepeatChanged(mode != REPEAT_MODE_OFF)`; `repeatEnabled = true` — это `REPEAT_MODE_ALL` (повтора одного трека нет, TR-06) |
| `onIsPlayingChanged(playing)` | `onPlayingChanged(playing)` |
| `onPlayerError(error)` | `onFailure(PlaybackFailures.classify(error.errorCode, error.cause), error.cause?.message ?: error.errorCodeName)` |

`playOrder()` обходит `currentTimeline` через `getFirstWindowIndex`/`getNextWindowIndex(…, REPEAT_MODE_OFF, shuffle)`.

**Traps:** все вызовы — с главного потока ExoPlayer; листенеры ExoPlayer срабатывают после завершения команды, так что контроллер видит уже обновлённое состояние.

## Классификация ошибок

`PlaybackFailures.classify` определяет вид по коду `PlaybackException` и по цепочке `cause`, никогда по тексту. Проверки идут сверху вниз:

| Вид | Признак |
|---|---|
| `SESSION` | в цепочке `HttpException` с `isTokenRejected` |
| `NETWORK` | код 2001/2002 (`IO_NETWORK_CONNECTION_*`) или в цепочке `NetworkException`, `UnknownHost`, `Connect`, `SocketTimeout`, `NoRouteToHost` |
| `TRACK` | всё остальное: 2004 (статус хранилища), `HttpException` 4xx/5xx от API, `MalformedResponseException`, парсинг 3xxx, декодер 4xxx |

Что делать с каждым видом, решает `queue/ErrorPolicy` (см. `queue/README.md`).

**Traps:**
- ExoPlayer сам повторяет сетевые ошибки загрузчика (около 3 попыток с паузой до 5 с) до `onPlayerError`, поэтому пауза наступает через несколько секунд после пропажи сети, а не сразу. Пока в буфере есть звук, трек доигрывает.
- Ошибки `TrackResolver` приходят с общим кодом 2000 (`IO_UNSPECIFIED`): отличить сеть от битого трека можно только по причине, поэтому `yandex` бросает типизированные исключения.

## `MediaItems`, `TrackResolver`

URI трека виртуальный: `qiyaa://track/{id}` (`MediaItems.SCHEME`). `mediaId = track.id`. В `MediaMetadata.extras` (`Bundle`) лежат `id`, `albumId`, `durationMs` (long), `artists` (string array list), `cover` (nullable string), `title`, чтобы `toTrack` восстанавливал `Track` из уведомления и после пересоздания процесса без сети. `artworkUri` = `coverUrl`.

`TrackResolver.resolveDataSpec` вызывается на loading-потоке ExoPlayer; `runBlocking { api.resolveTrackUrl(id) }` там допустим. Битрейт выбранного варианта публикуется в `AudioBus.bitrateKbps`. URI с другой схемой проходит без изменений. Исключения `yandex` — `IOException`, поэтому ExoPlayer превращает их в `PlaybackException`, а не падает (см. `yandex/README.md`).

## Процессоры

Оба — `BaseAudioProcessor`, только `ENCODING_PCM_16BIT` (иначе `UnhandledAudioFormatException`). `EqualizerProcessor.onConfigure` сообщает формат в `AudioBus.setFormat`. `queueInput`: пустой буфер возвращается сразу — `replaceOutputBuffer(0)` в Media3 1.4 отдаёт общий `EMPTY_BUFFER`, и `put(inputBuffer)` на нём бросает «The source buffer is this buffer». `onFlush`/`onReset` сбрасывают состояние EQ и очищают кольцо.

## Not here

- Что играть дальше, «вперёд»/«назад», источники, волна, политика ошибок, shuffle и повтор — `queue/`. Тексты тостов — `ui/QueueEventText.kt` и `res/values*/strings.xml`. Подпись ссылки — `yandex/TrackUrl`. `MediaController` со стороны UI — `ui/PlayerViewModel`.

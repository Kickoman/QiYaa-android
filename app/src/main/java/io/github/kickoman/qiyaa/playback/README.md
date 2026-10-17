# `playback/` — воспроизведение

ExoPlayer внутри `MediaSessionService`, адаптер между ExoPlayer и очередью, хозяин джема, классификация ошибок воспроизведения, ленивое разрешение ссылок и два аудиопроцессора, которые вставляют `audio/` в звуковой конвейер. Пакет **не** решает, что играть дальше (это `queue/`), **не** ходит в сеть сам (это `yandex/`), **не** считает DSP (это `audio/`) и **не** показывает текст.

```bash
grep -rln 'qiyaa\.ui\.' app/src/main/java/io/github/kickoman/qiyaa/playback/   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `PlaybackService.kt` | `PlaybackService` — сборка ExoPlayer и `MediaSession`, уведомление, подключение `Media3Engine` |
| `Media3Engine.kt` | `Media3Engine` — `queue.PlayerEngine` поверх `ExoPlayer`; ставит порядок shuffle `HeadShuffleOrder` и паузу в конце трека для заворота головы; пересылает события листенера в `QueueController` |
| `HeadShuffleOrder.kt` | `HeadShuffleOrder` — `ShuffleOrder` ExoPlayer по `queue.HeadShuffle`: проход головы, затем остальное по индексу; заранее поставленный проход для `setMediaItems` |
| `QueueForwardingPlayer.kt` | `QueueForwardingPlayer` — плеер, которого видит `MediaSession`: «вперёд» и «назад», shuffle и повтор уходят в `QueueController` |
| `NotificationButtons.kt` | `NotificationButtons` — команды сессии «лайк» и «дизлайк» и их кнопки в уведомлении |
| `PlaybackFailures.kt` | `PlaybackFailures.classify` — `queue.FailureKind` по коду `PlaybackException` и причине; `errorKind` — `yandex.ErrorKind` для пользователя |
| `MediaItems.kt` | `MediaItems` — `Track` ↔ `MediaItem` |
| `TrackResolver.kt` | `TrackResolver` — `qiyaa://track/{id}` → подписанная ссылка из `TrackUrlCache` |
| `TrackUrlCache.kt` | `TrackUrlCache` — подписанные ссылки по `trackId` на 15 минут, до 64 штук, с качеством каждой |
| `ExpiredLinkDataSource.kt` | `ExpiredLinkDataSource` — при 403/410 от хранилища сбрасывает ссылку и открывает поток заново |
| `LogTag.kt` | `LOG_TAG = "QiYaa"` — тег logcat пакета |
| `CurrentBitrate.kt` | `CurrentBitrate` — битрейт по треку; в `AudioBus` попадает битрейт текущего трека |
| `EqualizerProcessor.kt` | `EqualizerProcessor` — PCM16 → float → EQ → баланс → PCM16 |
| `VisualizerTapProcessor.kt` | `VisualizerTapProcessor` — копия PCM в `VisualizerTap`, звук не меняет |
| `TimedAudioSink.kt` | `TimedAudioSink` — `ForwardingAudioSink`: метки времени входных буферов и звучащая позиция для `VisualizerTap` |
| `OkHttpJamTransport.kt` | `OkHttpJamTransport` — `jam.JamTransport` на WebSocket OkHttp: без тайм-аута чтения, свой ping раз в 20 с, одно `onClosed` на сокет; адрес, который не URL, — как неудачное соединение |
| `JamHost.kt` | `JamHost`, `JamHostState`, `JamHostEvent`, `JamCatalog`, `JamHostConfig` — хозяин джема: сервер, режим джема очереди и запросы гостей |
| `JamTracks.kt` | `JamTracks` — `yandex.Track` ↔ `jam.JamTrack` с пределами протокола |

Зависит от `queue`, `jam`, `yandex`, `audio`, `data` и корневого `appGraph` (сервис — точка входа Android и получает граф через `Context.appGraph`).

## Конвейер

`QueueController.setQueue` → `Media3Engine.setTracks` → `MediaItems.toMediaItem` → ExoPlayer → `ExpiredLinkDataSource` → `ResolvingDataSource(TrackResolver → TrackUrlCache)` → `OkHttpDataSource` → декодер → `DefaultAudioSink[EqualizerProcessor, VisualizerTapProcessor]` → `AudioTrack`. Ссылка на mp3 подписывается в момент открытия потока, поэтому очередь из 300 треков не делает 300 запросов заранее.

## `PlaybackService`

Собирает `ExoPlayer` с `DefaultRenderersFactory`, у которого `buildAudioSink` подменён на `DefaultAudioSink` с двумя процессорами и `enableFloatOutput = false` (процессоры принимают только `ENCODING_PCM_16BIT`). Атрибуты `USAGE_MEDIA`/`AUDIO_CONTENT_TYPE_MUSIC` с `handleAudioFocus = true`, `handleAudioBecomingNoisy`, `WAKE_MODE_NETWORK`, User-Agent `QiYaa/Android`. Громкость сервис берёт из `Settings.volume` и следит за ней сам (`serviceScope`, `AudioBus.volumeGain`), поэтому она применяется и без открытого экрана.volumeGain`. Тап по уведомлению открывает launcher-intent пакета (сервис не знает про `ui/`). Иконка уведомления — `R.drawable.ic_notification`.

Сервис объявлен `exported="true"` с `tools:ignore="ExportedService"`: так требует Media3, чтобы система и гарнитуры могли привязаться к `MediaSessionService`. `onCreate` создаёт `Media3Engine(player, appGraph.queue)`, подключает его и отдаёт сессии `QueueForwardingPlayer(player, appGraph.queue)`; сессия получает `SessionCallback` (кнопки лайка и дизлайка, «играть» после выгрузки процесса); `onTaskRemoved` останавливает сервис, если ничего не играет; `onDestroy` отключает движок (контроллер запоминает, где остановились), освобождает плеер и сессию. Оба сообщают `appGraph.jamHost` (`onServiceStarted`/`onServiceStopped`), см. `JamHost`. `attachBaseContext` даёт сервису язык интерфейса (`AppLocale.wrap`), а смена языка во время работы перестраивает кнопки уведомления (см. `ui/README.md`, «Язык интерфейса»).

## Один хозяин плеера

У плеера один хозяин — `QueueController` из `AppGraph` (вариант «синглтон» из Kickoman/QiYaa-android#6; приложение однопроцессное, поэтому очередь не сериализуется в команды и `sessionExtras` сессии). Все пути к плееру сходятся в нём:

| Откуда | Путь |
|---|---|
| Кнопки приложения | `ui/PlayerViewModel` → `MediaController` → `MediaSession` → `QueueForwardingPlayer` → `QueueController.next()`/`previous()`; SHF и RPT — `QueueController.setShuffle()`/`setRepeat()` напрямую |
| Уведомление, экран блокировки, гарнитура, Bluetooth | `MediaSession` → `QueueForwardingPlayer` → `QueueController`: «вперёд», «назад», shuffle, повтор |
| Выбор источника, правка очереди, лайки | `ui/AppViewModel` → `QueueController` напрямую |
| События ExoPlayer | `Media3Engine` (листенер) → `QueueController` |

Сервис может пересоздаваться при живом процессе (смахнули из недавних → `onTaskRemoved` → `stopSelf`, потом приложение открыли снова). `onDestroy` отключает движок, и контроллер запоминает трек и позицию; новый `Media3Engine` получает ту же очередь на паузе в том же месте. Источник, выбранный, пока сервис ещё не поднялся, применяется, как только движок подключится. После выгрузки процесса очередь восстанавливает сам `QueueController` из `data/QueueFile` (см. `queue/README.md`), и новый сервис при `attach` получает её на паузе в том же месте.

## «Играть» после выгрузки процесса

Кнопка гарнитуры или карточка возобновления в системе доходят до приложения через `androidx.media3.session.MediaButtonReceiver` в манифесте (`exported`, `android.intent.action.MEDIA_BUTTON`). Он поднимает `PlaybackService`, `onCreate` подключает движок, и очередь уже стоит в плеере. Если плеер всё же пуст, Media3 вызывает `MediaSession.Callback.onPlaybackResumption`: `ResumptionCallback` отдаёт треки, индекс и позицию из `QueueController.resumePoint()`, а при пустой очереди — неудачный `Future`, и команда ничего не делает. Эти треки сессия ставит в ExoPlayer мимо `Media3Engine`; `HeadShuffleOrder`, поставленный при `attach`, делает из них случайный проход, и все они — голова.

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

`ForwardingPlayer` над ExoPlayer: `seekToNext`/`seekToNextMediaItem` → `QueueController.next()`, `seekToPrevious`/`seekToPreviousMediaItem` → `previous()`, `setShuffleModeEnabled(on)` → `setShuffle(on)`, `setRepeatMode(mode)` → `setRepeat(mode != REPEAT_MODE_OFF)`. Всё остальное (play, pause, seek по позиции, громкость) уходит в ExoPlayer как есть. Режимы ExoPlayer ставит только контроллер (`queue/README.md`).

**Отклонённый выбор публикуется заново.** Если после `setShuffle`/`setRepeat` режим ExoPlayer не равен запрошенному, ExoPlayer события не даёт, и контроллер сессии (уведомление, Bluetooth) остался бы со своим замаскированным значением. Так бывает, когда выбор отклонён (волна, станция, джем), когда повтор выбран, но в ExoPlayer не включён (курсор в продолжении — CONT-11 — или голову с продолжением за ней заворачивает сам контроллер — CONT-10, `queue/README.md`), или когда запрошен `REPEAT_MODE_ONE`: повтор бывает только всей очереди (TR-06), и действует `REPEAT_MODE_ALL`. Тогда `QueueForwardingPlayer` сам зовёт `onShuffleModeEnabledChanged`/`onRepeatModeChanged` с действующим значением у всех слушателей сессии. `MediaSessionImpl.PlayerListener` в Media3 1.4.1 на каждый такой вызов обновляет `PlayerInfo` и рассылает его контроллерам, не сравнивая со старым значением (сверено по байткоду AAR через `javap`, на устройстве не проверялось). Внешние контроллеры поэтому видят режим, который действует в ExoPlayer; экран приложения показывает выбор пользователя (`QueueController.modes`). При завороте головы контроллером это значит, что Bluetooth и уведомление показывают повтор выключенным, хотя голова повторяется; нажатие «повтор» там снова включает выбор, ничего не меняет и снова публикует «выключено».

`getAvailableCommands` добавляет `COMMAND_SEEK_TO_NEXT(_MEDIA_ITEM)` и `COMMAND_SEEK_TO_PREVIOUS(_MEDIA_ITEM)`, когда в очереди есть треки: сам ExoPlayer убирает «вперёд» на последнем треке, и тогда сессия отбросила бы команду, а в конце волны она значит «догрузить» (WAVE-09), а в конце конечной очереди — «стоп» (TR-04).

**Traps:**
- `MediaSession` берёт набор команд и из `getAvailableCommands`, и из события `onAvailableCommandsChanged`; поэтому листенеры оборачиваются (`TransportCommandsListener`), и событие несёт тот же расширенный набор.
- `TransportCommandsListener` пересылает каждый из 37 методов `Player.Listener` явно, включая устаревшие (`@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")`: их пересылает и `ForwardingListener` из Media3). Делегирование Kotlin `Player.Listener by listener` не годится: для default-методов Java-интерфейса Kotlin пересылку не генерирует, а в `Player.Listener` все методы такие. Так было в v0.2.0: сессия получала только смену команд, и контроллеры (экран плеера, уведомление Media3, экран блокировки) навсегда оставались со снимком состояния на момент подключения — не шёл прогресс и спектр, не появлялось уведомление, сервис не уходил в foreground и умирал при выходе из приложения. `playback/QueueForwardingPlayerTest` сверяет доставку каждого события с обычным `ForwardingPlayer`; новый метод слушателя в Media3 уронит этот тест, пока его не перешлют.
- `Media3Engine` управляет самим ExoPlayer, а не `QueueForwardingPlayer`: иначе `QueueController.next()` → `skipToNext()` вернулся бы в `next()`, а `applyModes()` — в `setShuffle()`.

## `Media3Engine`

```kotlin
@OptIn(UnstableApi::class)
class Media3Engine(player: ExoPlayer, controller: QueueController, random: Random = Random.Default) : PlayerEngine {
    fun attach()     // addListener + HeadShuffleOrder на пустой плеер + controller.attach(this)
    fun detach()
}
```

Тонкий: ни одного решения, только перевод. Команды `PlayerEngine` → вызовы `ExoPlayer` (`setTracks` = заранее поставленный проход (см. `HeadShuffleOrder`) + `setMediaItems(…, startIndex, startPositionMs)` + `prepare()` + `playWhenReady`; `shuffleHead(headSize, first)` = `setShuffleOrder(HeadShuffleOrder(HeadShuffle.pass(headSize, mediaItemCount, first)))`; `appendTracks` = `addMediaItems(mediaItemCount, …)`; `insertTracks(i, …)` = `addMediaItems(i, …)`, текущий трек и буфер не трогаются; `removeAt` = `removeMediaItem`; `clear` = `clearMediaItems`; `seekTo(i)` = `seekTo(i, 0)`; `seekToPosition` = `seekTo(ms)`; `skipToNext`/`skipToPrevious` = `seekToNext/PreviousMediaItem`; `pauseAtEndOfCurrent` = `pauseAtEndOfMediaItems`). Движок держит копию порядка ExoPlayer (`order: HeadShuffle`) и меняет её до каждого вызова плеера так же, как ExoPlayer меняет свой: `HeadShuffleOrder` клонируется детерминированно. Вставка в пустой плеер ставит свой порядок заранее, как `setTracks`, иначе ExoPlayer сделал бы случайный проход, которого копия не знает. События `Player.Listener` → методы контроллера:

| Событие ExoPlayer | Вызов |
|---|---|
| `onMediaItemTransition(item, reason)` | `onItemChanged(MediaItems.toTrack(item), transition, player.isPlaying)`; причина: `AUTO` → `AUTO`, `REPEAT` → `REPEAT`, `SEEK` → `SEEK`, остальное (`PLAYLIST_CHANGED`) → `NEW_QUEUE` |
| `onPlaybackStateChanged(STATE_ENDED)` | `onEnded()` |
| `onPositionDiscontinuity(old, new, reason)` | `onSeeked()`, только при `DISCONTINUITY_REASON_SEEK` и одном `mediaItemIndex` у `old` и `new`; перемотка на другой элемент приходит как `onMediaItemTransition(SEEK)`. `seekToPrevious/NextMediaItem` на тот же элемент (повтор текущего, `repeatCurrentMediaItem` в Media3) даёт и `onSeeked()`, и сразу за ним `onMediaItemTransition(SEEK)` — это безвредно (вне джема лишнее сохранение, в джеме повтор выключен), так что фильтр «тот же элемент» остаётся как есть |
| `onIsPlayingChanged(playing)` | `onPlayingChanged(playing)` |
| `onPlayWhenReadyChanged(false, PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM)` | `onPausedAtEnd()`: ExoPlayer встал на паузу в конце трека, потому что включён `pauseAtEndOfMediaItems` (заворот головы, CONT-10) |
| `onPlayerError(error)` | `Log.w("QiYaa", …, error)`, затем `onFailure(PlaybackFailures.classify(error.errorCode, error.cause), PlaybackFailures.errorKind(error.errorCode, error.cause))` |

`onShuffleModeEnabledChanged` и `onRepeatModeChanged` не пересылаются: режимы ExoPlayer пишет только контроллер (`shuffleEnabled` = `shuffleModeEnabled`, `repeatEnabled = true` — это `REPEAT_MODE_ALL`: повтора одного трека нет, TR-06), а выбор пользователя приходит командами через `QueueForwardingPlayer`.

`playOrder()` обходит `currentTimeline` через `getFirstWindowIndex`/`getNextWindowIndex(…, REPEAT_MODE_OFF, shuffle)`.

**Traps:**
- Все вызовы — с главного потока ExoPlayer; листенеры ExoPlayer срабатывают после завершения команды, так что контроллер видит уже обновлённое состояние.
- Пауза в конце трека (Media3 1.4.1, сверено по байткоду AAR через `javap`, на устройстве не проверялось): когда играющий период дорендерен и стоит `pendingPauseAtEndOfPeriod`, `doSomeWork` делает `setPlayWhenReadyInternal(false, …, END_OF_MEDIA_ITEM)`, а в `STATE_ENDED` уходит только последний период; следующий период рендерерам не отдаётся. Флаг ставится, когда последний трек головы становится текущим; если чтение к этому моменту уже ушло в следующий период, цена — один `seekToCurrentPosition` (щелчок, не чужой трек). ExoPlayer при этом всё равно подгружает первый трек продолжения (`download-info` и байты) на каждом круге головы. Пока длится пауза, `playWhenReady = false`, и уведомление Media3 на миг считает воспроизведение остановленным; держится ли сервис на переднем плане в фоне с выключенным экраном — проверка на телефоне.
- `ShuffleOrder`, `setShuffleOrder`, `pauseAtEndOfMediaItems` в Media3 1.4.1 — `@UnstableApi`: `HeadShuffleOrder` помечен `@UnstableApi`, `Media3Engine` — `@OptIn(UnstableApi::class)` (`androidx.annotation.OptIn`), иначе lint с `warningsAsErrors` падает.
- `onSeeked` опирается на код Media3 1.4.1 (сверено по байткоду AAR через `javap`, на устройстве не проверялось). `seekTo` заканчивается `updatePlaybackInfo(…, DISCONTINUITY_REASON_SEEK)` и `flushEvents()`, поэтому разрыв доходит до листенера внутри самого вызова, уже с новой позицией и без `STATE_ENDED` (перемотка из `READY` или `ENDED` сначала показывает `BUFFERING`); исключение — перемотка из колбэка ExoPlayer, тогда разрыв приходит сразу после него. `QueueController` перематывает только из команд сессии, UI и сокета джема, поэтому `support/FakeEngine.seekToPosition` зовёт `onSeeked()` синхронно. `setMediaItems` даёт разрыв с причиной `REMOVE`, повтор после ошибки (`prepare()`) разрыва не даёт, так что `onSeeked` приходит только на перемотку внутри текущего трека: ползунок экрана и уведомления, «назад», перемотка в 0 у «стоп», а также любой `seekTo` на текущий элемент (тап по текущей строке плейлиста, «плей» в конце очереди из одного трека, в том числе из уведомления, `ensureJamWave` с перемоткой в 0 пустого плеера). Все они безвредны: отчёт джему и сохранение позиции.

## `HeadShuffleOrder`

```kotlin
@UnstableApi
class HeadShuffleOrder(val shuffle: HeadShuffle, random: Random, val staged: HeadShuffle? = null) : ShuffleOrder {
    override fun getLength();  getNextIndex(i);  getPreviousIndex(i);  getFirstIndex();  getLastIndex()   // по shuffle.order; C.INDEX_UNSET на концах и в пустом
    override fun cloneAndInsert(at, count);  cloneAndRemove(from, until);  cloneAndClear()               // HeadShuffle.inserted / removed / EMPTY
    companion object { fun staging(installed: HeadShuffle, itemCount: Int, pass: HeadShuffle, random: Random): HeadShuffleOrder }
}
```

Порядок ExoPlayer для контракта `queue.PlayerEngine`: проход головы, затем остальные индексы по возрастанию (`queue/README.md`, «`PlayerEngine`»). Поиск следующего и предыдущего — O(1) по заранее посчитанным массивам. Клоны детерминированы: вставка в непустой порядок не делает треки головой, удаление сохраняет порядок остальных. Вставка в пустой порядок делает из вставленных треков проход: поставленный заранее (`staged`), если его длина равна числу вставленных, иначе случайный.

**Заранее поставленный проход** (`staged`). `setMediaItems` в `ExoPlayerImpl` (Media3 1.4.1, сверено по байткоду через `javap`) клонирует порядок ровно два раза: `cloneAndRemove(0, size)`, если плейлист не пуст, и `cloneAndInsert(0, n)`; оба — до того, как листенеры получат `onMediaItemTransition`. Поэтому `Media3Engine.setTracks` сначала ставит `staging(…)`: текущий порядок той же длины, что у плеера, с проходом новой очереди в `staged`; удаление всего сохраняет `staged`, вставка `n` треков возвращает именно этот проход. Контроллер, которому ExoPlayer синхронно сообщает смену трека изнутри `setMediaItems`, уже видит настоящий порядок, а не случайный промежуточный (`onItemChanged` → `playOrder()`). Отдельного `setShuffleOrder` после `setMediaItems` нет.

**Traps:**
- `ExoPlayerImpl.setShuffleOrder` требует `getLength() == mediaItemCount` (`checkArgument`). Порядок, который ставится перед `setMediaItems`, берёт длину у плеера, а не у копии в `Media3Engine`: сессия разрешает контроллерам все команды плеера, и `removeMediaItem` извне меняет плейлист мимо копии. При расхождении `staging` ставит порядок по возрастанию длины плеера.
- Поставленный проход используется один раз: порядок, который вернул `cloneAndInsert`, его уже не несёт, и любой клон, кроме удаления всего, его отбрасывает. Иначе следующие «очистить и вставить столько же» (например, `setMediaItems` сессии при возобновлении) получили бы старый проход.
- `cloneAndClear` даёт пустой порядок без `staged`. `MediaSourceList.setShuffleOrder` зовёт его только при несовпадении длины, а его исключает проверка выше.

## Классификация ошибок

`PlaybackFailures.classify` определяет вид по коду `PlaybackException` и по цепочке `cause`, никогда по тексту. Проверки идут сверху вниз:

| Вид | Признак |
|---|---|
| `SESSION` | в цепочке `HttpException` с `isTokenRejected` |
| `NETWORK` | код 2001/2002 (`IO_NETWORK_CONNECTION_*`) или в цепочке `NetworkException`, `UnknownHost`, `Connect`, `SocketTimeout`, `NoRouteToHost` |
| `TRACK` | всё остальное: 2004 (статус хранилища), `HttpException` 4xx/5xx от API, `MalformedResponseException`, парсинг 3xxx, декодер 4xxx |

Что делать с каждым видом, решает `queue/ErrorPolicy` (см. `queue/README.md`).

`PlaybackFailures.errorKind` говорит, что показать: `SESSION` → `TokenRejected`, `NETWORK` → `NoNetwork`, `TRACK` → `ServerError(status)`, если в цепочке есть `HttpException` или `HttpDataSource.InvalidResponseCodeException` хранилища, иначе `TrackUnplayable` (нет вариантов, декодер, парсинг). Подробности ошибки — только в logcat.

**Traps:**
- ExoPlayer сам повторяет сетевые ошибки загрузчика (около 3 попыток с паузой до 5 с) до `onPlayerError`, поэтому пауза наступает через несколько секунд после пропажи сети, а не сразу. Пока в буфере есть звук, трек доигрывает.
- Ошибки `TrackResolver` приходят с общим кодом 2000 (`IO_UNSPECIFIED`): отличить сеть от битого трека можно только по причине, поэтому `yandex` бросает типизированные исключения.

## `MediaItems`, `TrackResolver`, кэш ссылок

URI трека виртуальный: `qiyaa://track/{id}` (`MediaItems.SCHEME`). `mediaId = track.id`. В `MediaMetadata.extras` (`Bundle`) лежат `id`, `albumId`, `durationMs` (long), `artists` (string array list), `cover` (nullable string), `title`, чтобы `toTrack` восстанавливал `Track` из уведомления и после пересоздания процесса без сети. `artworkUri` = `coverUrl`.

`TrackResolver.resolveDataSpec` вызывается на loading-потоке ExoPlayer при каждом открытии источника: старт, перемотка за скачанное, повтор после обрыва, предзагрузка следующего трека. `runBlocking { links.get(id, continuing = dataSpec.position > 0) }` там допустим: открытие с середины файла (перемотка за скачанное, повтор после обрыва) продолжает тот же файл. URI с другой схемой проходит без изменений.

```kotlin
class TrackUrlCache(
    clock: () -> Long,
    quality: () -> Quality = { Quality.STANDARD },   // качество, действующее сейчас
    ttlMs: Long = TTL_MS,
    capacity: Int = CAPACITY,
    resolve: suspend (String, Quality) -> ResolvedUrl,
) {
    suspend fun get(trackId: String, continuing: Boolean = false): ResolvedUrl
    fun peek(trackId: String): String?              // свежая ссылка из кэша без подписи; для слушателей джема
    fun invalidate(trackId: String)                 // отказ хранилища: следующий get подпишет заново, в том же качестве
    companion object { TTL_MS = 15 * 60 * 1_000; CAPACITY = 64 }
}
```

**Качество** (`spec/player/quality.md`). Запись помнит качество, в котором ссылка подписана. `get(id)` — новый старт файла: берётся `quality()` и запись того же качества, пока она свежая; запись другого качества подписывается заново (QUAL-01, QUAL-02). `get(id, continuing = true)` — чтение с середины: берётся качество записи, даже если выбор уже другой, потому что файл другого кодека не продолжит начатый (QUAL-08); устаревшая запись подписывается заново в том же качестве. `invalidate` не удаляет запись, а делает её устаревшей, чтобы повторное открытие после 403/410 осталось в своём качестве. `peek` отдаёт ссылку любого качества: это файл, который играет. `AppGraph` передаёт `quality = qualityInEffect()`: `Settings.qualityMetered` на лимитной сети (`NetworkMonitor.isMetered()`: мобильная или лимитный Wi-Fi), иначе `Settings.quality` (QUAL-07).

Кэш один на процесс — `AppGraph.trackLinks`: его берут и сервис, и хозяин джема. `resolve` — `YandexApi.resolveTrackUrl(id, quality)`: в стандартном качестве два запроса, `download-info` к API и `download-info` хранилища; в экономии `get-file-info` (см. `yandex/README.md`). Часы — `SystemClock.elapsedRealtime`. Каждая настоящая подпись пишет в logcat `Signing the link of track <id>` (тег `QiYaa`, уровень debug): по ней видно, что перемотка и повторное открытие обходятся без API. Без кэша эти два запроса шли на каждое открытие; с кэшем перемотка и предзагрузка того же трека обходятся без API. Срок жизни подписанной ссылки Яндекс не документирует; проверено 2026-10-02: ссылка живёт час (параметр `ts` — время истечения в микросекундах), потом хранилище отвечает 401. 15 минут выбраны, чтобы перемотка в любое место почти любого трека шла по той же ссылке. Если ссылка истекла раньше, хранилище отвечает 403 или 410, и `ExpiredLinkDataSource` сбрасывает запись, заново открывает источник, а `TrackResolver` подписывает новую ссылку: цена слишком длинного срока — один лишний запрос. `CAPACITY` ограничивает память в длинной волне; вытесняется давно не использованная ссылка. Упавшая подпись не кэшируется. Доступ к кэшу синхронизирован: предзагрузка и текущий трек открываются с разных потоков; два одновременных промаха по одному треку подпишут ссылку дважды, это допустимо.

`ExpiredLinkDataSource` оборачивает `ResolvingDataSource` и повторяет открытие один раз, только для `HttpDataSource.InvalidResponseCodeException` 403/410 у `qiyaa://`-трека; второй отказ уходит в ExoPlayer как обычно (трековая ошибка, `queue/ErrorPolicy`). Все методы `DataSource` пересылаются явно: `getResponseHeaders` — default-метод Java, и делегирование Kotlin `by` его бы не переслало (см. ловушку `TransportCommandsListener`).

**Битрейт.** В экономии показание — `64K`. `TrackResolver` сообщает битрейт выбранного варианта в `CurrentBitrate.onResolved(id, kbps)`, а сервис — смену текущего трека (`onMediaItemTransition` → `currentTrackId` → `onCurrentChanged`). В `AudioBus.bitrateKbps` попадает битрейт текущего трека: предзагрузка следующего за ~50 с до конца его не меняет, показание меняется ровно на переходе; пока ссылка текущего трека не подписана — 0 (`--K`). Битрейты помнятся для последних 64 треков. Исключения `yandex` — `IOException`, поэтому ExoPlayer превращает их в `PlaybackException`, а не падает (см. `yandex/README.md`).

**Traps (качество):**
- Смена качества или сети не трогает следующий трек, который ExoPlayer уже начал грузить: он доиграет в старом качестве (QUAL-05, пробел A2 в `spec/player/README.md`). Заменить его `MediaItem` значило бы удалить и вставить элемент заново; с `HeadShuffleOrder` вставленный элемент выпадает из головы, но прежний порядок можно вернуть: копия `Media3Engine.order` до правки и `setShuffleOrder(HeadShuffleOrder(порядок до правки))` (зацепка для QiYaa-android#76). Треки после него берут новый выбор.
- «С середины» определяется по `dataSpec.position > 0`. Открытие с позиции 0 считается новым стартом и берёт действующее качество. Если ExoPlayer перечитает с нуля уже начатый mp3 без тега ID3 после смены качества, он получит файл другого кодека, и трек кончится ошибкой (ERR-04). У файлов Яндекса ID3 есть, а первый сэмпл MP4 лежит после `moov`, так что на практике чтение с нуля не происходит.
- `NetworkMonitor.isMetered()` читается в момент подписи на loading-потоке ExoPlayer: `ConnectivityManager.isActiveNetworkMetered` это допускает.

## `TimedAudioSink`

`buildAudioSink` оборачивает `DefaultAudioSink` в `TimedAudioSink(sink, audioBus.visualizerTap)`. `handleBuffer(buffer, presentationTimeUs, …)` сначала сообщает кольцу `announceInput(presentationTimeUs)`: процессоры вызываются внутри этого же `handleBuffer`, и отвод пишет кадры буфера с его временем. `getCurrentPositionUs`, который рендерер ExoPlayer спрашивает, пока играет, отдаёт звучащую позицию в тех же единицах и уже с учётом задержки `AudioTrack` и Bluetooth; `TimedAudioSink` передаёт её в `reportPlaying` с `System.nanoTime()`. Раз в 5 с пишет в logcat `Visualizer: the newest written audio sounds in N ms`.

## Процессоры

Оба — `BaseAudioProcessor` и работают только с `ENCODING_PCM_16BIT`. На другой формат (float, 24 бит) `onConfigure` возвращает `AudioFormat.NOT_SET`: процессор выключается, и `DefaultAudioSink` его пропускает, а звук идёт без EQ и визуализации, но играет. Раньше они бросали `UnhandledAudioFormatException`, и падало всё воспроизведение. `enableFloatOutput = false` держит декодеры в PCM16, так что это страховка. `EqualizerProcessor.onConfigure` сообщает формат в `AudioBus.setFormat`. `queueInput`: пустой буфер возвращается сразу — `replaceOutputBuffer(0)` в Media3 1.4 отдаёт общий `EMPTY_BUFFER`, и `put(inputBuffer)` на нём бросает «The source buffer is this buffer». `onFlush`/`onReset` сбрасывают состояние EQ и очищают кольцо.

## `JamHost`

```kotlin
class JamHost(transport: JamTransport, connectivity: Flow<Boolean>, queue: QueueController, catalog: JamCatalog, store: JamSessionStore,
              config: () -> JamHostConfig, queueTitle: () -> String, scope: CoroutineScope, io: CoroutineContext, appVersion: String,
              newId: () -> String = UUID, clock: () -> Long = System::currentTimeMillis, log: (String, Throwable?) -> Unit = …) : JamHandler, JamPlaybackListener {
    val state: StateFlow<JamHostState>      // phase NONE/CREATING/ACTIVE, connection, connected, room, joinUrl, storedSession
    val events: SharedFlow<JamHostEvent>    // Refused(reason), Ended(BY_HOST | BY_SERVER | EXPIRED | GONE)
    fun create(hostName: String, settings: JamSettingsPatch? = null): Boolean;  fun cancelCreate()
    fun continueStored();  fun discardStored()          // «Продолжить джем?» (HOST-23)
    fun end()
    fun add(track: Track): Boolean;  fun playNext(track: Track): Boolean
    fun pin(itemId);  fun remove(itemId);  fun kick(publicId);  fun changeSettings(patch);  fun rotateLink()   // Boolean: false без связи
    fun onServiceStarted();  fun onServiceStopped()
}
interface JamCatalog { suspend fun searchTracks(text: String): List<Track>; suspend fun tracks(ids: List<String>): List<Track> }
data class JamHostConfig(serverUrl: String, waveFeedback: Boolean)
```

Сценарии — `spec/jam/host.md`. `AppGraph` создаёт один `JamHost` на процесс (`scope` — `Dispatchers.Main.immediate`, `io` — `Dispatchers.IO`, `catalog` — `Library.searchTracks` и `tracksByIds`, хранилище — `data/JamFile`, настройки — `jam/server`, `jam/waveFeedback`). Ключа хозяина нет: комнату может создать любое приложение, сервер ограничивает их число на IP (ROOM-02).

- **Сессия.** `create` проверяет имя и настройки тем же `JamCodec`, что и входящие сообщения: сообщение, которое сервер отвергнет и закроет соединение, не уходит вовсе (иначе переподключение слало бы его снова). После `welcome` — `create`, а если сессия уже есть — `resume` со снимком и `outbox`. `created` сохраняет `roomId`, `hostSecret`, `joinUrl` и включает режим джема очереди; `resumed` очищает `outbox` и отправляет `playing` (HOST-26). **Связь** (`connected`) — это принятый `create` или `resume` на текущем соединении, а не просто `welcome`: до неё `started` идёт в `outbox`, а `playing` и действия хозяина не отправляются (HOST-25).
- **Хранение** (HOST-22). Сессия, последний `snapshot`, новая ссылка из `linkRotated` и каждое изменение `outbox` пишутся в `JamSessionStore` на `io` через `Channel.CONFLATED`.
- **«Продолжить джем?»** (HOST-23). Сохранённая сессия видна как `state.storedSession`; вопрос задаёт UI. «Да» — режим джема очереди сразу, потом `resume`. «Нет» — хранилище очищается сразу, jam-слоты очереди снимаются, потом `resume` и `end` только чтобы гости увидели конец.
- **Отказы.** `resume` с `room-not-found` или `bad-secret` заканчивает джем здесь (HOST-24, `Ended(GONE)`); с другой причиной — `Refused` и новая попытка через 30 с (`RESUME_RETRY_MS`). Отказ `create` (например, `rate-limited`: с этого IP уже 2 живые комнаты) — `Refused` и никакой сессии. Отказ действия — `Refused(reason)`.
- **`state`**: версия не больше последней на этом соединении пропускается, первая после переподключения берётся как есть. Очередь уходит в `QueueController.onJamQueue` с сидами и `seedsVersion`.
- **Гости** (HOST-28…31). `searchRequest` — `Library.searchTracks` (`type=track`), доступные, до 20, `JamTracks.toJam`; ошибка — `failed`, 401/403 — `unauthorized` (сам вход покажет `yandex/Session`). `validateRequest` — `POST /tracks`: по ответу на каждый id по порядку, недоступный или пропавший — `track-unavailable`, упавший запрос — `failed` для всех. Оба запроса идут отдельными корутинами на `io` и не ждут плеер.
- **`command{skip}`** — `QueueController.jamSkip` (HOST-18, HOST-19).
- **Добавить** (HOST-20). `add` шлёт `add{track}`; трек попадает в хвост только со следующим `state`. `playNext` — `pin` для уже ждущего трека, иначе `add` и `pin`, когда элемент с этим треком от хозяина появится в `state`.
- **Конец** (HOST-32). `end()` отправляет `end`, если есть связь, и в любом случае заканчивает локально: `QueueController.endJam`, хранилище очищается, соединение закрывается. `ended` от сервера — то же без `end`.
- **Слушать вместе** (`spec/jam/listen.md`, LISTEN-01–05, LISTEN-13). С `JamHostConfig.shareAudio` (`Settings.jamShareAudio`, по умолчанию выключено) `playing` трека джема и трека волны несёт `listenUrl` — ссылку на файл, который играет, и `listenNextUrl` — на следующий (`JamPlayback.nextTrack`). Ссылки берутся из `TrackUrlCache.peek` — уже подписанные, без новых запросов к Яндексу; кэш теперь общий (`AppGraph.trackLinks`) для плеера и хозяина джема. Ссылка не из хранилища Яндекса (`JamCodec.isListenUrl`) не уходит: сервер отказал бы во всём `playing`. Поэтому файл экономии (`strm*.strm.yandex.net`, не `/get-mp3/`) слушателям не передаётся (LISTEN-13).
- **Соединение живёт с `PlaybackService`**: `onCreate` сервиса зовёт `onServiceStarted` (идёт джем — `resume`), `onDestroy` — `onServiceStopped` (соединение закрывается, джем остаётся в памяти и на диске, комната ждёт хозяина час, REC-05). На переднем плане ради джема сервис не держим.

## `JamTracks`

`toJam(track)` — `null`, если трек не проходит протокол (id не `^[0-9A-Za-z_-]{1,64}$`, пустое название); управляющие символы → пробелы, название ≤ 150, исполнители ≤ 10 по ≤ 64 символа, длительность 0…24 ч. `coverUri` восстанавливается из `coverUrl`: без схемы, `400x400` в конце → `%%`; адрес без размера — без обложки. `fromJam` — обратно через `TrackParsing.coverUrl`.

## Not here

- Что играть дальше, «вперёд»/«назад», источники, волна, режим джема, политика ошибок, shuffle и повтор — `queue/`. Протокол, соединение и хранение сессии джема — `jam/`. Тексты тостов — `ui/QueueEventText.kt` и `res/values*/strings.xml`. Подпись ссылки — `yandex/TrackUrl`. `MediaController` со стороны UI — `ui/PlayerViewModel`.

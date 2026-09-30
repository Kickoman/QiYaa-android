# `queue/` — очередь

Что играть: источники (лайки, плейлист, альбом, артист, станция, волна, поиск), режим джема, тикеты «побеждает последний запрос», поколения очереди, догрузка волны, выделение и правка очереди, лайки и дизлайк, отметка `/play-audio`, политика ошибок воспроизведения и правило shuffle. Порт очереди из `src/core/player.cpp` десктопной версии; поведение описано сценариями `spec/player/` (ID вида `SRC-06`, `WAVE-05`, `ERR-04`), режим джема — `spec/jam/host.md` (`HOST-01`…).

Пакет **не** знает про ExoPlayer: плеер виден ему как интерфейс `PlayerEngine`, а адаптер к Media3 — `playback/Media3Engine`. Он **не** ходит в сеть сам: запросы идут через `MusicSource` (в приложении — `LibraryMusicSource` над `yandex/Library`). Он **не** показывает текст: всё для пользователя уходит `QueueEvent`, а строку собирает `ui/QueueEventText.kt`.

Чистая JVM, импортирует только `yandex`:

```bash
grep -rlnE '^import (android|androidx)' app/src/main/java/io/github/kickoman/qiyaa/queue/   # ничего не печатает
grep -rln 'qiyaa\.\(playback\|ui\|data\)\.' app/src/main/java/io/github/kickoman/qiyaa/queue/   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `QueueController.kt` | `QueueState`, `QueueController` — вся логика очереди |
| `PlayerEngine.kt` | `PlayerEngine` — что очереди нужно от плеера |
| `MusicSource.kt` | `MusicSource` — какие запросы к библиотеке делает очередь |
| `LibraryMusicSource.kt` | `LibraryMusicSource` — `MusicSource` поверх `yandex/Library` |
| `QueueEvent.kt` | `QueueEvent` — что очередь сообщает наружу |
| `ErrorPolicy.kt` | `ErrorAction`, `ErrorPolicy` — что делать с ошибкой; ожидание сети перед повтором |
| `FailureKind.kt` | `FailureKind` — `NETWORK`, `SESSION`, `TRACK` (определяет `playback/PlaybackFailures`) |
| `WaveModeRule.kt` | `WaveModeRule` — режим пользователя (shuffle, повтор), который в волне выключен и потом возвращается |
| `PlayTracker.kt` | `PlayTracker` — когда трек начался и закрылся (дослушан или пропущен) и сколько секунд реально играл |
| `Transition.kt` | `Transition` — причина смены трека: `AUTO`, `REPEAT`, `SEEK`, `NEW_QUEUE` |
| `PlayOrder.kt` | `PlayOrder.remainingAfter` — сколько треков после текущего в порядке воспроизведения |
| `QueueStore.kt` | `QueueStore` — куда сохранять очередь (в приложении — `data/QueueFile`) |
| `QueueSnapshot.kt` | `QueueSnapshot`, `QueueSnapshotCodec` — сохранённая очередь и её JSON |
| `JamPlayback.kt` | `JamSlot`, `JamEntry`, `JamPlayback`, `JamPlaybackListener`, `JamTail` — типы режима джема и правка хвоста по общему префиксу |

## `QueueController`

```kotlin
data class QueueState(tracks: List<Track>, title: String, isWave: Boolean, loadingMore: Boolean, selected: Set<Int>, activeSourceId: String?, jamSlots: List<JamSlot>? = null)

class QueueController(source: MusicSource, connectivity: Flow<Boolean>, scope: CoroutineScope, io: CoroutineContext, newPlayId: () -> String = UUID, clock: () -> Long = монотонные мс, store: QueueStore? = null, logFailure: (Stage, Throwable) -> Unit = {}) {
    val state: StateFlow<QueueState>;  val events: SharedFlow<QueueEvent>;  val engine: PlayerEngine?
    fun attach(engine: PlayerEngine);  fun detach(engine: PlayerEngine)
    fun resumePoint(): ResumePoint?   // треки, индекс и позиция для «играть» после выгрузки процесса; null — очередь пуста
    // команды из UI
    fun loadSource(title: String, sourceId: String? = null, autoplay: Boolean = true, loader: suspend () -> List<Track>)
    fun playWave(seeds: List<String>, title: String, sourceId: String = seeds.first())
    fun search(text: String, title: String)
    fun setQueue(tracks, title, isWave, autoplay, sourceId);  fun appendTracks(tracks);  fun clear()
    fun removeIndices(indices: Set<Int>);  fun toggleSelected(index: Int);  fun selectAllOrNone()
    fun next();  fun previous();  fun stop()   // транспорт: сюда приходят кнопки приложения, уведомление и гарнитура
    fun toggleLike(track: Track);  fun dislikeAndSkip(track: Track)
    fun currentWaveSeed(): String?   // станция (первый сид) играющей волны; null для обычной очереди
    fun likeCurrent();  fun dislikeCurrent()   // то же для текущего трека плеера (кнопки уведомления); без движка или трека — ничего
    fun requestMore()   // «вперёд» в конце волны: снова запросить догрузку и продолжить с первого нового трека
    // события движка
    fun onItemChanged(track: Track?, transition: Transition, isPlaying: Boolean);  fun onEnded();  fun onShuffleChanged(enabled: Boolean);  fun onRepeatChanged(enabled: Boolean)
    fun onPlayingChanged(isPlaying: Boolean);  fun onFailure(kind: FailureKind, error: ErrorKind)
    // режим джема (см. ниже)
    val isJamActive: Boolean
    fun startJam(title: String, listener: JamPlaybackListener, waveFeedback: Boolean)
    fun onJamQueue(entries: List<JamEntry>, seeds: List<String>, seedsVersion: Int)   // из каждого state сервера
    fun jamSkip(itemId: String)   // command{skip}
    fun endJam()
    companion object { MY_WAVE_SEED = "user:onyourwave"; LOAD_MORE_WHEN_LEFT = 2; WAVE_HISTORY = 5; RESTART_AFTER_MS = 3_000; PLAYING_REPORT_MS = 10_000; JAM_WAVE_AHEAD = 2 }
}
```

В приложении `AppGraph` создаёт контроллер со `scope` на `Dispatchers.Main.immediate` и `io = Dispatchers.IO`; все методы вызываются с главного потока, запросы `MusicSource` и загрузчики — на `io`. В тестах оба — диспетчер `runTest`, время виртуальное.

Гарантии (сценарии `spec/player/`):
- **Побеждает последний источник** (SRC-01…03). Каждый `loadSource`/`playWave`/`search` берёт билет; ответ или ошибка устаревшего билета отбрасываются молча. Поиск с лучшим артистом или альбомом — один источник, как бы много запросов он ни делал.
- **Недоступные треки** (`available == false`) не попадают в очередь (SRC-05). Источник, в котором нет ни одного доступного трека, оставляет очередь и воспроизведение как есть и сообщает `SourceEmpty` (SRC-07, SRC-08); поиск — `NothingFound` (SRC-12); первая порция волны — `SourceEmpty` без сессии и догрузки (WAVE-03). Проверка на пустоту идёт после фильтра доступности.
- **Волна** стартует с `SourceLoading` сразу и `WaveStarted` после ответа (WAVE-01). Догружается, когда текущий трек — один из двух последних в порядке воспроизведения (`LOAD_MORE_WHEN_LEFT = 2` считает текущий вместе с оставшимися), с последними 5 id очереди, не больше одного запроса за раз (WAVE-05, WAVE-06). Поколение очереди не даёт дописать ответ к уже заменённой очереди, а её ошибку показать (WAVE-07). Если плеер дошёл до конца во время догрузки, продолжает с первого нового трека (WAVE-08). Неудачная догрузка в конце волны сама не повторяется; `requestMore()` («вперёд») отправляет запрос снова, и после ответа воспроизведение продолжается с первого нового трека (WAVE-09). Ответ, где нет ни одного доступного трека, ничего не меняет.
- **Shuffle и повтор в волне не действуют** (WAVE-10…12). Два экземпляра `WaveModeRule`: `setQueue` ставит движку `playerModeFor(isWave)` для каждого режима, а `onShuffleChanged`/`onRepeatChanged` возвращают выключенный режим, если его включили во время волны с любого контроллера. Выбор пользователя запоминается только вне волны и возвращается для обычных очередей. Так последний трек волны не переходит к первому, а ждёт догрузку (WAVE-08).
- **Ошибки воспроизведения** (ERR-01…07) — по `ErrorPolicy`, см. ниже.
- **«Вперёд»** (`next()`): следующий трек в порядке воспроизведения (TR-03), с повтором — первый после последнего (TR-05); в конце конечной очереди — пауза и начало текущего трека, курсор остаётся (TR-04); в конце волны — `LoadingMore` и `requestMore()` (WAVE-09).
- **«Назад»** (`previous()`): после 3 с (`RESTART_AFTER_MS`) — в начало текущего трека (TR-02), иначе предыдущий трек, на первом с повтором — последний, без повтора — в начало (TR-01).
- **Один хозяин плеера.** Движок может смениться (сервис пересоздан при живом процессе): `detach` запоминает трек и позицию, `attach` нового движка кладёт ту же очередь на паузе туда же, вместе с режимами shuffle и повтора. Очередь, выбранная без движка, применяется при `attach`; правка очереди без движка меняет только `state`. Подробнее — `playback/README.md`.
- **Очередь переживает выгрузку процесса** (Kickoman/QiYaa-android#8). Контроллер пишет `QueueSnapshot` в `store` при каждой правке очереди, смене трека, паузе и `detach`; запись идёт на `io` через `Channel.CONFLATED`, так что частые изменения дают одну запись. Конструктор читает `store` и восстанавливает `state`, выбор shuffle и повтора и точку возврата: первый `attach` кладёт очередь на паузе на тот же трек и позицию. Треки восстановленной волны сохраняют свой `WaveContext`, поэтому их фидбек уходит в старую сессию и порцию. Для догрузки старая сессия не используется: первая догрузка после восстановления делает `startWave` по сохранённой станции, отправляет `radioStarted` новой сессии и дописывает её треки в конец.
- **Дизлайк** = запрос `dislike` + то же, что «вперёд» (TR-07, TRK-07): следующий трек, в конце конечной очереди — пауза и начало трека, в конце волны — догрузка.
- **Обратная связь волны** (TRK-03…08). Каждый трек волны помнит `WaveContext` — сессию, станцию (первый сид) и `batchId` порции, из которой пришёл, включая догруженные. `radioStarted` уходит при старте волны до её первого `trackStarted`. `trackStarted` — когда трек начался; при закрытии — `trackFinished` (автопереход, повтор, конец очереди) или `skip` (всё остальное: «вперёд», «назад», выбор трека, рестарт, «стоп», новая очередь, удаление текущего трека, ошибка трека, дизлайк, отключение движка) с секундами, которые звук реально играл. События трека уходят в его сессию, даже если очередь уже сменилась на другую волну. Обычные очереди фидбек не шлют. Все события отправляются по одному через `Channel`, поэтому `skip` уходит раньше следующего `trackStarted`.
- **Отметка `/play-audio`** (TRK-01, TRK-02) уходит, когда трек **начался**: стал текущим и после этого заиграл — сразу, если при смене трека звук уже шёл (`isPlaying` в `onItemChanged`, как при бесшовном переходе), иначе при первом `onPlayingChanged(true)`. Трек, который только стоит в очереди или у которого не получилась ссылка, не начинался. Новый старт с новым `play-id` — рестарт «назад» после 3 с, повтор очереди из одного трека, «плей» после «стоп» (`stop()`) или после конца трека. Пауза с продолжением и перемотка внутри трека — не новый старт. Отправка best-effort, ошибки игнорируются.

**Ошибки источников и лайков.** Упавший запрос даёт `QueueEvent.Failed(stage, error)`, где `error` — `yandex/ErrorKind.of(failed)`, а само исключение уходит в `logFailure(stage, failed)`; в приложении это `Log.w` из `AppGraph`. Текста исключения в событии нет. Ошибка воспроизведения приходит уже с видом: `onFailure(kind, error)` от `playback/PlaybackFailures`. Ошибка догрузки заменённой волны пишется в лог, но события не даёт (WAVE-07).

`events` — `MutableSharedFlow(extraBufferCapacity = 8)` с `tryEmit`: без подписчика события теряются, это нормально для тостов.

## Режим джема

Пока идёт джем, очередь плеера — это текущий трек, **часть джема** (треки гостей в порядке сервера) и за ней **волна джема**. Сеть джема очередь не знает: `jam/`-сессия (Kickoman/QiYaa-android#61) зовёт `startJam`, передаёт очередь сервера через `onJamQueue` и получает в ответ `JamPlaybackListener`. Каждому треку плеера соответствует `JamSlot` в `state.jamSlots`: `Item(itemId, addedBy)` — трек гостя, `Wave` — трек волны джема, `Other` — трек, который играл до джема.

- **Старт** (HOST-14). Текущий трек остаётся и играет дальше (сообщается как `wave`), остальная очередь удаляется. Контексты обычной волны сбрасываются, shuffle и повтор выключаются (как в волне), выбор пользователя запоминается. Если очередь восстановлена из файла вместе со слотами, она берётся как есть.
- **Часть джема** (HOST-01…04). `onJamQueue` сравнивает часть джема с очередью сервера: общий префикс остаётся на месте (`JamTail.edit`), остальное заменяется через `removeAt` и `insertTracks`, поэтому следующий трек и его буфер не трогаются, если он не поменялся. Текущий элемент в хвост не попадает, даже если `state` ещё его перечисляет. Волна джема стоит за частью джема.
- **Переходы** (HOST-05…08). Новый текущий элемент → `onItemStarted(itemId)`, затем `onPlayback`. `onPlayback` уходит также при паузе, продолжении, перемотке и каждые 10 с игры (`PLAYING_REPORT_MS`). Элемент, пришедший в остановленный или ждущий волну плеер, играет сразу. «Назад» в джеме всегда перезапускает текущий трек.
- **Волна джема** (HOST-10…13). Когда впереди нет элементов и меньше двух треков волны (`JAM_WAVE_AHEAD`), контроллер делает `startWave(seeds)` по сидам последнего `state` (без сидов — `track:<id>` текущего трека) или `moreWave` своей сессии с последними 5 треками волны. Сессия помнит `seedsVersion`; если в `state` пришла другая версия, то, когда закончились элементы, отложенные треки старой сессии удаляются и стартует новая. Элемент, пришедший во время трека волны, встаёт перед оставшимися треками волны. Ничего не играло и сидов нет — плеер стоит, `onPlayback(IDLE)`.
- **Ошибки** (HOST-09). Правила `ErrorPolicy` как в волне: сломанный последний трек ждёт волну джема и продолжает с её первого трека.
- **Без обучения** (HOST-15, HOST-16). Для треков джема нет `/play-audio` и фидбека в «Мою волну» и станции. Фидбек ротора идёт только в сессию волны джема и только при `waveFeedback = true`.
- **Запреты** (HOST-21). `loadSource`, `playWave`, `search`, `setQueue`, `appendTracks`, `clear`, `removeIndices` ничего не делают и отдают `QueueEvent.JamActive`. Лайк и дизлайк работают (HOST-17).
- **Пропуск гостя** (HOST-18, HOST-19). `jamSkip(itemId)` = «вперёд», только если этот элемент — текущий.
- **Конец** (HOST-32, HOST-33). `endJam` оставляет элементы как обычные треки, удаляет треки волны после текущего, снимает `jamSlots`, возвращает shuffle, повтор и отметки `/play-audio`.

## Известные расхождения со спекой

Закреплены тестами «как есть» с пометкой гэпа в имени теста; исправления меняют эти тесты явно.

| Гэп | Сейчас | Сценарии | Задача |
|---|---|---|---|


## `ErrorPolicy`

```kotlin
sealed interface ErrorAction { WaitForNetwork; Hold; SkipToNext; WaitForMore; Stop }
object ErrorPolicy {
    const val MAX_CONSECUTIVE_TRACK_FAILURES = 3
    fun decide(kind: FailureKind, consecutiveTrackFailures: Int, hasNext: Boolean, isWave: Boolean = false): ErrorAction
    suspend fun awaitRetry(connectivity: Flow<Boolean>, attempt: Int)
    fun retryDelayMs(attempt: Int): Long         // 2, 4, 8 … 60 с, те же пределы, что у yandex/Session
}
```

| Вид | Действие контроллера |
|---|---|
| `SESSION` | `Hold`: остаться на месте, `Failed(PLAYBACK)`; `yandex/Session` уведёт на вход |
| `NETWORK` | `WaitForNetwork`: пауза на текущем треке, один раз `WaitingForNetwork`; когда `awaitRetry` вернулся — `engine.prepare()` |
| `TRACK` | 3-я неудача подряд — `Stop` и `StoppedAfterFailures(3)` (ERR-05, и в волне тоже); иначе есть следующий трек — `SkipToNext` с `Failed(PLAYBACK)` (ERR-04); нет следующего в волне — `WaitForMore`: `Failed(PLAYBACK)`, догрузка и продолжение с первого нового трека (ERR-07); нет следующего в конечной очереди — `Stop` |

`awaitRetry`: если сети нет, ждёт её и возвращается сразу, как только она появилась; если сеть есть, а загрузка всё равно падает, ждёт `retryDelayMs(attempt)`. Счётчики и ожидание сбрасываются, когда трек реально заиграл (`onPlayingChanged(true)`), и при `setQueue`/`clear`.

## Формат сохранённой очереди

`QueueSnapshotCodec.encode` — компактный JSON (`kotlinx.serialization`), `version` = 1:

```json
{"version":1,"title":"Моя волна","sourceId":"user:onyourwave","isWave":true,"waveSessionId":"S1","waveStationId":"user:onyourwave","index":1,"positionMs":42000,"shuffle":false,"repeat":true,
 "tracks":[{"id":"1","title":"Кукушка","artists":["Кино"],"albumId":"4053","durationMs":398000,"available":true,"coverUrl":"https://…","batchId":"B1"}]}
```

В джеме добавляются `"jam":true` и у треков `jamItemId`, `jamAddedBy` (элемент) или `"jamWave":true` (волна джема); без них трек — `Other`. Вне джема этих полей нет.

`shuffle` и `repeat` — выбор пользователя (`WaveModeRule.wanted`), а не режим плеера в волне. `sourceId`, `coverUrl` могут отсутствовать; `batchId`, `waveSessionId`, `waveStationId` пусты вне волны. `decode` зажимает `index` в границы треков, неизвестные поля пропускает, при другой `version` возвращает null.

## Тесты

`app/src/test/.../queue/`: `QueueSourcesTest`, `QueueWaveTest`, `QueueErrorsTest`, `QueueEditingTest`, `QueueJamTest` гоняют контроллер с `support/FakeEngine` (плейлист, курсор, конец, порядок shuffle, синхронные колбэки — как ExoPlayer, без повтора) и `support/FakeMusicSource` (ответы — лямбды, в том числе отложенные `CompletableDeferred`). Имена тестов начинаются с ID сценария.

**Traps:**
- Секунды прослушивания считаются по часам `clock` только между `onPlayingChanged(true)` и `(false)` открытого трека; перемотка времени не добавляет. В тестах часы — виртуальное время `runTest`.
- `title` — данные из UI или сервера (имя плейлиста, `getString(R.string.library_my_wave)`), не константа этого пакета.
- `state.tracks` и плейлист плеера пишет только контроллер, поэтому сверять их не нужно; если в плеер начнёт писать кто-то ещё (например, команда удаления из уведомления), это должно идти через контроллер.
- `removeIndices` удаляет по убыванию индексов, иначе сдвиг сломает выборку.
- `DefaultShuffleOrder` ExoPlayer вставляет дописанные треки в случайные места перемешанного порядка, поэтому в волне shuffle запрещён, а не «исправлен». Выбор shuffle и повтора хранится в `WaveModeRule` и в сохранённой очереди.
- `restore` идёт в блоке `init` после объявления всех полей: инициализатор поля, объявленного ниже `init`, затёр бы восстановленное значение.
- Файл без треков, с другой версией или битый читается как «сохранённой очереди нет»; пустая очередь после `clear` сохраняется и тоже ничего не восстанавливает.
- `QueueController` не должен импортировать `yandex/Library` напрямую: только `MusicSource`, иначе тесты потеряют фейк, а `yandex` — место внизу графа.

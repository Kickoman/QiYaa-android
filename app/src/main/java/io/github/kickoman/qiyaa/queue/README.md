# `queue/` — очередь

Что играть: источники (лайки, плейлист, альбом, артист, станция, волна, поиск), тикеты «побеждает последний запрос», поколения очереди, догрузка волны, выделение и правка очереди, лайки и дизлайк, отметка `/play-audio`, политика ошибок воспроизведения и правило shuffle. Порт очереди из `src/core/player.cpp` десктопной версии; поведение описано сценариями `spec/player/` (ID вида `SRC-06`, `WAVE-05`, `ERR-04`).

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
| `PlayOrder.kt` | `PlayOrder.remainingAfter` — сколько треков после текущего в порядке воспроизведения |

## `QueueController`

```kotlin
data class QueueState(tracks: List<Track>, title: String, isWave: Boolean, loadingMore: Boolean, selected: Set<Int>, activeSourceId: String?)

class QueueController(source: MusicSource, connectivity: Flow<Boolean>, scope: CoroutineScope, io: CoroutineContext, newPlayId: () -> String = UUID) {
    val state: StateFlow<QueueState>;  val events: SharedFlow<QueueEvent>;  val engine: PlayerEngine?
    fun attach(engine: PlayerEngine);  fun detach(engine: PlayerEngine)
    // команды из UI
    fun loadSource(title: String, sourceId: String? = null, autoplay: Boolean = true, loader: suspend () -> List<Track>)
    fun playWave(seeds: List<String>, title: String, sourceId: String = seeds.first())
    fun search(text: String, title: String)
    fun setQueue(tracks, title, isWave, autoplay, sourceId);  fun appendTracks(tracks);  fun clear()
    fun removeIndices(indices: Set<Int>);  fun toggleSelected(index: Int);  fun selectAllOrNone();  fun syncFromPlayer()
    fun toggleLike(track: Track);  fun dislikeAndSkip(track: Track)
    // события движка
    fun onItemChanged(track: Track?);  fun onEnded();  fun onShuffleChanged(enabled: Boolean);  fun onRepeatChanged(enabled: Boolean)
    fun onPlayingChanged(isPlaying: Boolean);  fun onFailure(kind: FailureKind, message: String)
    companion object { MY_WAVE_SEED = "user:onyourwave"; LOAD_MORE_WHEN_LEFT = 2; WAVE_HISTORY = 5 }
}
```

В приложении `AppGraph` создаёт контроллер со `scope` на `Dispatchers.Main.immediate` и `io = Dispatchers.IO`; все методы вызываются с главного потока, запросы `MusicSource` и загрузчики — на `io`. В тестах оба — диспетчер `runTest`, время виртуальное.

Гарантии (сценарии `spec/player/`):
- **Побеждает последний источник** (SRC-01…03). Каждый `loadSource`/`playWave`/`search` берёт билет; ответ или ошибка устаревшего билета отбрасываются молча. Поиск с лучшим артистом или альбомом — один источник, как бы много запросов он ни делал.
- **Недоступные треки** (`available == false`) не попадают в очередь (SRC-05). Источник, в котором нет ни одного доступного трека, оставляет очередь и воспроизведение как есть и сообщает `SourceEmpty` (SRC-07, SRC-08); поиск — `NothingFound` (SRC-12); первая порция волны — `SourceEmpty` без сессии и догрузки (WAVE-03). Проверка на пустоту идёт после фильтра доступности.
- **Волна** стартует с `SourceLoading` сразу и `WaveStarted` после ответа (WAVE-01). Догружается, когда текущий трек — один из двух последних в порядке воспроизведения (`LOAD_MORE_WHEN_LEFT = 2` считает текущий вместе с оставшимися), с последними 5 id очереди, не больше одного запроса за раз (WAVE-05, WAVE-06). Поколение очереди не даёт дописать ответ к уже заменённой очереди (WAVE-07). Если плеер дошёл до конца во время догрузки, продолжает с первого нового трека (WAVE-08).
- **Shuffle и повтор в волне не действуют** (WAVE-10…12). Два экземпляра `WaveModeRule`: `setQueue` ставит движку `playerModeFor(isWave)` для каждого режима, а `onShuffleChanged`/`onRepeatChanged` возвращают выключенный режим, если его включили во время волны с любого контроллера. Выбор пользователя запоминается только вне волны и возвращается для обычных очередей. Так последний трек волны не переходит к первому, а ждёт догрузку (WAVE-08).
- **Ошибки воспроизведения** (ERR-01…07) — по `ErrorPolicy`, см. ниже.
- **Дизлайк** = запрос `dislike` + переход к следующему; в конечной очереди без следующего — стоп, в волне — ничего, ждём догрузку (TR-07).
- **Отметка `/play-audio`** уходит на смену id текущего трека, best-effort, ошибки игнорируются.

`events` — `MutableSharedFlow(extraBufferCapacity = 8)` с `tryEmit`: без подписчика события теряются, это нормально для тостов.

## Известные расхождения со спекой

Закреплены тестами «как есть» с пометкой гэпа в имени теста; исправления меняют эти тесты явно.

| Гэп | Сейчас | Сценарии | Задача |
|---|---|---|---|
| A2 | `/play-audio` уходит на смену элемента, в том числе без автозапуска, и не повторяется для того же трека | TRK-01, TRK-02 | #27 |
| A4 | ошибка догрузки для заменённой очереди всё равно показывается; битый последний трек волны — стоп | WAVE-07, ERR-07 | #29 |

Транспорт «назад»/«вперёд» (TR-01…05, WAVE-09) пока живёт в `ui/PlayerViewModel` поверх `MediaController`; переедет в очередь с Kickoman/QiYaa-android#6.

## `ErrorPolicy`

```kotlin
sealed interface ErrorAction { WaitForNetwork; Hold; SkipToNext; Stop }
object ErrorPolicy {
    const val MAX_CONSECUTIVE_TRACK_FAILURES = 3
    fun decide(kind: FailureKind, consecutiveTrackFailures: Int, hasNext: Boolean): ErrorAction
    suspend fun awaitRetry(connectivity: Flow<Boolean>, attempt: Int)
    fun retryDelayMs(attempt: Int): Long         // 2, 4, 8 … 60 с, те же пределы, что у yandex/Session
}
```

| Вид | Действие контроллера |
|---|---|
| `SESSION` | `Hold`: остаться на месте, `Failed(PLAYBACK)`; `yandex/Session` уведёт на вход |
| `NETWORK` | `WaitForNetwork`: пауза на текущем треке, один раз `WaitingForNetwork`; когда `awaitRetry` вернулся — `engine.prepare()` |
| `TRACK` | 1-я и 2-я неудача подряд — `SkipToNext` с `Failed(PLAYBACK)`; 3-я или нет следующего трека — `Stop` и `StoppedAfterFailures(n)` |

`awaitRetry`: если сети нет, ждёт её и возвращается сразу, как только она появилась; если сеть есть, а загрузка всё равно падает, ждёт `retryDelayMs(attempt)`. Счётчики и ожидание сбрасываются, когда трек реально заиграл (`onPlayingChanged(true)`), и при `setQueue`/`clear`.

## Тесты

`app/src/test/.../queue/`: `QueueSourcesTest`, `QueueWaveTest`, `QueueErrorsTest`, `QueueEditingTest` гоняют контроллер с `support/FakeEngine` (плейлист, курсор, конец, порядок shuffle, синхронные колбэки — как ExoPlayer, без повтора) и `support/FakeMusicSource` (ответы — лямбды, в том числе отложенные `CompletableDeferred`). Имена тестов начинаются с ID сценария.

**Traps:**
- `title` — данные из UI или сервера (имя плейлиста, `getString(R.string.library_my_wave)`), не константа этого пакета.
- `syncFromPlayer` сверяет `state.tracks` с плеером только по числу элементов.
- `removeIndices` удаляет по убыванию индексов, иначе сдвиг сломает выборку.
- `DefaultShuffleOrder` ExoPlayer вставляет дописанные треки в случайные места перемешанного порядка, поэтому в волне shuffle запрещён, а не «исправлен». Выбор shuffle и повтора хранится только в памяти `WaveModeRule`.
- `QueueController` не должен импортировать `yandex/Library` напрямую: только `MusicSource`, иначе тесты потеряют фейк, а `yandex` — место внизу графа.

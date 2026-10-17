# `ui/` — Compose

Четыре вкладки (Player · Playlist · EQ · Library), экран входа, экраны джема, мини-плеер и тосты, нарисованные вручную по дизайн-системе «Winamp-inspired mobile music player»: без Material-компонентов, без ripple, IBM Plex Mono для показаний и IBM Plex Sans для контента. Пакет только читает `StateFlow`-ы и вызывает методы view model. Он **не** содержит доменной логики: очередь — `playback/`, сеть — `yandex/`, DSP — `audio/`.

```
ui/
  AppRoot.kt, MainActivity.kt          корень Compose, вкладки, мини-плеер, тост
  AppViewModel.kt, PlayerViewModel.kt, JamViewModel.kt  состояние экранов
  QueueEventText.kt, JamEventText.kt, Format.kt         рендер событий и чисел в строки
  components/   QiText, кнопки, чипы, слайдеры, иконки
  screens/      по одному файлу на экран
  theme/        цвета, шрифты, стили текста
  visualizer/   полоса спектра/осциллографа
```

Зависимости: `screens → components → theme`; `visualizer → components, theme, audio`. `theme` не зависит от остального `ui`.

| Файл | Содержит |
|---|---|
| `AppRoot.kt` | `AppRoot`, `MiniPlayer`, `TabBar` |
| `MainActivity.kt` | `MainActivity` — язык интерфейса (`applyOverrideConfiguration` в `attachBaseContext`), `setContent { AppRoot() }` и запрос `POST_NOTIFICATIONS` на Android 13+ |
| `AppViewModel.kt` | `Screen`, `LibrarySection`, `LoginStatus`, `LoginUi`, `LibraryUi`, `AppViewModel`, `PlaylistRef.sourceId` |
| `ListLoader.kt` | `ListState` (`Idle`, `Loading`, `Loaded(items)`, `Failed(error)`), `ListLoader` — загрузка одного списка библиотеки со своей ошибкой и повтором |
| `PlayerViewModel.kt` | `PlayerUi`, `PlayerViewModel`, `AccentTheme.labelId` |
| `JamViewModel.kt` | `JamUi`, `JamViewModel` — хозяин джема для экранов: `playback/JamHost`, поиск треков для джема, настройки `jam/*` |
| `JamEventText.kt` | `JamHostEvent.render(context)`, `refusedText(reason)` — отказы сервера и конец джема → строки |
| `QueueEventText.kt` | `QueueEvent.render(context)` — событие очереди → строка из ресурсов |
| `ErrorText.kt` | `ErrorKind.text()` — строка вида ошибки (`error_*`, у `ServerError` со статусом), `render(context)` и `@Composable render()`; `LOG_TAG = "QiYaa"` |
| `Format.kt` | `formatTime`, `positionAt` (доля полосы перемотки → мс), `timeLabel` (подпись времени плеера), `balanceLabel`, `formatReadout`, `formatDb` |
| `components/Components.kt` | `QiText`, `Modifier.tap`, `ScreenHeader`, `ActionButton`, `IconActionButton`, `IconWideButton` (широкая кнопка с иконкой и описанием для экранного диктора), `LikeButton`, `PillToggle`, `OnOffPills` (пара `PillToggle` «вкл / выкл»), `RoundButton`, `LedDot`, `Chip` |
| `components/Sliders.kt` | `HorizontalSlider` (0…1), `VerticalFader` (дБ) |
| `components/Icons.kt` | `IconPaths`, `PathIcon` — SVG-пути (viewBox 24): транспорт из макета; сердце, палец вниз и «открыть снаружи» для кнопок лайка, дизлайка и браузера, контуром (`strokeWidth = IconPaths.OUTLINE`), сердце лайкнутого трека — заливкой. Подписи у этих кнопок нет (по-белорусски и по-русски она не помещается), текст — их `contentDescription` |
| `components/QrCode.kt` | `QrCodeImage` — QR (qrcodegen, коррекция M) тёмным по белому с полями в 4 модуля |
| `screens/LoginScreen.kt` | `LoginScreen` |
| `screens/PlayerScreen.kt` | `PlayerScreen`, `marqueeText`, `queueTitle` |
| `screens/PlaylistScreen.kt` | `PlaylistScreen` |
| `screens/EqScreen.kt` | `EqScreen`, `PresetsSheet` |
| `screens/LibraryScreen.kt` | `LibraryScreen` |
| `screens/JamScreen.kt` | `JamScreen`, `JamSettingsScreen`, `JamContinueDialog` |
| `screens/LibrarySectionScreen.kt` | `LibrarySectionScreen` — «для вас» / станции / плейлисты / исполнители / альбомы чипами |
| `theme/Color.kt` | `QiColors`, `accentColors`, `LocalQiColors` |
| `theme/Theme.kt` | `Qi.colors`, `QiYaaTheme` |
| `theme/Type.kt` | `PlexMono`, `PlexSans`, `mono()`, `sans()`, `CaptionStyle`, `LabelStyle`, `ButtonStyle`, `ReadoutStyle`, `HintStyle` |
| `visualizer/VisualizerStrip.kt` | `VisualizerStrip` |

## View models

`AppViewModel` — навигация (`screen`, `section`), вход (`login`), списки библиотеки (`forYou`, `playlists`, `artists`, `albums`, `stations`, `wheel` — по `ListLoader` на каждый; `libraryUi` хранит только текст поиска и `wheelMatchesCurrent`), тост (`toast`, 1.8 с). При старте: есть токен → `Screen.PLAYER`, иначе `Screen.LOGIN` и `startDeviceLogin()`. Подключением управляет `yandex/Session` (`AppGraph.session`), view model только слушает `sessionState`: `Online` → `loadLibraryLists()`, `Expired` → очистить очередь, `TokenStore` и списки, открыть вход с `LoginUi.notice = login_expired` и запросить новый код. Вход (`applyToken`) идёт через `session.signIn(token)`; токен сохраняется только после успеха. «Выйти» делает то же, что `Expired`, без сообщения. Собирает `queue.events` и рендерит их через `QueueEvent.render`. Заголовки источников (`library_liked`, `library_my_wave`, `queue_search_title`) берёт из ресурсов и передаёт в очередь как данные.

`PlayerViewModel` — `MediaController` к `PlaybackService`, снимок `PlayerUi` (позиция тикает каждые 250 мс, пока играет), транспорт, громкость/баланс/визуализатор/тема, EQ, флаг «продолжать волной» текущей очереди. Свои сообщения (shuffle, повтор, тема, EQ) публикует в `notices`, которые `AppRoot` перекладывает в `AppViewModel.say`. Громкость, баланс и EQ только записывает в `Settings`; к плееру и `AudioBus` их применяют `AppGraph` и `PlaybackService`, так что настройки действуют и без открытого экрана (см. `data/README.md`).

`seekToFraction(fraction)` переводит долю полосы в миллисекунды через `positionAt` из `Format.kt` (тот же расчёт, что у подписи времени), перематывает и в том же `update {}` пишет цель в `positionMs`, чтобы ни один кадр не показал старую позицию (TR-08). При неизвестной длительности (`positionAt` вернул `null`) ничего не делает. Перетаскивания полосы view model не видит: оно живёт в `PlayerScreen` (см. «Компоненты и экраны»).

`toggleShuffle()` и `toggleRepeat()` зовут `QueueController.setShuffle`/`setRepeat` (не `MediaController`) и показывают тост по выбору после команды (`queue.modes`); во время волны очередь не трогают и показывают `player_shuffle_wave` / `player_repeat_wave`. Кнопки SHF и RPT на экране плеера читают `queue.modes`, а не режим ExoPlayer: подсвечены, когда режим выбран и очередь не заперта, приглушены (`dimmer`), когда заперта (волна, станция, джем). Правило живёт в `queue/WaveModeRule`.

`next()` и `previous()` вызывают `seekToNext`/`seekToPrevious` у `MediaController`, `stop()` — `queue.stop()`; правила (3 с, конец очереди, конец волны) живут в `queue/QueueController` и одинаковы для приложения, уведомления и гарнитуры (см. `playback/README.md`, «Один хозяин плеера»). «Loading more…» показывает событие `QueueEvent.LoadingMore`.

**Traps:**
- Обе view model — `AndroidViewModel` с областью Activity; `PlayerViewModel.onCleared` освобождает контроллер.
- `PlayerUi.durationMs` берёт длительность из `MediaItem.extras`, пока ExoPlayer её не знает.
- `refresh` на каждое событие плеера строит `PlayerUi` заново (`PlayerUi(...)`, не `copy`), а тикер каждые 250 мс переписывает `positionMs`. Поле, которое не приходит из плеера, первое же событие сбросит в значение по умолчанию. Поэтому чисто экранное состояние (перетаскивание полосы и подобное) в `PlayerUi` не хранится никогда: оно живёт в `remember` экрана. Когда предпросмотр перетаскивания писался в `positionMs`, подпись времени мигала между пальцем и плеером (#90).
- `LoginStatus.Failed(error)` и `ListState.Failed(error)` хранят `yandex/ErrorKind`, а не текст; на экране — `error.render()` заглавными. Исключение пишется в logcat (`Log.w(LOG_TAG, …)`) там же, где поймано: `AppViewModel.kindOf`, отказ подключения `MediaController` в `PlayerViewModel`. Тост «сервис недоступен» текста исключения не показывает.
- `sessionState` меняется с `Dispatchers.Default` (`AppGraph.applicationScope`); коллектор во view model работает на главном потоке, поэтому `queue.clear()` там безопасен.

`AppRoot` при `SessionState.Offline` показывает над экраном строку `session_offline` («НЕТ СЕТИ · ПОВТОРЮ САМ») с красным `LedDot`; на экране входа строки нет. Повторы идут сами, кнопки «повторить» нет.

**«Назад».** `Screen.backTarget`: с вкладок EQ, Плейлист и Библиотека — на вкладку «Плеер» (`AppRoot`, `BackHandler`), с «Плеера» и экрана входа — системное поведение, то есть выход из приложения. Обработчики, объявленные позже, важнее: открытый раздел библиотеки сначала закрывается (`LibrarySectionScreen`), открытый лист пресетов EQ — тоже (`closePresets`), и только потом срабатывает переход на «Плеер».

`PlaylistScreen` показывает внизу списка строку `playlist_loading_more`, пока `QueueState.loadingMore`, а не всё время, пока играет волна. Когда это так, решает очередь (`queue/README.md`, «Ждёт продолжения»): у своей волны — пока идёт догрузка, у волны по истории — только пока очередь ждёт её треков после конца головы; тихий запрос за два трека до конца головы строку не показывает (CONT-04: без статуса).

**Продолжать волной** (CONT-01, CONT-02, `spec/player/continuation.md`). Два переключателя `OnOffPills`:
- **Плейлист** — строка под шапкой: `vibe_continue_title` и «вкл / выкл» по `QueueState.continueWithVibe`. Есть, только когда у очереди нет своего продолжения и она не пуста: `!isWave && jamSlots == null && tracks.isNotEmpty()` (не «Моя волна», не станция, не джем). Переключение — `PlayerViewModel.setQueueContinuesWithVibe(on)` → `queue.setContinueWithVibe(on)` и тост по флагу после команды: `vibe_continue_on` («за этой очередью пойдёт волна») или `vibe_continue_off` («эта очередь остановится в конце»). Меняет только эту очередь, не настройку.
- **Библиотека** — блок над «Качеством»: подпись `vibe_continue_title`, строка «Новые очереди» (`vibe_continue_new_queues`) с «вкл / выкл» по `Settings.continueWithVibe` и пояснение `vibe_continue_note`. Выбор — `AppViewModel.setContinueWithVibe` → `Settings.setContinueWithVibe` (ключ `playback/continueWithVibe`, по умолчанию вкл, `data/README.md`); действует с новой очереди: очередь берёт значение при создании и при `clear()` через лямбду `continueWithVibeByDefault` из `AppGraph`. На десктопе эта настройка есть только в `settings.ini`; у Android нет файла, который пользователь может править, поэтому без экрана умолчание было бы не изменить.

Отметки на треках волны в плейлисте нет: на десктопе отмечена только волна джема (HOST-34).

## Джем

Экраны хозяина джема (Kickoman/QiYaa-android#62, сценарии HOST-20…35 в `spec/jam/host.md`). Логика — `playback/JamHost`; `JamViewModel` только передаёт действия и превращает `JamHostEvent` и свои отказы («нет связи», «не настроено») в тосты через `notices` → `AppViewModel.say`.

- **Вход**: строка «Джем» в библиотеке (мета — «Начать» или «Идёт · N гостей»), а во время джема — тап по названию очереди на экране плеера (оно подсвечено акцентом). `Screen.JAM` → назад на «Плеер», `Screen.JAM_SETTINGS` → назад на «Джем».
- **`JamScreen`** по фазе `JamHostState.phase`: `NONE` — имя хозяина (по умолчанию имя аккаунта, ≤ 24 символа), «Начать джем», при пустом сервере — подсказка и кнопка настроек; `CREATING` — «Подключаюсь…» и «Отмена»; `ACTIVE` — QR `joinUrl` на 72 % ширины, ссылка, «Поделиться ссылкой» (`ACTION_SEND` через системный выбор) и «Копировать», поиск треков для джема (`Library.searchTracks`, «Следом» = `playNext`, «В джем» = `add`), гости (онлайн, вид, сколько ждёт, «Убрать»), настройки комнаты (порядок, пропуск гостями, вход), «Новая ссылка», «Закончить джем» с подтверждением вторым нажатием за 3 с. Состояние связи — в шапке.
- **Без связи** (HOST-25) «В джем», «Следом», «Убрать» и «Новая ссылка» приглушены, а любое действие с комнатой даёт тост `jam_no_connection`; над любым экраном — красная строка `jam_offline_strip`, тап ведёт на экран джема. Строка «нет сети» Яндекса важнее и показывается вместо неё.
- **«Продолжить джем?»** (HOST-23) — `JamContinueDialog` поверх любого экрана, кроме входа, пока `storedSession`.
- **Плейлист** (HOST-34): у элемента джема — «+ имя» того, кто добавил (`addedBy` по `room.participants`), у трека волны джема — «Волна джема». REM во время джема убирает выделенные элементы джема из очереди комнаты (`JamHost.remove`); они уйдут из плейлиста со следующим `state`. ADD ведёт на экран джема.
- **Поиск в библиотеке во время джема** (HOST-21) ищет треки для джема и открывает экран джема, а не заменяет очередь. Выбор источника (плейлист, альбом, волна) очередь не заменяет: `QueueEvent.JamActive` → тост `queue_jam_active`.
- «Выйти» заканчивает джем: гости ищут через аккаунт хозяина.
- **`JamSettingsScreen`**: сервер (по умолчанию `qiyaaJamUrl`) и «Учить волну джема» (`jam/waveFeedback`, HOST-16). Ключа хозяина нет.

## Тема

`QiColors` — токены макета в sRGB; акцент задаёт `AccentTheme`:

| Токен макета | Поле | Green | Amber | Ice |
|---|---|---|---|---|
| `--acc` (oklch L 0.8/0.82) | `accent` | `#29E16F` (oklch 0.8 0.21 150) | `#FFB113` (0.82 0.17 75) | `#59D6FA` (0.82 0.12 220) |
| `--acc2` (L 0.55/0.56) | `accentDark` | `#1C8742` | `#9D6800` | `#0A819D` |
| `--accbg` (L 0.28) | `accentBackground` | `#0F3118` | `#372508` | `#0E2D36` |

Остальные: `background #15161A`, `text #D9DBE0`, `textSecondary #A7ABB5`, `muted #8B8F99`, `dim #6C707A`, `dimmer #4A4E58`, `surface #1C1E23`, `surface2 #22242A`, `border #2B2E36`, `deep #0C0D10`, `insetBorder #1E2026`, `tabBackground #101114`, `handle #3A3E48`, `error #FA6863` (oklch 0.7 0.18 25). Тап по надписи QIYAA на экране плеера переключает акцент по кругу.

Текст: `mono(size, weight, tracking, color, lineHeight)` и `sans(size, weight, color, lineHeight)` с `LineHeightStyle(Center, Trim.Both)`, чтобы высота строки совпадала с CSS. Шрифты — `res/font/ibm_plex_*` (`scripts/fetch-fonts.sh`).

## Компоненты и экраны

`QiText` — `BasicText` без отступов Material. `Modifier.tap` — `clickable` без индикации. `OnOffPills(value, onText, offText, onChange)` — две `PillToggle` (подписи заглавными, по умолчанию `toggle_on` / `toggle_off`); `onChange` зовётся только при смене значения, тап по горящей пилюле ничего не делает. Ею пользуются настройки джема («Гости могут пропускать», «Новые гости» со своими подписями, «Учить волну джема», «Гости могут слушать»), строка плейлиста и блок библиотеки «Продолжать волной». `Chip` с `onLongClick` использует `combinedClickable` (тоже без индикации); в этой версии Compose он `@ExperimentalFoundationApi`, отсюда `@OptIn` на `Chip`. `VerticalFader` округляет до 0.1 дБ, тап по значению над фейдером сбрасывает полосу в 0.

```kotlin
fun HorizontalSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 28.dp,
    accent: Color = Qi.colors.accent,
    onChangeFinished: ((Float) -> Unit)? = null,
    onChangeCancelled: (() -> Unit)? = null,
)
```

`value` и доля в колбэках — от 0 до 1. `HorizontalSlider` держит своё `dragging`/`dragValue`: во время перетаскивания ползунок рисуется по пальцу, а не по `value`, поэтому сам он не мигает, что бы ни приходило в `value`. Перетаскивание (`detectDragGestures`) зовёт `onChange` с начала и на каждом движении, `onChangeFinished` при отпускании, `onChangeCancelled` из `onDragCancel`. Тап (`detectTapGestures`) зовёт `onChange` и сразу `onChangeFinished` в одном стеке вызовов; отмены у тапа не бывает, а перетаскивание, начавшись, тап отменяет. Все три колбэка идут через `rememberUpdatedState`: `pointerInput(Unit)` не перезапускается и иначе держал бы первые лямбды. Громкость и баланс отмену не передают.

**Traps (`HorizontalSlider`):**
- Уход пальца с полосы перетаскивание **не** отменяет: в `detectDragGestures` (Compose foundation 1.7) нет проверки границ, доля зажимается в 0 или 1, и отпускание перематывает туда. Так перематывают в начало и в конец; проверку границ не добавлять.
- `onChangeCancelled` приходит только при отмене касания системой (`ACTION_CANCEL`): кнопка питания или «Домой» в трёхкнопочной навигации во время перетаскивания, начатый и брошенный жест «назад» (если перетаскивание началось у левого края полосы, в зоне жеста), уход окна. Родительский `verticalScroll` перехватить перетаскивание не успевает.
- Если ползунок уходит из композиции посреди перетаскивания (вкладка, нажатая вторым пальцем, вход при `SessionState.Expired`, смена ключа `key(…)`), корутина `pointerInput` отменяется молча: ни `onChangeFinished`, ни `onChangeCancelled`. Состояние перетаскивания у вызывающего должно кончаться вместе с композицией.

**Перетаскивание полосы перемотки** (Kickoman/QiYaa-android#90) живёт в `PlayerScreen`, а не во view model:

```kotlin
val trackId = track?.id
var dragFraction by remember(trackId) { mutableStateOf<Float?>(null) }
key(trackId) { HorizontalSlider(value = fraction, onChange = …, onChangeFinished = …, onChangeCancelled = …) }
```

`dragFraction` — `null`, пока полосу не тащат. `onChange` пишет его; `onChangeFinished` сначала сбрасывает его, потом зовёт `seekToFraction` (так перетаскивание не остаётся висеть, даже если перемотки не будет); `onChangeCancelled` сбрасывает, и подпись возвращается к позиции плеера, без перемотки. `value` ползунка — позиция плеера, `positionMs / durationMs`.

Подпись времени — `timeLabel(positionMs, durationMs, hasTrack, remaining, dragFraction)`:
- без трека — `00:00`;
- пока тащат и длительность известна — позиция под пальцем `positionAt(dragFraction, durationMs)`, иначе позиция плеера;
- прошедшее или `-` и оставшееся по `settings.timeRemaining`.

Тикер и `refresh` пишут только `PlayerUi.positionMs`, поэтому подпись под пальцем не прыгает. `positionAt` общий с `seekToFraction`, так что подпись под пальцем — ровно цель перемотки, и при отпускании она не сдвигается (TR-08). Хранится доля, а не миллисекунды: длительность может уточниться посреди перетаскивания (пока ExoPlayer её не знает, она берётся из `extras`, потом тикер пишет настоящую), и подпись идёт за ней так же, как цель при отпускании. При неизвестной длительности подпись перетаскивание не показывает, как и `seekToFraction` не перематывает.

Перетаскивание не переживает свой трек: при другом `track.id` `remember(trackId)` сбрасывает подпись, а `key(trackId)` пересоздаёт ползунок и обрывает его перетаскивание (без колбэков), так что старая доля не перемотает новый трек — до следующего касания новый ползунок ничего не получает. Тот же трек дважды подряд (повтор очереди из одного трека) ключ не меняет, и перетаскивание продолжается: длительность та же. Уход с экрана посреди перетаскивания забывает его вместе с композицией.

`EqScreen`: подписи полос `BAND_LABELS` живут здесь, а не в `audio`. График соединяет значения полос в координатах макета (x = 10 + i·344/9 из 364, y = 44 ∓ 36 из 88). `PresetsSheet` рисуется поверх всего приложения из `AppRoot`.

**Списки библиотеки.** Каждый список грузит свой `ListLoader` в своей корутине на `Dispatchers.IO`, поэтому списки приходят параллельно, и упавший или зависший список не держит остальные.

```kotlin
class ListLoader<T>(scope: CoroutineScope, io: CoroutineContext, fetch: suspend () -> List<T>, logFailure: (Throwable) -> Unit) {
    val state: StateFlow<ListState<T>>
    fun load(force: Boolean = false)   // Loaded и Loading без force не перезапрашиваются; force отменяет запрос в полёте
    fun reset()                        // Idle; ответ отменённого запроса не публикуется
}
```

| Когда | Что грузится |
|---|---|
| `SessionState.Online`, открытие экрана библиотеки | `loadLibraryLists()`: все пять списков (загруженные пропускаются), лайки, если их ещё нет |
| Открытие раздела, тап «повторить» | `loadSection(section)`: только список раздела; упавший список запрашивается снова |
| Открытие «Колеса волн» | `wheel.load(force = true)` с сидом текущей волны |
| Выход, истёкший токен | `reset()` у всех списков |

Строка библиотеки показывает состояние своего списка: `…` до запроса, `library_loading`, число элементов или `library_row_error`. Раздел с упавшим списком показывает `library_error` с видом ошибки и `library_retry`; тап повторяет запрос.

`LibrarySectionScreen` группирует станции по `Library.stationGroupKey` в порядке `STATION_GROUP_ORDER` (неизвестные группы в конце, в порядке ответа сервера), плейлисты делит на «мои» и «сохранённые» по `ownerUid`; «Для вас» (`LibraryUi.forYou`, `Library.personalPlaylists`) — одна группа в порядке ответа, чип играет плейлист, как обычный. Долгое нажатие на чип плейлиста (в «Плейлистах» и «Для вас») ставит «Похожие треки» (`playSimilar` → `Library.playlistRecommendations`, заголовок `queue_similar_title`, источник `similar:{owner}:{kind}`); над группами — подсказка `library_similar_hint`. «Колесо волн» (`LibrarySection.WHEEL`) загружается при каждом открытии раздела: `Library.wheelWaves` с сидом `QueueController.currentWaveSeed()`, а если волна не играет — с `MY_WAVE_SEED`; заголовок группы говорит, под что подобрано (`library_group_wheel_current` или `library_group_wheel_my`). Волны колеса показаны строками с описанием (`ChipItem.description`), а не чипами; строка играет волну по её `seeds` (`playWheelWave` → `playWave`, фидбек как у любой волны). Активный чип — `QueueState.activeSourceId`; id источников: `liked`, `playlist:{owner}:{kind}`, `similar:{owner}:{kind}`, `artist:{id}`, `album:{id}`, id станции.

`VisualizerStrip`: 1024 кадра из `VisualizerTap`, которые кончаются на звучащем сейчас (`readPlaying(…, System.nanoTime())`, см. `audio/README.md`), кадр каждые 33 мс, только пока играет; спектр — 19 полос с пиками, осциллограф — последние 576 кадров (окно Winamp в 75 px). В режиме `OFF` цикл не запускается.

## Строки

Все тексты — `res/values/strings.xml` (английский), `res/values-be/strings.xml` (белорусский) и `res/values-ru/strings.xml`; `queue_loaded` и `queue_stopped_after_failures` — `plurals` (у белорусского и русского формы `one`, `few`, `many`, `other`). «Волна» Яндекса по-английски — **vibe** («My Vibe», «Jam vibe», «Wheel of vibes»), не wave; ключи (`library_my_wave`…) остались прежними. Слова те же, что на ПК: таблица в `translations/README.md` репозитория QiYaa. `StringsTest` проверяет, что каждый текст переведён на оба языка с теми же подстановками, формы plurals и что ни один английский текст не говорит «wave».

**Качество звука** (`spec/player/quality.md`). Внизу библиотеки, над выбором языка: заголовок `quality_title`, строки «Wi-Fi» (`quality_wifi`) и «Мобильная сеть» (`quality_mobile`) с двумя `PillToggle` — `quality_standard` и `quality_economy` — и пояснение `quality_note`. Выбор уходит в `AppViewModel.setQuality` / `setQualityMetered` и действует со следующей подписи ссылки (см. `playback/README.md`, кэш ссылок). Примечание к «Слушать вместе» (`jam_share_audio_note`) говорит, что слушатели получают файлы только при стандартном качестве (LISTEN-13).

**Язык интерфейса — свой, не системный.** `data/Settings.language` (по умолчанию белорусский). `AppLocale` даёт его приложению и `PlaybackService` в `attachBaseContext` (контекст с локалью) и активности — через `applyOverrideConfiguration`, чтобы она по-прежнему следила за экраном и тёмной темой. `LanguageSwitch` (коды `BE RU EN`, текущий подсвечен) стоит на экране входа (сменить язык можно до входа) и внизу библиотеки с подписью `language_title` и английским словом «LANGUAGE», чтобы непонятный язык можно было отменить. Выбор: `AppViewModel.setLanguage` сохраняет язык и переводит ресурсы приложения (`AppLocale.apply`), экран пересоздаёт активность; сервис перестраивает кнопки уведомления под новый язык. Системная смена конфигурации сбрасывает локаль ресурсов приложения и сервиса — `onConfigurationChanged` применяет язык заново. В сборке `bundle.language.enableSplit = false`: все языки должны быть в установке. Ключи `jam_*` — экраны джема; `toggle_on` / `toggle_off` — подписи `OnOffPills` по умолчанию (до #79 — `jam_on` / `jam_off`, тексты те же: On / Off, Да / Нет, Так / Не); `vibe_continue_*` — «Продолжать волной» в плейлисте и библиотеке; `jam_refused_*` — причины отказа сервера, неизвестная причина — `jam_refused_other`. Ключи `queue_*` рендерят `QueueEvent` (в том числе `queue_waiting_for_network` и plurals `queue_stopped_after_failures`), `theme_*` — подписи `AccentTheme`.

## Not here

- Логика очереди (что играть дальше, догрузка волны) — `queue/QueueController`. Ключи настроек — `data/README.md`.

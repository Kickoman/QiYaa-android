# `ui/` — Compose

Четыре вкладки (Player · Playlist · EQ · Library), экран входа, мини-плеер и тосты, нарисованные вручную по дизайн-системе «Winamp-inspired mobile music player»: без Material-компонентов, без ripple, IBM Plex Mono для показаний и IBM Plex Sans для контента. Пакет только читает `StateFlow`-ы и вызывает методы view model. Он **не** содержит доменной логики: очередь — `playback/`, сеть — `yandex/`, DSP — `audio/`.

```
ui/
  AppRoot.kt, MainActivity.kt          корень Compose, вкладки, мини-плеер, тост
  AppViewModel.kt, PlayerViewModel.kt  состояние экранов
  QueueEventText.kt, Format.kt         рендер событий и чисел в строки
  components/   QiText, кнопки, чипы, слайдеры, иконки
  screens/      по одному файлу на экран
  theme/        цвета, шрифты, стили текста
  visualizer/   полоса спектра/осциллографа
```

Зависимости: `screens → components → theme`; `visualizer → components, theme, audio`. `theme` не зависит от остального `ui`.

| Файл | Содержит |
|---|---|
| `AppRoot.kt` | `AppRoot`, `MiniPlayer`, `TabBar` |
| `MainActivity.kt` | `MainActivity` — `setContent { AppRoot() }` и запрос `POST_NOTIFICATIONS` на Android 13+ |
| `AppViewModel.kt` | `Screen`, `LibrarySection`, `LoginStatus`, `LoginUi`, `LibraryUi`, `AppViewModel`, `PlaylistRef.sourceId` |
| `ListLoader.kt` | `ListState` (`Idle`, `Loading`, `Loaded(items)`, `Failed(error)`), `ListLoader` — загрузка одного списка библиотеки со своей ошибкой и повтором |
| `PlayerViewModel.kt` | `PlayerUi`, `PlayerViewModel`, `AccentTheme.labelId` |
| `QueueEventText.kt` | `QueueEvent.render(context)` — событие очереди → строка из ресурсов |
| `ErrorText.kt` | `ErrorKind.text()` — строка вида ошибки (`error_*`, у `ServerError` со статусом), `render(context)` и `@Composable render()`; `LOG_TAG = "QiYaa"` |
| `Format.kt` | `formatTime`, `balanceLabel`, `formatReadout`, `formatDb` |
| `components/Components.kt` | `QiText`, `Modifier.tap`, `ScreenHeader`, `ActionButton`, `IconActionButton`, `PillToggle`, `RoundButton`, `LedDot`, `Chip` |
| `components/Sliders.kt` | `HorizontalSlider` (0…1), `VerticalFader` (дБ) |
| `components/Icons.kt` | `IconPaths`, `PathIcon` — SVG-пути из макета (viewBox 24) |
| `screens/LoginScreen.kt` | `LoginScreen` |
| `screens/PlayerScreen.kt` | `PlayerScreen`, `marqueeText`, `queueTitle` |
| `screens/PlaylistScreen.kt` | `PlaylistScreen` |
| `screens/EqScreen.kt` | `EqScreen`, `PresetsSheet` |
| `screens/LibraryScreen.kt` | `LibraryScreen` |
| `screens/LibrarySectionScreen.kt` | `LibrarySectionScreen` — «для вас» / станции / плейлисты / исполнители / альбомы чипами |
| `theme/Color.kt` | `QiColors`, `accentColors`, `LocalQiColors` |
| `theme/Theme.kt` | `Qi.colors`, `QiYaaTheme` |
| `theme/Type.kt` | `PlexMono`, `PlexSans`, `mono()`, `sans()`, `CaptionStyle`, `LabelStyle`, `ButtonStyle`, `ReadoutStyle`, `HintStyle` |
| `visualizer/VisualizerStrip.kt` | `VisualizerStrip` |

## View models

`AppViewModel` — навигация (`screen`, `section`), вход (`login`), списки библиотеки (`forYou`, `playlists`, `artists`, `albums`, `stations`, `wheel` — по `ListLoader` на каждый; `libraryUi` хранит только текст поиска и `wheelMatchesCurrent`), тост (`toast`, 1.8 с). При старте: есть токен → `Screen.PLAYER`, иначе `Screen.LOGIN` и `startDeviceLogin()`. Подключением управляет `yandex/Session` (`AppGraph.session`), view model только слушает `sessionState`: `Online` → `loadLibraryLists()`, `Expired` → очистить очередь, `TokenStore` и списки, открыть вход с `LoginUi.notice = login_expired` и запросить новый код. Вход (`applyToken`) идёт через `session.signIn(token)`; токен сохраняется только после успеха. «Выйти» делает то же, что `Expired`, без сообщения. Собирает `queue.events` и рендерит их через `QueueEvent.render`. Заголовки источников (`library_liked`, `library_my_wave`, `queue_search_title`) берёт из ресурсов и передаёт в очередь как данные.

`PlayerViewModel` — `MediaController` к `PlaybackService`, снимок `PlayerUi` (позиция тикает каждые 250 мс, пока играет), транспорт, громкость/баланс/визуализатор/тема, EQ. Свои сообщения (shuffle, повтор, тема, EQ) публикует в `notices`, которые `AppRoot` перекладывает в `AppViewModel.say`. Громкость, баланс и EQ только записывает в `Settings`; к плееру и `AudioBus` их применяют `AppGraph` и `PlaybackService`, так что настройки действуют и без открытого экрана (см. `data/README.md`).

`toggleShuffle()` и `toggleRepeat()` во время волны плеер не трогают и показывают `player_shuffle_wave` / `player_repeat_wave`; кнопки SHF и RPT на экране плеера при волне приглушены (`dimmer`). Правило живёт в `queue/WaveModeRule`.

`next()` и `previous()` вызывают `seekToNext`/`seekToPrevious` у `MediaController`, `stop()` — `queue.stop()`; правила (3 с, конец очереди, конец волны) живут в `queue/QueueController` и одинаковы для приложения, уведомления и гарнитуры (см. `playback/README.md`, «Один хозяин плеера»). «Loading more…» показывает событие `QueueEvent.LoadingMore`.

**Traps:**
- Обе view model — `AndroidViewModel` с областью Activity; `PlayerViewModel.onCleared` освобождает контроллер.
- `PlayerUi.durationMs` берёт длительность из `MediaItem.extras`, пока ExoPlayer её не знает.
- `LoginStatus.Failed(error)` и `ListState.Failed(error)` хранят `yandex/ErrorKind`, а не текст; на экране — `error.render()` заглавными. Исключение пишется в logcat (`Log.w(LOG_TAG, …)`) там же, где поймано: `AppViewModel.kindOf`, отказ подключения `MediaController` в `PlayerViewModel`. Тост «сервис недоступен» текста исключения не показывает.
- `sessionState` меняется с `Dispatchers.Default` (`AppGraph.applicationScope`); коллектор во view model работает на главном потоке, поэтому `queue.clear()` там безопасен.

`AppRoot` при `SessionState.Offline` показывает над экраном строку `session_offline` («НЕТ СЕТИ · ПОВТОРЮ САМ») с красным `LedDot`; на экране входа строки нет. Повторы идут сами, кнопки «повторить» нет.

**«Назад».** `Screen.backTarget`: с вкладок EQ, Плейлист и Библиотека — на вкладку «Плеер» (`AppRoot`, `BackHandler`), с «Плеера» и экрана входа — системное поведение, то есть выход из приложения. Обработчики, объявленные позже, важнее: открытый раздел библиотеки сначала закрывается (`LibrarySectionScreen`), открытый лист пресетов EQ — тоже (`closePresets`), и только потом срабатывает переход на «Плеер».

`PlaylistScreen` показывает строку `playlist_loading_more` только пока идёт догрузка (`QueueState.loadingMore`), а не всё время, пока играет волна.

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

`QiText` — `BasicText` без отступов Material. `Modifier.tap` — `clickable` без индикации. `Chip` с `onLongClick` использует `combinedClickable` (тоже без индикации); в этой версии Compose он `@ExperimentalFoundationApi`, отсюда `@OptIn` на `Chip`. `HorizontalSlider` отдаёт `onChange` во время перетаскивания и `onChangeFinished` при отпускании; `PlayerScreen` пользуется этим для предпросмотра позиции. `VerticalFader` округляет до 0.1 дБ, тап по значению над фейдером сбрасывает полосу в 0.

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

`VisualizerStrip`: 1024 последних кадра из `VisualizerTap`, кадр каждые 33 мс, только пока играет; спектр — 19 полос с пиками, осциллограф — последние 576 кадров (окно Winamp в 75 px). В режиме `OFF` цикл не запускается.

## Строки

Все тексты — `res/values/strings.xml` (английский) и `res/values-ru/strings.xml`; `queue_loaded` — `plurals`. Ключи `queue_*` рендерят `QueueEvent` (в том числе `queue_waiting_for_network` и plurals `queue_stopped_after_failures`), `theme_*` — подписи `AccentTheme`.

## Not here

- Логика очереди (что играть дальше, догрузка волны) — `queue/QueueController`. Ключи настроек — `data/README.md`.

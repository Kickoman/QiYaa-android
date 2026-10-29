# `ui/` — Compose

Четыре вкладки (Player · Playlist · EQ · Library), экраны входа в Яндекс и в Spotify, экраны джема, мини-плеер и тосты, нарисованные вручную по дизайн-системе «Winamp-inspired mobile music player»: без Material-компонентов, без ripple, IBM Plex Mono для показаний и IBM Plex Sans для контента. Пакет только читает `StateFlow`-ы и вызывает методы view model. Он **не** содержит доменной логики: очередь — `playback/`, сеть — `yandex/` и `spotify/`, DSP — `audio/`.

```
ui/
  AppRoot.kt, MainActivity.kt          корень Compose, вкладки, мини-плеер, тост
  AppViewModel.kt, PlayerViewModel.kt, JamViewModel.kt, JamGuestViewModel.kt, SpotifyViewModel.kt  состояние экранов
  LinkRouting.kt                       куда ведёт ссылка на джем
  QueueEventText.kt, JamEventText.kt, QueueNotices.kt, Format.kt   рендер событий и чисел в строки
  TrackPages.kt                        страница трека в браузере
  components/   QiText, кнопки, чипы, слайдеры, иконки, секции экранов джема
  screens/      по одному файлу на экран
  theme/        цвета, шрифты, стили текста
  visualizer/   полоса спектра/осциллографа
```

Зависимости: `screens → components → theme`; `visualizer → components, theme, audio`. `theme` не зависит от остального `ui`.

| Файл | Содержит |
|---|---|
| `AppRoot.kt` | `AppRoot`, `MiniPlayer`, `TabBar` |
| `MainActivity.kt` | `MainActivity` — язык интерфейса (`applyOverrideConfiguration` в `attachBaseContext`), `setContent { AppRoot(app) }`, запрос `POST_NOTIFICATIONS` на Android 13+ и ссылки на джем из `Intent` (App Link и «Поделиться») → `AppViewModel.openJamLink` |
| `Navigation.kt` | `Screen`, `Screen.backTarget`, `Screen.isLogin` (`LOGIN` и `SPOTIFY_LOGIN`: без вкладок, мини-плеера, строки «нет сети» и диалога джема), `startScreen`, `screenAfterSignOut` — куда ведут старт, «назад» и выход (чистые функции, `NavigationTest`) |
| `AppViewModel.kt` | `LibrarySection` (и `isLocal`, `spotifyList` — какой список Spotify открывает раздел), `LoginStatus`, `LoginUi`, `LibraryUi`, `AppViewModel` (в том числе `openJamLink`), `PlaylistRef.sourceId` |
| `SpotifyViewModel.kt` | `SpotifyList`, `SpotifyAccess`, `spotifyAccessOf`, `spotifyCanLogOut`, `SpotifyUi`, `SpotifyCodeStatus`, `SpotifyBrowserStatus`, `SpotifyLoginUi`, `SpotifyGateway`, `SpotifyViewModel` — Spotify для экранов: вход кодом и в браузере, раздел Library и его списки, выборы, поиск, радио (ниже, «Spotify») |
| `QueueNotices.kt` | `QueueState.fromSpotify`, `continueNotice`, `lockedShuffleNotice`, `lockedRepeatNotice` — тосты «продолжать волной», shuffle и повтора: у очереди Spotify свои (`QueueNoticesTest`) |
| `LinkRouting.kt` | `HostSide`, `GuestSide`, `LinkRefusal`, `LinkAction`, `route(…)`, `GuestPhase.isSession` — решение, что делать со ссылкой на джем (GUEST-01, GUEST-20, GUEST-21) |
| `LocalAccess.kt` | `LocalAccess` (`GRANTED`, `ASK`, `ASK_AGAIN`, `SETTINGS`), `LocalAccessRule` — что предлагает раздел «Свои файлы» до и после ответа на запрос разрешения (LOCAL-10, чистые функции, `LocalAccessTest`) |
| `SearchModel.kt` | `FoundPick`, `Found` (`of(yandex.SearchResult)`, `of(SpotifyFound, треки)`, `shown()`, `playable()`, `isEmpty`) — что показывает поиск; `SearchSource`, `SearchPart`, `SearchPhase`, `SearchOpened`, `SearchTarget`, `SearchUi`, `shownPart` — состояние экрана поиска (ниже, «Поиск»; `SearchModelTest`) |
| `SearchViewModel.kt` | `SearchGateway`, `SearchViewModel` — экран поиска: запросы, открытый исполнитель или альбом, игра и добавление (ниже, «Поиск»; `SearchViewModelTest`) |
| `ListLoader.kt` | `ListState` (`Idle`, `Loading`, `Loaded(items)`, `Failed(error)`), `ListLoader` — загрузка одного списка библиотеки со своей ошибкой и повтором |
| `PlayerViewModel.kt` | `PlayerUi`, `TrackActions`, `PlayerViewModel`, `AccentTheme.labelId` |
| `JamViewModel.kt` | `JamUi`, `JamViewModel` — хозяин джема для экранов: `playback/JamHost`, поиск треков для джема, настройки `jam/*` |
| `JamGuestViewModel.kt` | `GuestTab`, `JamGuestUi`, `JamGuestViewModel` — гость в чужом джеме для экранов: `jam/JamGuest`, свои поиск, лайки и плейлисты через `yandex/Library`, «лайк себе» |
| `JamTrackNote.kt` | `jamTrackNote(entry, isWave, jamOn, names)` — метка строки плейлиста во время джема (HOST-34) как `TextRes` или null |
| `JamEventText.kt` | `JamHostEvent.render(context)`, `JamGuestEvent.render(context)`, `refusedText(reason)` (все 18 причин протокола), `guestRefusedText(reason)`, `GuestPhase.endText()`, `JamGuestState.statusText()`, `GuestAddResult.text()` — отказы, концы джема и сессии гостя, «нет связи» хозяина (`NotConnected`), строка состояния гостя → строки |
| `QueueEventText.kt` | `QueueEvent.render(context)` — событие очереди → строка из ресурсов |
| `ErrorText.kt` | `ErrorKind.text()` — строка вида ошибки (`error_*`, у `ServerError` со статусом), `render(context)` и `@Composable render()`; `LOG_TAG = "QiYaa"` |
| `Format.kt` | `formatTime`, `positionAt` (доля полосы перемотки → мс), `timeLabel` (подпись времени плеера), `balanceLabel`, `formatReadout` (битрейт больше 999 — сотнями, «14H», как `Ui::BitrateText` десктопа), `formatDb` |
| `TrackPages.kt` | `Track.webUrl` — страница трека в браузере по `track.source`: Яндекс — `https://music.yandex.ru/album/<albumId>/track/<id>`, без альбома `https://music.yandex.ru/track/<id>`; Spotify — `https://open.spotify.com/track/<id>` (SPOT-19); свой файл — `null` (`TrackPagesTest`). Здесь, а не в `yandex/`: зовут её только экраны, и `yandex` не знает адресов Spotify |
| `components/Components.kt` | `QiText`, `Modifier.tap`, `ScreenHeader`, `ActionButton`, `IconActionButton`, `IconWideButton` (широкая кнопка с иконкой и описанием для экранного диктора), `LikeButton`, `PillToggle`, `OnOffPills` (пара `PillToggle` «вкл / выкл»), `RoundButton`, `LedDot`, `Chip` |
| `components/Sliders.kt` | `HorizontalSlider` (0…1), `VerticalFader` (дБ) |
| `components/Icons.kt` | `IconPaths`, `PathIcon` — SVG-пути (viewBox 24): транспорт из макета; сердце, палец вниз и «открыть снаружи» для кнопок лайка, дизлайка и браузера, контуром (`strokeWidth = IconPaths.OUTLINE`), сердце лайкнутого трека — заливкой. Подписи у этих кнопок нет (по-белорусски и по-русски она не помещается), текст — их `contentDescription` |
| `components/QrCode.kt` | `QrCodeImage` — QR (qrcodegen, коррекция M) тёмным по белому с полями в 4 модуля |
| `components/Sections.kt` | `SectionLabel`, `Note`, `Panel`, `Divider`, `SettingRow`, `InsetField` — секции экранов джема (хозяина и гостя), простые значения на входе |
| `screens/LoginScreen.kt` | `LoginScreen`, `openInBrowser` (адрес только браузеру; его же зовёт вход Spotify) |
| `screens/SpotifyLoginScreen.kt` | `SpotifyLoginScreen` — вход в Spotify: в браузере, кодом, QR (SPOT-01, SPOT-02) |
| `screens/SearchScreen.kt` | `SearchScreen`, `SearchAddSheet` — экран поиска и лист «Играть следом / В конец очереди» (ниже, «Поиск») |
| `screens/SpotifySectionScreen.kt` | `SpotifySectionScreen` — «Для вас», плейлисты, альбомы или исполнители Spotify ленивым списком |
| `screens/LibrarySpotify.kt` | `SpotifySources` — раздел «Spotify» в Library |
| `screens/PlayerScreen.kt` | `PlayerScreen`, `marqueeText`, `queueTitle` |
| `screens/LyricsPanel.kt` | `LyricsPanel` — текст песни на месте обложки, `LyricsPlace`, `currentLine`, `LrclibConsentDialog` — согласие перед LRCLIB |
| `screens/PlaylistScreen.kt` | `PlaylistScreen`, `TrackActionsSheet` — лист действий с треком строки |
| `screens/EqScreen.kt` | `EqScreen`, `PresetsSheet` |
| `screens/LibraryScreen.kt` | `LibraryScreen`; `SourceRow`, `SearchField`, `LibrarySearchField` (поле Library: свой текст, Enter открывает экран поиска), `listMeta` — общие с `LibrarySpotify.kt` и `SearchScreen.kt` |
| `screens/JamScreen.kt` | `JamScreen`, `JamSettingsScreen`, `JamContinueDialog` |
| `screens/JamGuestScreen.kt` | `JamGuestScreen` (комната гостя по фазам), `JamGuestAddScreen` (добавить треки: поиск · лайки · плейлисты), `JoinJamSection` (вставить ссылку; стоит и на `JamScreen`) |
| `screens/LibrarySectionScreen.kt` | `LibrarySectionScreen` — «для вас» / станции / плейлисты / исполнители / альбомы чипами |
| `screens/LocalSectionScreen.kt` | `LocalSectionScreen` — свои альбомы, исполнители или папки ленивым списком (LOCAL-11) |
| `theme/Color.kt` | `QiColors`, `accentColors`, `LocalQiColors` |
| `theme/Theme.kt` | `Qi.colors`, `QiYaaTheme` |
| `theme/Type.kt` | `PlexMono`, `PlexSans`, `mono()`, `sans()`, `CaptionStyle`, `LabelStyle`, `ButtonStyle`, `ReadoutStyle`, `HintStyle` |
| `visualizer/VisualizerStrip.kt` | `VisualizerStrip` |

## View models

`AppViewModel` — навигация (`screen`, `section`), вход (`login`), есть ли телеметрия (`telemetryAvailable`), списки библиотеки (`forYou`, `playlists`, `artists`, `albums`, `stations`, `wheel` — по `ListLoader` на каждый; `libraryUi` хранит только `wheelMatchesCurrent`); экран поиска — `openSearch(returnTo)` и `leaveSearch()` (ниже, «Поиск»), тост (`toast`, 1.8 с). Вход в Яндекс — не порог (Kickoman/QiYaa-android#115): без него приложение работает, а Яндекс — раздел Library. «Вошёл» — `yandex/SessionState.signedIn` (всё, кроме `LoggedOut` и `Expired`; `Session.start()` с токеном сразу `Connecting`), одно определение на всё приложение: стартовый экран, `routeLink`, экраны и `JamViewModel`. Старт — `startScreen(signedIn, spotifyLoggedIn, restoreGuest)`: без входа в Яндекс `jamGuest.restore()` не зовётся вовсе (он соединяется с комнатой, GUEST-19 — «signed in»), и старт — `Screen.PLAYER` при сохранённом входе Spotify (`SpotifyAccount.hasSavedLogin()`, ещё до подключения), иначе `Screen.LIBRARY`; со входом в Яндекс — `Screen.JAM_GUEST`, если `restore()` поднял сессию гостя, иначе `Screen.PLAYER`. Подключением управляет `yandex/Session` (`AppGraph.session`), view model только слушает `sessionState`: `Online` → `loadLibraryLists()`, `Expired` → `leaveSession(expired = true)`.

**Вход.** `openLogin(notice, jamPage, returnTo)` открывает `Screen.LOGIN` и запрашивает код устройства. `notice`: `null` из Library, `jam_join_needs_login` для ссылки на джем (`LinkAction.AskSignIn`, тогда ещё `jamPage` — кнопка «Открыть в браузере» с адресом ссылки), `login_expired` для `Expired`. Повторный `openLogin` при открытом входе меняет только `notice` и `jamPageUrl`: код тот же, человек, может быть, уже набирает его на ya.ru/device. Вход по коду и токеном (`useToken`) идёт в одном `loginJob`. Уйти со входа можно системным «назад» и «ПОЗЖЕ» (вкладок на нём нет); оба — `leaveLogin()`, то есть `go(returnTo)`, а `go()`, уходя с `LOGIN`, отменяет `loginJob` и забывает отложенную ссылку. Отменённый запрос дорабатывает в фоне, но токен нового входа не стирает (`yandex/Session.signIn`). Пояснение входа красное только у `login_expired` (`LoginUi.noticeIsError`). Отмена в любой фазе — не ошибка: `applyToken` пробрасывает `CancellationException`, ни тоста, ни `login/failed`; `Session.signIn` при отмене сбрасывает токен, как при любой неудаче, если после неё не начался новый вход. Удачный вход (`applyToken`, токен сохраняется только после успеха) уходит со входа через `finishLogin()` — на `returnTo` (`LIBRARY`, с экрана джема — `JAM`), затем открывает отложенную ссылку (GUEST-01).

**Выход.** `leaveSession(expired)`: `jamHost.cancelCreate()` и `jamHost.end()` (гости ищут через этот аккаунт, HOST-32; джем кончается и во время «Подключаюсь…»), гость — `Expired` только выводит из комнаты (`jamGuest.leave()`, членства остаются: это тот же человек на том же устройстве, GUEST-03), «Выйти» забывает все членства (`jamGuest.forgetAll()`); очередь, `TokenStore` и списки чистятся. Дальше `screenAfterSignOut`: «Выйти» — Library без нового кода, `Expired` — вход с `login_expired` (ERR-08), с которого можно уйти. Собирает `queue.events` и рендерит их через `QueueEvent.render`. Заголовки источников (`library_liked`, `library_my_wave`, `queue_search_title`) берёт из ресурсов и передаёт в очередь как данные. Неудачный вход — по коду устройства (`LoginStatus.Failed`) или токеном (`applyToken`) — ещё и `error login/failed` телеметрии (`graph.telemetry?.loginFailed()`, без телеметрии `telemetry` — `null`); `SessionState.Expired` туда же отправляет сам `AppTelemetry` (`telemetry/README.md`).

`PlayerViewModel` — `MediaController` к `PlaybackService`, снимок `PlayerUi` (позиция тикает каждые 250 мс, пока играет), транспорт, громкость/баланс/визуализатор/тема, EQ, флаг «продолжать волной» текущей очереди. Свои сообщения (shuffle, повтор, тема, EQ) публикует в `notices`, которые `AppRoot` перекладывает в `AppViewModel.say`. Громкость, баланс и EQ только записывает в `Settings`; к плееру и `AudioBus` их применяют `AppGraph` и `PlaybackService`, так что настройки действуют и без открытого экрана (см. `data/README.md`).

`seekToFraction(fraction)` переводит долю полосы в миллисекунды через `positionAt` из `Format.kt` (тот же расчёт, что у подписи времени), перематывает и в том же `update {}` пишет цель в `positionMs`, чтобы ни один кадр не показал старую позицию (TR-08). При неизвестной длительности (`positionAt` вернул `null`) ничего не делает. Дальше цель держит `MediaController`: он маскирует перемотку (`BUFFERING`, `isPlaying = false`, позиция — цель) и откладывает состояние от сессии, пока команду не подтвердят, поэтому `refresh` на `onEvents` читает цель и останавливает тикер. Тикер снова идёт, только когда плеер заиграет, и уже с цели; пауза остаётся паузой. Как это устроено в Media3 — `playback/README.md`, «Перемотка и буфер». Перетаскивания полосы view model не видит: оно живёт в `PlayerScreen` (см. «Компоненты и экраны»).

`toggleShuffle()` и `toggleRepeat()` зовут `QueueController.setShuffle`/`setRepeat` (не `MediaController`) и показывают тост по выбору после команды (`queue.modes`); во время волны очередь не трогают и показывают `lockedShuffleNotice` / `lockedRepeatNotice` (`QueueNotices.kt`): у радио Spotify — `player_shuffle_radio` / `player_repeat_radio` («порядок задаёт Spotify», «радио продолжается само»), у волны Яндекса — `player_shuffle_wave` / `player_repeat_wave`. Кнопки SHF и RPT на экране плеера читают `queue.modes`, а не режим ExoPlayer: подсвечены, когда режим выбран и очередь не заперта, приглушены (`dimmer`), когда заперта (волна, станция: `modes.locked` = `isWave`). Джем очередь не запирает: над базой с головой SHF и RPT работают и во время джема, над «Моей волной» и станцией приглушены, как вне джема. Правило живёт в `queue/WaveModeRule`.

`next()` и `previous()` вызывают `seekToNext`/`seekToPrevious` у `MediaController`, `stop()` — `queue.stop()`; правила (3 с, конец очереди, конец волны) живут в `queue/QueueController` и одинаковы для приложения, уведомления и гарнитуры (см. `playback/README.md`, «Один хозяин плеера»). «Loading more…» показывает событие `QueueEvent.LoadingMore`.

**Лайк и дизлайк.** ♥ экрана плеера и библиотеки — `toggleLikeCurrent()` → `queue.toggleLike(ui.current)`. Их дизлайк — `dislikeCurrent()` → `queue.dislike(ui.current)`: запрос уходит показанному треку, а «вперёд» — только если он ещё текущий, когда очередь получает вызов (TR-10, `queue/README.md`, «Дизлайк»). Нажатие во время смены трека дизлайкает показанный (старый) трек и не пропускает новый.

**Действия с треком плейлиста** (Kickoman/QiYaa-android#84, TR-10):

```kotlin
data class TrackActions(val track: Track, val liked: Boolean, val isCurrent: Boolean, val radio: Boolean)   // radio — трек Spotify
val trackActions: StateFlow<TrackActions?>
fun showTrackActions(track: Track);  fun closeTrackActions()
fun likeOrUnlikePicked();  fun dislikePicked()
```

`trackActions` — открытый лист, `null` — закрыт. `showTrackActions(track)` захватывает трек строки и оба флага в момент долгого нажатия: `liked` — по `library.likedIds` (тот же набор, что у ♥ плеера и у уведомления), `isCurrent` — `track.id == ui.value.current?.id` (тот же трек, что подсвечен в плейлисте и показан на экране плеера). Дальше лист из очереди ничего не перечитывает: под открытым листом строки могут сдвинуться (догрузка волны, правки джема), а действие должно уйти тому треку, что показан. `likeOrUnlikePicked()` — `queue.setLiked(track, !liked)`: шлёт состояние, которое видел пользователь, а не переключение, как `setLiked(key, !liked)` на десктопе. `dislikePicked()` — `queue.dislike(track)`: очередь заново решает в момент действия, текущий ли трек. Оба сначала закрывают лист, а при закрытом листе ничего не делают, поэтому второй тап по строке листа второго запроса не шлёт.

**Traps:**
- `AppViewModel` и `PlayerViewModel` — `AndroidViewModel` с областью Activity; `PlayerViewModel.onCleared` освобождает контроллер. `SpotifyViewModel` — простой `ViewModel` с фабрикой (ниже, «Spotify»).
- `finishLogin()` сначала забирает `pendingLink` и обнуляет `loginJob`, и только потом зовёт `go()`. Иначе `go()`, уходя с `LOGIN`, отменила бы сам удачный вход (он идёт в `loginJob`) и забыла бы ссылку, которую после входа надо открыть (GUEST-01).
- «Открыть в браузере» на входе по ссылке: у `ACTION_VIEW` стоит selector `ACTION_VIEW` на `https://` (без хоста) с `CATEGORY_BROWSABLE`. Адрес без хоста подходит только браузерам, а проверенная App Link (`docs/release.md`) иначе вернула бы ссылку в приложение. Без браузера (`ActivityNotFoundException`) — тост `login_no_browser`: обычный `ACTION_VIEW` открыл бы то же приложение.
- `PlayerUi.durationMs` берёт длительность из `MediaItem.extras`, пока ExoPlayer её не знает.
- `refresh` на каждое событие плеера строит `PlayerUi` заново (`PlayerUi(...)`, не `copy`), а тикер каждые 250 мс переписывает `positionMs`. Поле, которое не приходит из плеера, первое же событие сбросит в значение по умолчанию. Поэтому чисто экранное состояние (перетаскивание полосы и подобное) в `PlayerUi` не хранится никогда: оно живёт в `remember` экрана. Когда предпросмотр перетаскивания писался в `positionMs`, подпись времени мигала между пальцем и плеером (#90).
- `LoginStatus.Failed(error)` и `ListState.Failed(error)` хранят `yandex/ErrorKind`, а не текст; на экране — `error.render()` заглавными. Исключение пишется в logcat (`Log.w(LOG_TAG, …)`) там же, где поймано: `AppViewModel.kindOf`, отказ подключения `MediaController` в `PlayerViewModel`. Тост «сервис недоступен» текста исключения не показывает.
- `sessionState` меняется с `Dispatchers.Default` (`AppGraph.applicationScope`); коллектор во view model работает на главном потоке, поэтому `queue.clear()` там безопасен.

`AppRoot` при `SessionState.Offline` показывает над экраном строку `session_offline` («НЕТ СЕТИ · ПОВТОРЮ САМ») с красным `LedDot`; на экранах входа (`Screen.isLogin`: в Яндекс и в Spotify) строки нет, как и вкладок, мини-плеера и диалога «продолжить джем». Повторы идут сами, кнопки «повторить» нет.

**«Назад».** `Screen.backTarget`: с вкладок EQ, Плейлист и Библиотека — на вкладку «Плеер» (`AppRoot`, `BackHandler`), с комнаты гостя (`JAM_GUEST`) — тоже на «Плеер», с «Добавить треки» гостя (`JAM_GUEST_ADD`) — в комнату, а открытый там плейлист сначала закрывается; с экрана входа — туда, откуда вход открыли (`AppViewModel.leaveLogin()`: Library или экран джема; вход можно отложить, #115, а `go()` при этом отменяет вход; `LOGIN.backTarget` = Library только включает обработчик), со входа в Spotify — в Library (`go(SPOTIFY_LOGIN.backTarget)`, а уход с экрана отменяет оба способа входа, ниже, «Spotify»), с «Плеера» — системное поведение, то есть выход из приложения. Обработчики, объявленные позже, важнее: открытый раздел библиотеки сначала закрывается (`LibrarySectionScreen`), открытый лист пресетов EQ — тоже (`closePresets`), лист действий с треком — тоже (`closeTrackActions`, объявлен после пресетов), и только потом срабатывает переход на «Плеер».

`PlaylistScreen` показывает внизу списка строку `playlist_loading_more`, пока `QueueState.loadingMore`, а не всё время, пока играет волна. Когда это так, решает очередь (`queue/README.md`, «Ждёт продолжения»): у своей волны — пока идёт догрузка, у волны по истории — только пока очередь ждёт её треков после конца головы; тихий запрос за два трека до конца головы строку не показывает (CONT-04: без статуса).

**Продолжать волной** (CONT-01, CONT-02, `spec/player/continuation.md`). Два переключателя `OnOffPills`:
- **Плейлист** — строка под шапкой: `vibe_continue_title` и «вкл / выкл» по `QueueState.continueWithVibe`. Есть, только когда у очереди нет своего продолжения (не «Моя волна», не станция) и она не пуста или идёт джем: `!isWave && (tracks.isNotEmpty() || phase == ACTIVE)`. Во время джема это флаг базы, и пустой тоже (HOST-14, D15): когда кончится часть джема, он решает, пойдёт ли волна джема. Переключение — `PlayerViewModel.setQueueContinuesWithVibe(on)` → `queue.setContinueWithVibe(on)` и тост по флагу после команды (`continueNotice`, `QueueNotices.kt`): `vibe_continue_on` («за этой очередью пойдёт волна»), у очереди Spotify — `queue_autoplay_on` («за этой очередью пойдёт автоплей Spotify», SPOT-20), или `vibe_continue_off` («эта очередь остановится в конце»). У выбора Spotify `waveFromHistory` = `true` (`queue/README.md`, «Своё продолжение»), поэтому пилюли активны. Чьё продолжение у очереди, `QueueState` не говорит; `fromSpotify` решает по первой записи базы (`HEAD` или `CONTINUATION`, элементы джема `ITEM` и `OTHER` не в счёт): выборы Spotify дают только треки Spotify, Яндекса и свои файлы — только свои. Меняет только эту очередь, не настройку. У очереди своих файлов (`QueueState.waveFromHistory = false`, CONT-12) пилюли неактивны: флаг выключен и не переключается.
- **Библиотека** — блок над «Качеством»: подпись `vibe_continue_title`, строка «Новые очереди» (`vibe_continue_new_queues`) с «вкл / выкл» по `Settings.continueWithVibe` и пояснение `vibe_continue_note`. Выбор — `AppViewModel.setContinueWithVibe` → `Settings.setContinueWithVibe` (ключ `playback/continueWithVibe`, по умолчанию вкл, `data/README.md`); действует с новой очереди: очередь берёт значение при создании и при `clear()` через лямбду `continueWithVibeByDefault` из `AppGraph`. На десктопе эта настройка есть только в `settings.ini`; у Android нет файла, который пользователь может править, поэтому без экрана умолчание было бы не изменить.

**Случайный порядок** (SHUF-09, SHUF-10, `spec/player/shuffle.md`). Блок библиотеки над «Продолжать волной», в порядке главного меню десктопа («Шафл», «Продолжать волной», «Качество»): подпись `shuffle_title`, строка без подписи слева с двумя `PillToggle` у правого края, как у остальных рядов пилюль библиотеки, — `shuffle_without_repeats` и `shuffle_random_track` (названия — из переводов десктопа) — и пояснение `shuffle_note`. Подписи слева нет, потому что не помещается: моноширинный 11 sp с разрядкой — около 8 dp на знак, «ВЫПАДКОВЫ ТРЭК» и «БЕЗ ПАЎТОРАЎ» с полями пилюль — около 266 dp из 328 dp ширины телефона в 360 dp, по-английски около 275 dp. Ряд — тот же `ChoiceRow`, что у качества звука (`label = null`). В `ChoiceRow` пилюли стоят в `FlowRow` с `Arrangement.spacedBy(8.dp, Alignment.End)`: с подписью подпись берёт остаток ширины (`weight(1f)`), а пилюли — сколько им нужно; без подписи `FlowRow` сам берёт всю ширину (`weight(1f)`) и прижимает пилюли вправо. **При крупном шрифте** пилюли переносятся, а не обрезаются: при масштабе 1.3 ряду шафла нужно около 330 dp (be, ru) и 340 dp (en), больше 328 dp, и вторая пилюля уходит на вторую строку под первую (8 dp между строками), тоже к правому краю; ряд становится выше, пилюли — нет. Пока пилюли стояли прямо в `Row`, второй доставался остаток ширины: её текст переносился и обрезался высотой пилюли 32 dp. Ряды качества выглядят как прежде: пока пилюли помещаются в строку, сжимается подпись, а не они. `FlowRow` без `overflow` в foundation-layout 1.12.1 (BOM 2026.09.00) стабилен, `@OptIn` не нужен. Выбор — `AppViewModel.setShuffleAlgorithm` → `Settings.setShuffleAlgorithm` (ключ `shuffle/algorithm`, по умолчанию «без повторов», `data/README.md`); дальше его отдаёт очереди `AppGraph` (`queue/README.md`, «Алгоритм shuffle»). Кнопка SHF экрана плеера и её тосты (`player_shuffle_on` / `_off`) не меняются (D9 плана Kickoman/QiYaa-android#99): выбор алгоритма shuffle не включает, а `shuffle_note` говорит, что он действует, пока горит SHF (`player_shuffle` — `translatable="false"`, поэтому «SHF» в пояснении одинаков на всех языках).

**Свои файлы** (LOCAL-10, LOCAL-11, Kickoman/QiYaa-android#111). Раздел `library_local` в Library под Яндексом, со входом и без (#115):
- **Разрешение** (`READ_MEDIA_AUDIO`, до API 33 — `READ_EXTERNAL_STORAGE`, `AppViewModel.localPermission`). Что показывать, решает `LocalAccessRule` по трём признакам: дано ли разрешение, спрашивали ли раньше (`Settings.localPermissionAsked`, ключ `local/permissionAsked`) и `shouldShowRequestPermissionRationale` после ответа:
  - `ASK` — ещё не спрашивали: строка `local_allow` и пояснение `local_allow_note`; тап — системный запрос (`rememberLauncherForActivityResult(RequestPermission)`). Сам по себе запрос не всплывает: Library — первый экран без входа, и запрос на старте мешал бы тому, кто слушает только Яндекс;
  - `ASK_AGAIN` — отказ: та же строка и `local_allow_again_note`. Первый отказ без пояснения (на API 30+ так выглядит и закрытый без ответа диалог) — тоже `ASK_AGAIN`: «навсегда» система ставит только после второго;
  - `SETTINGS` — отказ навсегда (пояснения нет, а спрашивали уже до этого ответа): строка `local_settings` открывает `ACTION_APPLICATION_DETAILS_SETTINGS`, пояснение `local_settings_note`. На возврате `refreshLocal()` (`LifecycleResumeEffect`) видит выданное в настройках разрешение; без него `SETTINGS` держится, пока жива `AppViewModel`. После перезапуска отказ навсегда снова выглядит как `ASK_AGAIN`: система отличает его только по ответу на запрос, так что первый тап диалога не показывает, а сразу переводит строку в `SETTINGS`;
  - `GRANTED` — четыре строки: `local_all` (число треков; тап играет все), `local_albums`, `local_artists`, `local_folders` (число выборов; тап открывает раздел). Пока медиатека читается — `library_loading`, ошибка — `library_row_error`.
- **Чтение.** `localRows` — `ListLoader` над `LocalLibrary.rows()` на IO; `localPicks` — `LocalPicks` из загруженных строк на `Dispatchers.Default` (сортировка, `local/README.md`). Перечитывает только изменённую медиатеку: `refreshLocal()` на каждом возврате к экрану зовёт `load(force = localLibrary.takeChanged())`, а `takeChanged` — флаг `ContentObserver` на `MediaStore.Audio`; иначе каждый возврат в приложение перечитывал бы 10 000 строк и мигал бы «LOADING…».
- **Разрешение выдано** (диалогом, в настройках или ещё до старта) — `onLocalGranted()`: `GRANTED`, чтение и сверка восстановленной очереди (`AppGraph.checkRestoredLocalQueue`, LOCAL-14). `AppGraph` делает сверку один раз за процесс, как только разрешение есть: на старте или позже, когда его выдали, в том числе в новой `AppViewModel` после закрытой активности.
- **Разделы** `LocalSectionScreen`: `LibrarySection.LOCAL_ALBUMS`, `LOCAL_ARTISTS`, `LOCAL_FOLDERS` (`isLocal`, в `AppRoot` вместо `LibrarySectionScreen`, у которой для них пустая ветка), шапка как у разделов Яндекса, `LazyColumn` строк «имя · число треков», тап играет выбор. Пока строки прочитаны, а `localPicks` ещё сортируется, раздел показывает `library_loading`, а не «пусто». Длинный путь папки обрезается с начала (`TextOverflow.StartEllipsis`): различается обычно его конец. Исполнитель без имени — `local_unknown_artist`, альбом без названия — `local_unknown_album`, корень тома — `local_root`, папка не основного тома — с именем тома впереди. Пустой раздел — `local_empty`.
- **Игра** — `playLocal(pick, title)`: `queue.loadSource(title, sourceId, waveFromHistory = false, limit = 10_000)` (CONT-12, LOCAL-12) и экран плеера. `sourceId`: `local:all`, `local:album:<ALBUM_ID>`, `local:artist:<имя>` (исполнитель — строка `ARTIST`: id у медиатеки свой на каждое написание, а группирует `LocalPicks` по имени), `local:folder:<том>/<путь>`. Название очереди — подпись строки (`local_all`, имя альбома, исполнителя или папки).

Отметки на треках волны в плейлисте нет: на десктопе отмечена только волна джема (HOST-34).

**Текст песни** (LYR-01, LYR-06…08, LYR-13, LYR-15, Kickoman/QiYaa-android#112). Текст встаёт на место обложки на экране плеера (решение владельца, issuecomment-6384992076); искатель — `lyrics/LyricsFinder` в `AppGraph`, состояние — его `view`.
- **Тап по обложке** (вне полосы спектра: прозрачный слой над всем квадратом, кроме нижних 64 dp, `lyrics_show` для TalkBack) — `PlayerViewModel.showLyrics(true)`. Переключатель один — `AppGraph.lyricsShown`: переживает смену вкладки и пересоздание активности, не переживает новый процесс (тогда снова обложка). Телеметрия слушает его же (`feature lyrics`).
- **«Открыт»** (LYR-01) — `lyricsShown` и `PlayerScreen` на экране не ниже `STARTED` (статус `IDLE` при текущем треке — пустой, а не «ничего не играет»: `lyricsOnScreen` встаёт только после первой композиции, и на кадр вид ещё `IDLE`): `LifecycleStartEffect` пишет `AppGraph.lyricsOnScreen`, а искатель получает `lyricsShown && lyricsOnScreen`. Другая вкладка (экран уходит из композиции), фон и выключенный экран — закрыт: ничего не спрашивается, текущий поиск засчитывается.
- **Текущий трек** искателю отдаёт `PlayerViewModel.refresh` (`AppGraph.lyricsTrack = ui.current`): у него источник из `mediaId` (#111), искатель сравнивает `TrackKey`.
- **`LyricsPanel`** — над полосой спектра (нижние 64 dp квадрата остаются ей), фон `colors.deep` вместо обложки:
  - синхронный текст — `LazyColumn`, текущая строка (последняя с временем не позже позиции, `currentLine`; только если ключ трека вида совпадает с `ui.current`) — акцентом, серединой строки посередине (`animateScrollToItem` со сдвигом на половину высоты окна без высоты строки, если она уже видна); до первой строки — список в начало; пустая строка — «♪» (LYR-12);
  - новый текст (другой трек, текст из памяти, лучший текст на смену прежнему) — с чистого листа: ветки стоят под `key(трек, текст)`, так что прокрутка и слежение прежнего текста не переходят к новому (обычный — с начала, LYR-08), как `lyricsChanged` десктопа;
  - тап по строке — `PlayerViewModel.seekTo(ms)` (как `seekToFraction`: `controller.seekTo` и сразу `positionMs`; хозяин джема шлёт `playing`, как после ползунка, HOST-06). В `IDLE` (после ERR-05/07/08 и пока трек ждёт сеть, ERR-01…03: повтор зовёт `prepare()` только после ожидания) и `ENDED` тап ничего не делает, после «стопа» (пауза в 0) — как на паузе (LYR-07). Текущая строка помечена `selected` для TalkBack;
  - прокрутка пользователя (LYR-08) — по `DragInteraction` списка, а не по `isScrollInProgress` (его включает и своя прокрутка): слежение снова через 5 с после конца жеста и инерции. Весь экран — `verticalScroll`, остаток прокрутки списка уходит ему;
  - обычный текст — колонка с прокруткой с начала, тап по ней — обратно к обложке. «Тап в любом месте» обычного текста и статусов — `pointerInput { detectTapGestures }`, а не `tap`: `clickable` сливает семантику детей, и TalkBack читал бы весь текст одним куском; доступная кнопка назад — подвал;
  - статусы `lyrics_nothing`, `lyrics_loading`, `lyrics_missing`, `lyrics_instrumental`, `lyrics_failed`, `lyrics_no_account` — тап в любом месте ведёт обратно; когда текста нет и LRCLIB выключен — кнопка `lyrics_search_lrclib` (LYR-15 на телефоне: настройка Library — другая вкладка, а там текст по определению закрыт);
  - подвал — откуда текст (`lyrics_from_yandex` / `_lrc` / `_tags` / `_lrclib`; у `LyricsFrom.CATALOG` подпись выбирает `when (track.source)` без `else`: `lyrics_from_yandex` у Яндекса и когда трека нет, у своего файла и трека Spotify подписи нет) и `lyrics_better` («ещё ищу…»), пока лучший текст ещё может прийти, со значком обложки (`IconPaths.COVER`) справа; вся строка не ниже 48 dp — кнопка «назад к обложке» (`lyrics_back_to_cover`), с синхронного текста только так (решение владельца, issuecomment-6387858946).
- **LRCLIB.** Блок `lyrics_title` в Library над «Качеством»: «Тексты из LRCLIB» (`OnOffPills` по `Settings.lyricsThirdParty`) и пояснение `lyrics_lrclib_note`. Включение там и «Искать в LRCLIB» на экране текста сначала показывают `LrclibConsentDialog` (`Dialog` Compose, что уходит в LRCLIB — `lyrics_lrclib_consent`); «Отмена» ничего не меняет. Выключить можно только в Library, и на тексте это действует, когда его откроют снова (оговорка Android в LYR-15).
- **Джем** (LYR-13): у хозяина — текст его текущего трека, гостям ничего не уходит; в комнате гостя текста нет, а вкладка «Плеер» со своей очередью работает как обычно (GUEST-13).

## Spotify

План — Kickoman/QiYaa-android#113 (PR 5), поведение — `spec/player/spotify.md` (SPOT-01…12, SPOT-19, SPOT-20), клиент — `spotify/README.md`. Как на ПК (`src/ui/library_menu.cpp`, `src/ui/spotify_login_dialog.cpp`), только меню ПК здесь — раздел Library, а диалог входа — экран.

```kotlin
interface SpotifyGateway {   // что view model берёт из графа; в приложении — приватный GraphSpotify над AppGraph
    val state: StateFlow<SpotifyState>;  val onScreen: StateFlow<Boolean>;  val username: String;  val likedSongsUri: String   // onScreen — ScreenPresence.onScreen
    fun hasSavedLogin(): Boolean;  fun login(accessToken: String);  fun logout()
    suspend fun requestCode(): PairLogin.Code;  suspend fun waitForToken(code: PairLogin.Code): String
    suspend fun loginInBrowser(pages: BrowserLogin.Pages, openPage: (String) -> Unit): String
    suspend fun likedTracks() / forYou() / playlists() / savedAlbums() / followedArtists() / playlistTracks(uri) / albumTracks(uri) / artistTopTracks(uri)
    fun autoplay(contextUri: String): Continuation;  fun station(trackUri: String): Continuation
    fun loggedIn(way: SpotifyLoginWay);  fun loggedOut()   // телеметрия TEL-18
}
class SpotifyViewModel(spotify: SpotifyGateway, queue: QueueController, strings: (Int, List<Any>) -> String,
                       io: CoroutineContext = Dispatchers.IO, log: (String, Throwable) -> Unit = { _, _ -> }) : ViewModel() {
    val ui: StateFlow<SpotifyUi>               // access, canLogOut, username
    val login: StateFlow<SpotifyLoginUi>       // userCode, pairUrl, code: SpotifyCodeStatus, browser: SpotifyBrowserStatus
    val notices: Flow<String>                  // тосты → AppViewModel.say (канал с буфером)
    val moves: Flow<Screen>                    // переходы → AppViewModel.go
    val browserPages: Flow<String>             // страница входа, которую экран открывает в браузере
    val queueState: StateFlow<QueueState>
    val forYou, playlists, albums, artists: ListLoader<SpotifyItem>;  fun list(which: SpotifyList): ListLoader<SpotifyItem>
    fun load(which: SpotifyList);  fun loadLists()                                  // только с LOGGED_IN
    fun openLogin(returnTo: Screen = Screen.LIBRARY);  fun leaveLogin();  fun cancelLogin();  fun startCodeLogin();  fun startBrowserLogin();  fun noBrowser()
    fun logout();  fun say(message: String)
    fun playLiked();  fun play(which: SpotifyList, item: SpotifyItem)
    fun playRadio(track: Track)
    companion object { const val LIKED_SONGS = "spotify:liked"; const val RADIO_PREFIX = "spotify:radio:"; val Factory: ViewModelProvider.Factory }
}
```

**Своя view model.** `AppViewModel` и так больше 500 строк, поэтому Spotify живёт отдельно. `SpotifyViewModel` — простой `ViewModel`, а не `AndroidViewModel`: зависимости приходят в конструктор, `Factory` (`viewModelFactory`, `APPLICATION_KEY`) собирает их из `AppGraph` — шлюз `GraphSpotify` над `spotifyAccount`, `spotifyLibrary`, `spotifyPairLogin`, `spotifyBrowserLogin`, `spotifyContinuations`, `spotifySearch`, `screen.onScreen` и `telemetry`, строки — `Application.getString`, лог — `Log.w`. Поэтому `SpotifyViewModelTest` проверяет её на JVM: фальшивый шлюз, настоящая очередь из `support/QueueHarness`, `Dispatchers.setMain`. `AppRoot` создаёт её через `viewModel(factory = SpotifyViewModel.Factory)` и перекладывает `notices` в `AppViewModel.say`, а `moves` — в `AppViewModel.go`.

**Состояние входа** — `spotifyAccessOf(state, savedLogin)` по `SpotifyAccount.state` и сохранённому входу:

| `SpotifyState` | `SpotifyAccess` | Раздел Library |
|---|---|---|
| `LoggedOut` | `LOG_IN` | «Войти в Spotify» и `spotify_premium_note` («только Premium») |
| `Connecting`, `Offline` с сохранённым входом | `LOGGED_IN` | выборы: login5 работает с сохранёнными данными, пока AP подключается (`spotify/README.md`, «`SpotifyWeb`») |
| `Connecting`, `Offline` без него (только что получен токен) | `CONNECTING` | «Подключаюсь к Spotify…»: до `APWelcome` многоразовых данных нет, и списки упали бы `NotLoggedInException` |
| `Online` | `LOGGED_IN` | выборы |
| `Expired` | `EXPIRED` | «Войти заново» и красное `spotify_expired` (SPOT-04) |
| `PremiumRequired` | `PREMIUM_REQUIRED` | «Войти в Spotify» (другим аккаунтом), красное `spotify_premium_required` и `spotify_premium_note` (SPOT-05) |
| `Refused(code)` | `REFUSED` | «Войти заново» и красное `error_spotify_login_refused` |

Справа от подписи SPOTIFY — «<имя> · ВЫЙТИ», когда есть что выходить: `canLogOut = spotifyCanLogOut(access, savedLogin)`: `LOGGED_IN` — да, `LOG_IN` и `EXPIRED` — нет, `CONNECTING`, `PREMIUM_REQUIRED`, `REFUSED` — по сохранённому входу. `LOG_IN` — нет при любом `savedLogin`: `SpotifyState.LoggedOut` ставит `session.logout()`, а сборщик состояния на `Main.immediate` видит её внутри этого присваивания — с главного потока раньше, чем `SpotifyAccount` успел бы забыть вход, если бы забывал после (`spotify/README.md`, «`SpotifyAccount`»); иначе после «ВЫЙТИ» шапка так и показывала бы «ВЫЙТИ» (ревью #141). Поэтому при `PremiumRequired` и другом отказе сохранённый вход можно забыть (такой отказ его не удаляет), а свежий токен, которому AP отказала, ничего не сохранил, и там только «ВОЙТИ»: выход без входа прислал бы `feature spotify logout` и «Вы вышли» за вход, которого не было. Так же у ПК: «Выйти» в меню — только при `loggedIn()`. Пока свежий токен подключается (`CONNECTING`), тоже «ВОЙТИ». Подпись SPOTIFY — `weight(1f)` и одна строка; имя — свой текст с `weight(1f, fill = false)`, одной строкой и многоточием; «· ВЫЙТИ» — третий текст без веса, он не сжимается. Имя — из `Online`, до него — из данных входа сессии (`SpotifyLibrary.username`); у аккаунтов, заведённых по почте, это случайный id из 28 знаков, он и обрезается (360 dp, масштаб шрифта 1,3: подписи 72,8 dp из ~115–125, имени ~115–125 dp, `widths.py` ревью #141 по `ibm_plex_mono_medium.ttf`). Имени нет — только «ВЫЙТИ». Тап по имени и по «ВЫЙТИ» — `logout()`.

Тосты по состоянию идут со второго увиденного значения: `Expired` — `spotify_expired`, `PremiumRequired` — `spotify_premium_required`, `Refused` — `error_spotify_login_refused`. Первое значение (вход истёк ещё до создания view model) раздел показывает без тоста: иначе тост повторялся бы при каждом новом создании view model. Эти тосты и `spotify_logged_in` ждут экрана (`noticeOnScreen`: `onScreen.first { it }`): вход в браузере обычно кончается, пока QiYaa в фоне, а `AppViewModel.say` гасит тост через 1,8 с, видно приложение или нет. Ждёт только последний: новый такой тост отменяет прежний. `spotify_connecting` и тосты действий (выход, «нет браузера», загрузка) идут сразу. Списки сбрасываются, как только доступ перестаёт быть `LOGGED_IN`; при переходе в `Online` упавшие списки (`Failed`, например без сети при сохранённом входе) запрашиваются снова — как `loadLibraryLists()` Яндекса на `Online`, но только упавшие: остальные загружены или ещё не нужны.

**Выход** (SPOT-06) — `logout()`: отменяет вход, если он идёт, `SpotifyAccount.logout()` (соединение закрывается, сохранённое удаляется), `feature spotify logout`, тост `spotify_logged_out`. Очередь не трогается: треки Spotify в ней падают, когда им нужен файл, как ERR-08 (`playback/README.md`).

**Экран входа** (`Screen.SPOTIFY_LOGIN`, SPOT-01, SPOT-02). `openLogin()` из Library сразу запрашивает код и переходит на экран; `backTarget` — Library. Сверху — бренд и «ПОЗЖЕ», заголовок `spotify_login_title` и `spotify_premium_note`; дальше два способа, браузер первым — на телефоне это обычный путь.
- **В браузере** — `startBrowserLogin()` → `BrowserLogin.login(pages, openPage)`. Адрес страницы `authorize` приходит в `browserPages` (канал `CONFLATED`: адрес дождётся экрана, если тот как раз пересоздаётся), экран открывает его через `openInBrowser` (`LoginScreen.kt`: `ACTION_VIEW` только браузеру). Браузера нет — `noBrowser()`: вход в браузере отменяется, тост `login_no_browser`. Тексты, которые порт `127.0.0.1` отдаёт браузеру, — `spotify_page_logging_in` и `spotify_page_refused` (`BrowserLogin.Pages`): обе просят вернуться в приложение — вернуть его на экран из фона Android не даёт (`spotify/README.md`, «`BrowserLogin`»). Статус под кнопкой: «Жду браузер…» с мигающим LED, отказ — «Войти в браузере не удалось: <вид>».
- **Кодом** — `user_code` крупно, «Открыть spotify.com/pair» (`verification_uri_complete`), «Скопировать ссылку» (`LocalClipboard`, тост `jam_copied`) и QR той же ссылки (`QrCodeImage`, 60 % ширины) — для входа с другого устройства. Кнопки и QR появляются, когда код получен; каждая кнопка — во всю ширину своей строкой, как на входе в Яндекс: в один ряд по-белорусски и по-русски от подписей оставалось первое слово. Самая длинная подпись — «АДКРЫЦЬ SPOTIFY.COM/PAIR» и «ОТКРЫТЬ SPOTIFY.COM/PAIR», 252,7 dp при масштабе 1,3 из 320 dp на телефоне в 360 dp (у остальных подписей экрана — не больше 228,8). Статус: «Получаю код…», «Жду подтверждения…», отказ — «Вход по коду не удался: <вид> · нажмите на код»; тап по коду — `startCodeLogin()`, и тогда у квадрата кода роль кнопки и описание `spotify_login_new_code` для TalkBack.
- Вид ошибки — корневой `spotifyLoginErrorKindOf` (`spotify/README.md`, «Вид ошибки для статуса»): `AuthorizationException` с `expired_token` — `CodeExpired` («код устарел»), любой другой отказ OAuth (`access_denied`, `state_mismatch`, `no_code`) — `SpotifyLoginRefused`, остальное — как у списков.
- Токен любым путём: другой путь отменяется, `SpotifyAccount.login(token)`, тост `spotify_connecting`, переход в Library. Когда сессия станет `Online`, — тост `spotify_logged_in` и `feature spotify login-code` / `login-browser` (TEL-18), один раз на вход, как `loggedInWith` ПК; отказ AP до `Online` способ забывает, и вход не считается.
- «ПОЗЖЕ» — `leaveLogin()`, «назад» — `go(LIBRARY)`. Любой уход с экрана отменяет оба способа: `AppRoot` на каждой смене экрана зовёт `cancelLogin()`, если экран не `SPOTIFY_LOGIN`, — порт `127.0.0.1` закрывается, опрос кода прекращается. Поворот экрана экран не меняет и вход не отменяет.

**Раздел Library «Spotify»** (`SpotifySources`) — под «Яндекс Музыкой», со входом в Яндекс и без: своё поле поиска (`spotify_search_hint`): Enter открывает экран поиска с чипом Spotify (ниже, «Поиск»), «Liked Songs», «Для вас», «Плейлисты», «Альбомы», «Исполнители» с числом или статусом списка (`listMeta`, как у Яндекса). Списки грузятся, когда раздел виден с `LOGGED_IN` (`LaunchedEffect(access)` → `loadLists()`), и при открытии своего экрана (`load(list)`); у `ListLoader` — корневой `errorKindOf`, поэтому HTTP Spotify — «ошибка сервера N», а не «что-то пошло не так». Разделы — `LibrarySection.SPOTIFY_FOR_YOU`, `SPOTIFY_PLAYLISTS`, `SPOTIFY_ALBUMS`, `SPOTIFY_ARTISTS`; `spotifyList` переводит их в `SpotifyList`, и `AppRoot` открывает `SpotifySectionScreen`: «‹ БИБЛИОТЕКА / SPOTIFY · ПЛЕЙЛИСТЫ» и число справа, `LazyColumn` строк с именем (играющий — акцентом, по `activeSourceId`), «Загрузка…», ошибка с «нажмите, чтобы повторить» или «Пусто». Доступ ушёл из `LOGGED_IN`, пока раздел открыт (сохранённый вход отвергнут при переподключении — `Expired`): списки сброшены, `load()` без входа ничего не делает, и раздел сам закрывается (`LaunchedEffect(ui.access)` → `onClose`), а Library показывает новое состояние; иначе он вечно показывал бы «Загрузка…». Числа треков у строк нет: `SpotifyItem` — только URI и имя, как в меню ПК. `AppViewModel.loadSection` у этих разделов ничего не делает: их списки — у `SpotifyViewModel`.

**Выборы** — `queue.loadSource(title, sourceId, continuation = spotify.autoplay(контекст)) { треки }` и переход на «Плеер»:

| Выбор | Название | `sourceId` | Контекст автоплея (SPOT-20) | Треки |
|---|---|---|---|---|
| Liked Songs (SPOT-07) | `spotify_liked_songs` | `spotify:liked` | `SpotifyLibrary.likedSongsUri` (`spotify:user:<имя>:collection`) | `likedTracks()` |
| плейлист «Для вас» или свой (SPOT-08, SPOT-10) | имя | URI плейлиста | он же | `playlistTracks(uri)` |
| альбом (SPOT-09) | имя | URI альбома | он же | `albumTracks(uri)` |
| исполнитель (SPOT-09) | имя | URI исполнителя | он же | `artistTopTracks(uri)` |

Liked Songs ещё и сразу показывает `queue_loading` («Любимые треки: загрузка…», как статус ПК: 2000 треков — это 20 запросов метаданных), а «загружено N» — обычный `SourceLoaded` очереди.

**Поиск** Spotify — общий экран поиска (ниже, «Поиск»): поле раздела Library открывает его с чипом Spotify. Своего поиска у `SpotifyViewModel` нет.

**Радио** (SPOT-12) — строка «Радио» листа действий плейлиста, только у трека Spotify (`TrackActions.radio`); текущий трек — тоже строка плейлиста. `playRadio(track)` → `queue.playContinuation("<трек>: radio", "spotify:radio:<id>", station("spotify:track:<id>"))`. Экран не меняется: новая очередь видна в том же плейлисте, статус «<трек>: радио: загрузка…» даёт очередь.

**Traps:**
- Способ входа (`pendingWay`) живёт в view model до `Online`. Если процесс убили между токеном и `Online`, `feature` этого входа не уйдёт, а вход сохранится и поднимется при следующем старте.
- `moves` — канал с буфером, а не `SharedFlow`: переход после токена приходит, когда приложение в фоне (пользователь ещё в браузере), и не должен потеряться.
- `notices` — тоже канал с буфером, а не `SharedFlow` без подписчиков: тост, отложенный до экрана (`noticeOnScreen`), уходит в `onStart` новой Activity, а `AppRoot` подписывается на него позже, когда композиция прикреплена к окну (после `onResume`). Так бывает, если Activity пересоздалась, пока пользователь был в браузере (поворот, тема, язык); `tryEmit` в `SharedFlow` его бы потерял.
- `onState` читает `hasSavedLogin()` (SharedPreferences, `data/SpotifyStore`) на главном потоке, только при смене состояния. Поэтому и `canLogOut` пересчитывается только тогда: вход сохраняется на `APWelcome`, к `Online` он уже есть.

## Поиск

```kotlin
data class FoundPick(kind: Kind /* ARTIST, ALBUM */, id: String, name: String, artists: List<String> = emptyList(), year: Int? = null, single: Boolean = false)   // id — id Яндекса или URI Spotify
data class Found(bestTrack: Track? = null, bestArtist: FoundPick? = null, bestAlbum: FoundPick? = null, tracks: List<Track>, artists: List<FoundPick>, albums: List<FoundPick>) {
    val isEmpty: Boolean          // показывать нечего: SRC-19
    fun shown(): Found            // недоступные треки прочь, лучший результат один раз
    fun playable(): List<Track>   // лучший трек, затем треки: очередь SRC-18
    companion object { fun of(result: yandex.SearchResult): Found; fun of(found: SpotifyFound, tracks: List<Track>): Found }   // оба — уже shown()
}
```

Что показывает поиск (`spec/player/sources.md`, «Search», SRC-13; Spotify — SPOT-11), как `Core::SearchResults` и `Core::Shown` ПК. Лучший результат — исполнитель или альбом с непустым id или доступный трек; лучший плейлист, подкаст и что угодно другое не показываются. Лучший альбом берёт исполнителей, год и «сингл» из своей строки в альбомах ответа, если она там есть. Части оставляют без записи с id лучшего результата, треки — без недоступных и без трека с ключом лучшего. У Spotify лучший трек — найденный трек с URI лучшего (`SpotifyTracks.uriOf`); если его метаданных нет, лучшего трека нет, а остальные треки остаются.

Экран поиска (`spec/player/sources.md`, «Search», SRC-13…19; Spotify — SPOT-11) и добавление в очередь (`spec/player/queue.md`), как окно SEARCH ПК (`src/ui/search_window.cpp`), только по-телефонному: свой экран `Screen.SEARCH` с чипами «Яндекс Музыка» и «Spotify», тап по треку играет, «+» открывает лист «Играть следом / В конец очереди» (решения владельца, Kickoman/QiYaa-android#144).

```kotlin
interface SearchGateway {   // в приложении — приватный GraphSearch над AppGraph
    fun canSearch(source: SearchSource): Boolean   // Яндекс — SessionState.signedIn; Spotify — spotifyAccessOf(…) == LOGGED_IN
    suspend fun find(source: SearchSource, text: String): Found
    suspend fun tracksOf(source: SearchSource, pick: FoundPick): List<Track>   // исполнитель — artistTopTracks, альбом — albumTracks
    fun autoplay(contextUri: String): Continuation   // автоплей Spotify (SPOT-20)
}
class SearchViewModel(gateway: SearchGateway, queue: QueueController, strings: (Int, List<Any>) -> String,
                      io: CoroutineContext = Dispatchers.IO, log: (String, Throwable) -> Unit = { _, _ -> }) : ViewModel() {
    val ui: StateFlow<SearchUi>
    fun open(text: String, source: SearchSource);  fun setText(text: String);  fun setSource(source: SearchSource)
    fun search();  fun retry();  fun toggle(part: SearchPart)
    fun openPick(pick: FoundPick);  fun reopen();  fun back(): Boolean   // back: false — открытого нет
    fun play(track: Track);  fun playAll();  fun playPick(pick: FoundPick)
    fun chooseAdd(target: SearchTarget);  fun closeAdd();  fun add(placement: AddPlacement)
    companion object { val Factory: ViewModelProvider.Factory }
}
```

- **Поиск** (SRC-13, SRC-14). `search()` обрезает текст; пустой ничего не шлёт. Источник, который искать не может (`canSearch`), — фаза `Idle` без запроса. Экран показывает вход: у Яндекса «Войти…» (`openLogin(returnTo = SEARCH)`), у Spotify — те же строки состояния, что в Library (`SpotifyAccessRows`: вход, «Подключаюсь…», истёк, нужен Premium, отказ), и вход возвращает на экран поиска (`SpotifyViewModel.openLogin(returnTo = SEARCH)`). Когда вход появился, экран повторяет поиск сам. Иначе `Searching`, потом `Results(found)`, `NothingFound` (`found.isEmpty`, SRC-19) или `Failed(вид)` с «Повторить» (SRC-15, без тоста). Новый поиск побеждает: счётчик запросов, старый `Job` отменяется, его ответ отбрасывается; он же закрывает открытого исполнителя или альбом и сбрасывает развёрнутые части и метки. Смена чипа ищет тот же текст в другом источнике. Поиск не источник: запрос очереди он не отменяет, и тот его — тоже.
- **Части** (SRC-13): «Лучшее», «Треки · N» (первые 6), «Исполнители · N», «Альбомы · N» (по 3, `SearchPart.first`), «Все» / «Меньше» — `toggle`. Трек — номер, название, исполнители и длительность, «+»; текущий трек очереди (`PlayerUi.current`, по ключу) — цветом акцента. Исполнитель — имя, «исполнитель», ▶; альбом — название, «исполнители · год · сингл», ▶ и «+».
- **Игра** (SRC-18): тап по треку — `queue.playTracks("Search: <текст>", found.playable(), его индекс)`; у Spotify — с автоплеем от первого трека этой очереди (SPOT-20). ▶ у исполнителя или альбома — `queue.loadSource` под его именем с `sourceId` как в Library (`artist:<id>`, `album:<id>`; у Spotify — URI) и его загрузчиком (SRC-09, SRC-10, SPOT-09). На экран плеера поиск не уводит: результаты остаются, трек видно в мини-плеере.
- **Открыть** (SRC-16): тап по исполнителю или альбому — `openPick`: его **доступные** треки на месте результатов («популярные треки» или «исполнители · год»), ▶ «Играть всё», у альбома «+». Новое открытие, «Назад» и новый поиск побеждают (свой счётчик). «Назад» — `back()`: результаты как были, без запроса, с той же прокруткой (состояние списка живёт в `SearchScreen` и новое только у новых результатов) и развёрнутыми частями. Тап по треку — `playTracks(имя, его треки, индекс, sourceId)`, у Spotify с автоплеем от URI исполнителя или альбома.
- **Добавить** (SRC-17, ADD-01…09): «+» — `chooseAdd(цель)`, `AppRoot` рисует `SearchAddSheet`; выбор — `add(NEXT | END)`. Трек — `queue.add([трек], название поиска или открытого, …)`, под строкой «сыграет следом» / «в конце очереди» до нового поиска (`SearchUi.added`). Альбом, открытый сейчас, — `queue.add(его треки)`; иначе `queue.addLoaded(название) { tracksOf(…) }`, без билета. Статусы («Следом: …», «В конец: …», ошибка, «пусто») — события очереди, тосты `AppViewModel`.
- **Навигация.** Поля Library (Яндекс и Spotify) держат свой текст (`LibrarySearchField`, `rememberSaveable`) и по Enter зовут `onSearch(текст, источник)`: `AppRoot` → `search.open(…)` и `AppViewModel.openSearch(returnTo = LIBRARY)`. «Назад» экрана (стрелка и системная, свой `BackHandler`) сначала закрывает открытое (`back()`), потом `leaveSearch()` — туда, откуда открыли. Вкладка Library на экране поиска подсвечена.

`GraphSearch.find` у Spotify берёт метаданные одним `tracks(Found.metadataUris(found))`: найденные треки, а лучший трек, которого среди них нет, — первым, как у ПК (`catalog.cpp`), иначе он потерялся бы.

**Traps:**
- `of` уже зовёт `shown()`: снова звать не нужно, но и не вредно.
- `isEmpty` смотрит на показанное: ответ из одних недоступных треков — «ничего не найдено».

## Джем

Экраны хозяина джема (Kickoman/QiYaa-android#62, сценарии HOST-20…35 в `spec/jam/host.md`). Логика — `playback/JamHost`; `JamViewModel` только передаёт действия и превращает `JamHostEvent` и свои отказы («нет связи», «не настроено») в тосты через `notices` → `AppViewModel.say`.

- **Вход**: строка «Джем» в библиотеке (мета — «Начать» или «Идёт · N гостей»), а во время джема — тап по названию очереди на экране плеера (оно подсвечено акцентом). Это название базы: своего названия у джема нет (D4, как на десктопе), пустая база — `player_no_queue`. `Screen.JAM` → назад на «Плеер», `Screen.JAM_SETTINGS` → назад на «Джем».
- **Хозяину нужен вход в Яндекс** (HOST-32: гости ищут через его аккаунт; на ПК «Начать…» работает и без входа, а поиск гостей отвечает `unauthorized`, HOST-29). Без входа `JamScreen` в фазе `NONE` показывает `jam_intro`, пояснение `jam_needs_login`, «Войти» (`openLogin(returnTo = JAM)`) и `JoinJamSection`; `JamViewModel.start()` сам отказывает тостом `jam_needs_login` (IME поля имени тоже зовёт `start`); «Продолжить джем?» (`JamContinueDialog`) без входа не показывается — сохранённый джем ждёт следующего входа.
- **`JamScreen`** по фазе `JamHostState.phase`: `NONE` — имя хозяина (по умолчанию имя аккаунта, ≤ 24 кодовые точки, `jam/JamNames`), «Начать джем», при пустом сервере — подсказка и кнопка настроек; `CREATING` — «Подключаюсь…» и «Отмена»; `ACTIVE` — QR `joinUrl` на 72 % ширины, ссылка, «Поделиться ссылкой» (`ACTION_SEND` через системный выбор) и «Копировать» (`LocalClipboard.setClipEntry` в корутине экрана; `ClipData.newPlainText` без метки, чтобы не заводить видимую пользователю строку), поиск треков для джема (`Library.searchTracks`, «Следом» = `playNext`, «В джем» = `add`), гости (онлайн, вид, сколько ждёт, «Убрать»), настройки комнаты (порядок, пропуск гостями, вход), «Новая ссылка», «Закончить джем» с подтверждением вторым нажатием за 3 с. Состояние связи — в шапке.
- **Без связи** (HOST-25) «В джем», «Следом», «Убрать» и «Новая ссылка» приглушены, а любое действие с комнатой даёт тост `jam_no_connection`; над любым экраном — красная строка `jam_offline_strip`, тап ведёт на экран джема. Строка «нет сети» Яндекса важнее и показывается вместо неё.
- **«Продолжить джем?»** (HOST-23) — `JamContinueDialog` поверх любого экрана, кроме входа, пока `storedSession`.
- **Плейлист** (HOST-34): метку строки даёт чистая `jamTrackNote(entry, isWave, jamOn, names)` по записи очереди (`QueueState.entries`), только пока джем идёт (`phase == ACTIVE`): у элемента этого джема (`item.itemId` есть, ждёт он или уже сыграл, текущий тоже) — `jam_added_by` («+ имя» по `item.addedBy` среди `room.participants`; незнакомому гостю — ничего); у записи `CONTINUATION` базы без своей волны (`!isWave`) — `jam_wave_mark` («Волна джема»), в том числе у треков волны по истории, пришедших до джема (D14); остальная база — голова, `OTHER`, своя волна «Моей волны» или станции, элементы прошлого джема — без метки, как вне джема. После конца джема меток нет. REM всегда зовёт `QueueController.removeIndices(selected)` (HOST-36): ждущие элементы джема очередь отправляет серверу (`remove{itemId}`, из плейлиста они уйдут со следующим `state`), остальные выделенные строки — трек базы, сыгранная строка, текущий трек — уходят сразу, а удаление текущего трека останавливает воспроизведение. Без связи REM доступен: строки базы убираются, а за элементы джема `JamHostEvent.NotConnected` даёт тост `jam_no_connection` (HOST-25). CLEAR оставляет текущий трек и часть джема и очищает базу. Тап по строке — переход: ждущие элементы переезжают за выбранную строку (HOST-12, `queue/README.md`). ADD ведёт на экран джема.
- **Выбор источника во время джема** (HOST-21): плейлист, альбом, артист, волна, станция и найденный трек (D5; база начинается с него, HOST-38) становятся новой базой, с теми же тостами, что вне джема; ничего не начинается сразу. Треки для джема ищут на экране джема (HOST-20).
- «Выйти» заканчивает джем: гости ищут через аккаунт хозяина.
- **`JamSettingsScreen`**: сервер (по умолчанию `qiyaaJamUrl`) и «Учить волну джема» (`jam/waveFeedback`, HOST-16). Пояснение `jam_wave_feedback_note` (вслед за `4dcc9ff` десктопа и словами пилюль `toggle_on` / `toggle_off`) говорит, что за пустой очередью гостей играет собственная очередь хозяина, а волна джема — после неё, если у очереди нет своей волны и она продолжается волной; учится только волна джема. Ключа хозяина нет. Строки `jam_queue_title`, `queue_jam_active` и `jam_remove_nothing` удалены (D4, HOST-21, HOST-36).

## Гость в чужом джеме

Гость QiYaa (Kickoman/QiYaa-android#63, сценарии GUEST-01…21 в `spec/jam/guest.md`). Логика — `jam/JamGuest` (`AppGraph.jamGuest`), разбор ссылки — `jam/JamLink`. Здесь входы, куда ведёт ссылка, экраны, свои поиск, лайки и плейлисты, «лайк себе» и тексты. Гость ничего не играет и не трогает ни очередь, ни плеер (GUEST-13):

```bash
grep -nE 'graph\.queue|QueueController|PlayerViewModel|MediaController' app/src/main/java/io/github/kickoman/qiyaa/ui/JamGuestViewModel.kt app/src/main/java/io/github/kickoman/qiyaa/ui/screens/JamGuestScreen.kt   # ничего не печатает
```

**Входы.**
- «Войти в джем» (`JoinJamSection`) на экране джема в фазе `NONE`, под «Начать джем»: поле (`KeyboardType.Uri`), «Вставить» читает буфер обмена (`LocalClipboard.getClipEntry`, текст первого элемента) только по нажатию (иначе Android 12+ показывает тост о чтении буфера в случайный момент), «Войти» — `AppViewModel.openJamLink(text)`; принятая ссылка очищает поле. Тот же блок — на комнате гостя без сессии (`None`).
- **App Link и «Поделиться»** (GUEST-01, GUEST-21). Манифест объявляет `MainActivity` для `ACTION_VIEW` на `https://<jamHost><jamPathPrefix>…` с `autoVerify` (плейсхолдеры из `qiyaaJamUrl`, проверка домена и шаги владельца — `docs/release.md`, «App Links») и для `ACTION_SEND` с `text/plain`, так что QiYaa есть и в системном «Поделиться» любого текста. `MainActivity` берёт текст — `dataString` у `ACTION_VIEW`, `EXTRA_TEXT` у `ACTION_SEND` — в `onCreate` (только при `savedInstanceState == null`: после смерти процесса система отдаёт старый `Intent` снова) и в `onNewIntent` (активность `singleTask`) и зовёт `app.openJamLink(text, launchedFromHistory)`, где `launchedFromHistory` — флаг `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY`: так Android помечает базовый `Intent` задачи, который он повторяет при открытии из «Недавних», и `route` такой `Intent` не обрабатывает. После обработки `setIntent` ставит простой `ACTION_MAIN`/`LAUNCHER`. `AppViewModel` в `MainActivity` — тот же экземпляр, что `viewModel()` в `AppRoot` (одно хранилище view model активности).
- Строка «Джем» библиотеки во время гостевой сессии (`GuestPhase.isSession`): мета `jam_library_guest` («Гость · <хозяин>», до первого `state` — «…»), тап — `Screen.JAM_GUEST`, а не `Screen.JAM`.

**Куда ведёт ссылка** — чистая функция в `LinkRouting.kt` (`LinkRoutingTest`):

```kotlin
data class HostSide(val roomId: String? = null, val active: Boolean = false, val storedSession: Boolean = false)
data class GuestSide(val serverUrl: String? = null, val roomId: String? = null, val phase: GuestPhase = GuestPhase.None)
enum class LinkRefusal { NOT_A_LINK, HOSTING }
sealed interface LinkAction   // Ignore, Refuse(reason), AskSignIn(link), OpenHostJam, ShowGuest, OpenGuest(link)
fun route(link: JamLink?, host: HostSide, guest: GuestSide, signedIn: Boolean, launchedFromHistory: Boolean): LinkAction
val GuestPhase.isSession: Boolean   // isActive или NeedName
```

Первая подходящая строка:

| Условие | `LinkAction` | Что делает `AppViewModel` |
|---|---|---|
| `launchedFromHistory` | `Ignore` | ничего (GUEST-21) |
| в тексте нет ссылки `https://…/j/<roomId>` (`JamLink.find` вернул `null`; `http://` тоже) | `Refuse(NOT_A_LINK)` | тост `jam_join_bad_link` |
| нет токена | `AskSignIn(link)` | ссылка ждёт в `pendingLink`; после входа — маршрут заново (GUEST-01, D8) |
| `link.roomId` — комната, которую ведёт приложение (`JamLink.find(joinUrl)`) | `OpenHostJam` | `Screen.JAM` |
| джем хозяина идёт, создаётся или ждёт «Продолжить джем?» | `Refuse(HOSTING)` | тост `jam_join_while_hosting`, `Screen.JAM` (GUEST-20, D1) |
| та же комната (сервер и `roomId`) в `Joining`, `InRoom`, `WaitingForRoom` или `NeedName` | `ShowGuest` | `Screen.JAM_GUEST` |
| иначе | `OpenGuest(link)` | `jamGuest.open(link)` (сессия другой комнаты уходит, как `leave`, D7), `Screen.JAM_GUEST` |

`HostSide` и `GuestSide` строит `AppViewModel.routeLink` из `jamHost.state` и `jamGuest.state`. `signedIn` — `SessionState.signedIn`, а не `Library.isLoggedIn`: на холодном старте аккаунт ещё не загружен, а `Session` с токеном уже `Connecting`. `AskSignIn` открывает вход с пояснением `jam_join_needs_login` и кнопкой «Открыть в браузере» (веб-гостю аккаунт не нужен); после входа ссылка открывается, уход со входа её забывает. `openJamLink(text, launchedFromHistory = false): Boolean` — `true`, когда ссылка принята (`AskSignIn`, `OpenHostJam`, `ShowGuest`, `OpenGuest`). Обратное исключение (D1): `JamViewModel.start()` во время гостевой сессии — тост `jam_host_while_guest`, джем не создаётся. `NoSecret` (GUEST-05) сессией не считается: членства нет и ничего не соединено, поэтому системное «назад» вместо «Закрыть» не мешает начать джем и не делает строку «Джем» библиотеки гостевой.

**`JamGuestScreen`** по `GuestPhase`:

| Фаза | Экран |
|---|---|
| `None` | `JoinJamSection` |
| `NeedName` | приглашение `jam_guest_invited`, сервер ссылки моноширинным, поле имени, «Войти» (`join`; отказ — тост `jam_guest_name_invalid`), «Отмена». Имя (GUEST-02) — `lastName`, иначе `displayName` или `login` аккаунта, через `JamNames.prefill` (≤ 24 кодовых точки) |
| `Joining`, а также `InRoom` до первого `state` | `jam_guest_joining` и «Отмена» |
| `WaitingForRoom` без комнаты | строка состояния `jam_guest_waiting_for_room` вместо `jam_guest_joining` и «Отмена» (GUEST-16) |
| `InRoom`, `WaitingForRoom` с комнатой | комната |
| `NoSecret`, `Refused`, `Ended`, `Kicked`, `UpdateRequired` | `GuestPhase.endText()` и «Закрыть» |

«Отмена», «Закрыть» и «Выйти» — `jamGuest.leave()` (закрытие 1000, членство остаётся, GUEST-18) и `Screen.PLAYER`.

**Комната** (`LazyColumn`: очередь бывает до 300 элементов):
- Шапка «Джем · <хозяин>» и светодиод: `connected` — «На связи», соединение открыто или открывается — «Подключаюсь…», иначе «Нет связи». В концах сессии светодиода нет.
- Строка состояния — `JamGuestState.statusText()` (`JamEventTextTest`), первая подходящая: `WaitingForRoom` — `jam_guest_waiting_for_room` (GUEST-16), с комнатой и без; дальше только `InRoom` с комнатой: нет `connected` — `jam_offline_strip` (GUEST-15); `!hostOnline` — `jam_guest_host_offline` (GUEST-09); иначе строки нет.
- «Сейчас играет» (GUEST-14): обложка 64 dp (`JamTracks.fromJam(track).coverUrl`; у трека `source: host` обложки нет), название, исполнители и кто добавил: у `ITEM` — «Добавлено вами» или имя из `participants` (неизвестный — «кто-то»), у `WAVE` — `jam_guest_from_host` (D9). Время — `formatTime(positionMsAt(serverNow()))` из `jam/`, тикает раз в секунду (`LaunchedEffect` экрана; `serverNow` — часы сервера) и стоит на паузе. `IDLE` — `jam_guest_silence`. Справа ♥ (GUEST-12; только у трека каталога) и «Пропустить», пока `JamGuestState.canSkip` (GUEST-11).
- «Добавить треки» → `JAM_GUEST_ADD`. Без связи или без хозяина приглушена, но открывается: списки работают и так (D10).
- Очередь в порядке игры: «Очередь» и «Ваши N/max» (`waitingOfYours`, `maxPendingPerGuest`); свои строки — акцентом и с «×» (`remove`, GUEST-10; для TalkBack — `jam_guest_remove`), чужие — с именем добавившего; закреплённые — «следующий»; пусто — `jam_guest_queue_empty`; на пределе — plurals `jam_guest_limit_hint`.
- «Участники · N»: светодиод «в сети», имя, вид (`jam_kind_*`), «вы».
- «Выйти» с подтверждением вторым нажатием за 3 с (`jam_guest_leave_confirm`).

**`JamGuestAddScreen`**: вкладки `PillToggle` «Поиск · Мне нравится · Плейлисты» (ряд прокручивается вбок: «Мне падабаецца» длинное), та же строка состояния (на пределе — подсказка лимита) и список в `LazyColumn`:
- «Поиск» — `Library.searchTracks` своим аккаунтом (GUEST-06; `search` на сервер джема не уходит никогда, ROOM-27).
- «Мне нравится» — `ListLoader` над `Library.likedTracks()` (чанки по 250, как «Мне нравится» библиотеки) и локальный фильтр по названию и исполнителям (`likedShown`).
- «Плейлисты» — `ListLoader` над `Library.userPlaylists()`; тап по плейлисту — `Library.playlistTracks`, строка «‹ <плейлист>» и «назад» его закрывают.

В списках только `available` треки, для которых `JamTracks.toJam` не `null`. Упавший список — `library_error` и `library_retry` (`retry()` повторяет текущую вкладку). Кнопка строки — по `JamGuestState.addAction(track.id)` (GUEST-08):

| `AddAction` | Показ |
|---|---|
| `Add`, `Failed(reason)` | «Добавить»; у `Failed` под строкой `guestRefusedText(reason)` |
| `Sending` | «…» (`jam_guest_sending`, не переводится) |
| `HostOffline`; `null` — комнаты нет | «Добавить», приглушена |
| `Limit(max)` | «Лимит» |
| `Yours` | «Ваш, в очереди» |
| `QueuedBy(name)` | «В очереди: имя», без имени — «В очереди» |

«Добавить», в том числе приглушённая, — `JamGuestViewModel.add(track)` = `JamTracks.toJam(track)?.let(jamGuest::add)`; всё, кроме `SENT`, — тост `GuestAddResult.text()`: `NOT_CONNECTED` — `jam_no_connection`, `HOST_OFFLINE` — `jam_guest_host_offline`, `INVALID` (и `toJam` = `null`) — `jam_guest_add_invalid`.

```kotlin
enum class GuestTab { SEARCH, LIKED, PLAYLISTS }
data class JamGuestUi(val name: String, val tab: GuestTab, val searchText: String, val results: ListState<Track>,
                      val likedFilter: String, val playlist: PlaylistRef?, val playlistTracks: ListState<Track>)
val state: StateFlow<JamGuestState>;  val ui: StateFlow<JamGuestUi>;  val notices: SharedFlow<String>
val likedIds: StateFlow<Set<String>>;  val playlists: ListLoader<PlaylistRef>;  val likedShown: StateFlow<ListState<Track>>
fun serverNow(): Long
fun setName(text: String);  fun join();  fun leave();  fun add(track: Track);  fun remove(itemId: String);  fun skip()
fun toggleLike();  fun setTab(tab: GuestTab);  fun retry();  fun setSearchText(text: String);  fun search()
fun setLikedFilter(text: String);  fun openPlaylist(playlist: PlaylistRef);  fun closePlaylist()
```

`JamGuestViewModel` заполняет `ui.name` при каждом входе в `NeedName` и ещё раз, когда приходит аккаунт, пока пользователь имя не трогал (`setName`). `JamGuestEvent.Refused` (отказ в `remove`, `skip`) и свои отказы идут в `notices` → `AppViewModel.say`. Когда аккаунт становится пустым (выход), списки и вкладки сбрасываются: следующий аккаунт чужих лайков не увидит. `AppViewModel` следит за `jamGuest.state` и с `JAM_GUEST_ADD` переходит в комнату, когда фаза уже не `InRoom`/`WaitingForRoom`, чтобы конец сессии был виден.

**Лайк себе** (GUEST-12, D12): `toggleLike()` берёт текущий трек комнаты, если он из каталога, и зовёт `Library.setLiked(id, !isLiked(id))` на `Dispatchers.IO`; тост `queue_liked` / `queue_unliked`, ошибка — в logcat и тост `error_generic` с видом ошибки. Сердце — `track.id in library.likedIds`. Не через `QueueController`. Второй тап, пока запрос в полёте, ничего не делает.

**Traps:**
- `graph.jamGuest` живёт на `Dispatchers.Main.immediate`: его зовут с главного потока (view model, колбэки Compose).
- `jamGuest.open(link)` для знакомой комнаты подключается сразу, поэтому ссылку до входа держит `AppViewModel.pendingLink`, а не `JamGuest`.
- `savedInstanceState == null` от повтора ссылки не спасает. Ссылка, запустившая приложение, становится базовым `Intent` задачи; на Android 8–11 «назад» с «Плеера» закрывает активность, и «Недавние» запускают её заново с тем же `Intent`, флагом `FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY` и пустым состоянием. Без проверки флага гость после «Выйти» молча вошёл бы снова: членство осталось, а знакомая комната входит сразу.
- Ожидание комнаты бывает и без комнаты на экране. `open(link)` и `restore()` начинают сессию без `room`, а `state` до `joined` не принимается, поэтому `room-not-found` на первый же `join` ждёт с пустой комнатой. Так выходит, когда ссылку открывают заново после «Выйти», после заморозки приложения в фоне или после `ended{expired}`, и после перезапуска (GUEST-19). Без `statusText()` на экране без комнаты эти 10 минут выглядели бы как «Вхожу…».
- `restore()` даёт `true` и для конца сессии, который ещё не закрыли кнопкой (фаза не `None`), — тогда стартовый экран показывает текст конца.
- Ключей у строк списков треков нет: в плейлисте один трек может стоять дважды, а одинаковые ключи `LazyColumn` роняют приложение.

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

`QiText` — `BasicText` без отступов Material. `Modifier.tap` — `clickable` без индикации. `OnOffPills(value, onText, offText, enabled, onChange)` — две `PillToggle` (`enabled = false` — приглушены до `DISABLED_ALPHA` 0,35 и не нажимаются; так же `IconWideButton` и `LikeButton`) (подписи заглавными, по умолчанию `toggle_on` / `toggle_off`); `onChange` зовётся только при смене значения, тап по горящей пилюле ничего не делает. Ею пользуются настройки джема («Гости могут пропускать», «Новые гости» со своими подписями, «Учить волну джема», «Гости могут слушать»), строка плейлиста, блок библиотеки «Продолжать волной» и «Отправлять статистику» внизу библиотеки. `Chip` с `onLongClick` использует `combinedClickable` (тоже без индикации); в этой версии Compose он `@ExperimentalFoundationApi`, отсюда `@OptIn` на `Chip`. `VerticalFader` округляет до 0.1 дБ, тап по значению над фейдером сбрасывает полосу в 0.

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

**Трек не из Яндекса** (свой файл, LOCAL-08; трек Spotify, SPOT-19: дизлайка нет, лайка пока нет, зато в листе есть «Радио», SPOT-12). Лайк и дизлайк ему не предлагаются: на экране плеера и в строке текущего трека в Library кнопки неактивны (`Track.isYandex`), в листе действий эти две строки неактивны (`TrackActionRow(enabled = false)`, `DISABLED_ALPHA`), в уведомлении нет кнопок (`playback/NotificationButtons`). «Открыть в браузере» (кнопка Library и строка листа) решает отдельно, по `webUrl != null`: у своего файла адреса нет, и кнопка неактивна, трек Spotify открывает `https://open.spotify.com/track/<id>`. «Нравится ли» — `isYandex && id in likedIds`, «текущий ли» — по `TrackKey`: свой файл 42 не «лайкнут» треком Яндекса 42.

**Долгое нажатие на строку плейлиста.** Строка — `combinedClickable` без индикации (как `Chip`, отсюда `@OptIn(ExperimentalFoundationApi::class)` на `PlaylistScreen`): тап играет строку, долгое нажатие — `showTrackActions(track)`; `onLongClickLabel = playlist_actions`: без неё TalkBack предлагает безымянное долгое нажатие. Номер слева — свой `tap` внутри строки и получает касание первым: тап по номеру выделяет строку, долгое нажатие на номер лист не открывает. Подсказка `playlist_hold_hint` стоит отдельной строкой под «ВЫБРАНО n · m:ss / h:mm:ss». Оценка по ширине знака `HintStyle` (около 7,6 dp, не замер на устройстве): справа от счётчика, рядом с `playlist_tap_hint`, ей осталось бы 120–190 dp на телефоне 360–412 dp, то есть 2–3 строки; в своей строке ей нужно около 266 dp по-английски, 296 по-русски и 319 по-белорусски, и на 360 dp (328 dp внутри отступов) она помещается в одну.

`TrackActionsSheet(actions, viewModel, onRadio)` рисуется из `AppRoot` поверх всего приложения, включая мини-плеер и вкладки, пока `trackActions != null`: затемнение (тап закрывает) и панель снизу — название и исполнители трека и три строки, у трека Spotify — четыре:

| Строка | Подпись | Действие |
|---|---|---|
| лайк | `player_like` с контуром сердца или `playlist_unlike` с залитым, по `liked` | `likeOrUnlikePicked()` |
| дизлайк | `player_dislike_skip`, когда `isCurrent`, иначе `library_dislike` | `dislikePicked()` |
| радио, только у трека Spotify (`actions.radio`) | `spotify_radio` с `IconPaths.RADIO` контуром | `closeTrackActions()`, затем `onRadio(track)` — в `AppRoot` это `SpotifyViewModel.playRadio` |
| браузер | `playlist_open` | `Intent.ACTION_VIEW` с `track.webUrl` (`TrackPages.kt`), как кнопка библиотеки; `Intent` нужен `Context`, поэтому живёт в листе |

У каждой строки своё `enabled`: лайк и дизлайк — `track.isYandex`, браузер — `webUrl != null`.

У строк `Role.Button`, подпись строки — её текст для TalkBack. Панель листа — `paneTitle = playlist_actions`, поэтому TalkBack объявляет лист, когда тот открывается. Каждое действие закрывает лист; «назад» — тоже (`BackHandler` в `AppRoot`).

**Traps (`TrackActionsSheet`):**
- Панель получает `navigationBarsPadding()` после фона. `MainActivity` включает `enableEdgeToEdge()`, а `AppRoot` отступает от системных полос только в колонке экранов; лист рисуется вне её, и без отступа последняя строка (браузер) легла бы под трёхкнопочную навигацию (48 dp), где тапы уходят системе.
- Панель ловит касания сама (`pointerInput { detectTapGestures() }`): иначе тап по названию трека прошёл бы к затемнению под ней и закрыл лист.
- Подпись дизлайка выбирается при открытии листа, а очередь решает при нажатии. Если текущий трек сменился, пока лист открыт, подпись может разойтись с тем, что произойдёт, в любую сторону; поведение при этом верное (TR-10), и десктоп устроен так же: подпись строится вместе с меню, проверка идёт при действии.

**Списки библиотеки.** Каждый список грузит свой `ListLoader` в своей корутине на `Dispatchers.IO`, поэтому списки приходят параллельно, и упавший или зависший список не держит остальные.

```kotlin
class ListLoader<T>(scope: CoroutineScope, io: CoroutineContext, fetch: suspend () -> List<T>, logFailure: (Throwable) -> Unit,
                    errorKindOf: (Throwable) -> ErrorKind = ErrorKind::of) {   // вид ошибки Failed; списки Spotify передают корневой errorKindOf
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

Обложки — на экране плеера и в «Сейчас играет» гостя — грузит Coil 3.6.3 (`coil3.compose.AsyncImage` с адресом `coverUrl` строкой) через `SingletonImageLoader`; HTTP ему даёт модуль `coil-network-okhttp`. Своего `SingletonImageLoader.Factory` у приложения нет, поэтому одиночка — `ImageLoader.Builder(applicationContext).build()` (`SingletonImageLoader.kt:86–128`). Тот же одиночка грузит обложку уведомления, экрана блокировки и Bluetooth (`playback/CoverBitmapLoader`, `playback/README.md`, «Обложка в уведомлении»), поэтому дисковый кэш у экрана и уведомления общий. Настроек Coil в коде нет: всё ниже — значения по умолчанию версии 3.6.3, сверенные по её исходникам. Следующее обновление Coil сверяет их заново.

**Дисковый кэш обложек** (на устройстве не проверялся, Kickoman/QiYaa-android#91):
- **Где.** `FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "coil3_disk_cache"`, один экземпляр на процесс (`singletonDiskCache()`, `nonJsCommonMain/coil3/disk/utils.kt:12–18`). Временная папка Okio — `java.io.tmpdir`, а его Android ставит в `cacheDir` приложения до создания `Application` (AOSP `ActivityThread.handleBindApplication`), то есть папка — `cache/coil3_disk_cache` в данных приложения.
- **Сколько.** 2 % размера раздела, на котором лежит папка, в пределах 10–250 МиБ (`DiskCache.Builder`: `maxSizePercent = 0.02`, `DiskCache.kt:115–117`, `:191–202`). Документация Coil говорит «свободного места», но на Android `remainingFreeSpaceBytes` — это `StatFs.blockCountLong * blockSizeLong`, то есть весь раздел (`fileSystems.android.kt:7–10`). На разделе данных от 12,5 ГиБ кэш — 250 МиБ (больше 10 000 обложек по 22 КБ). Размер считается один раз, при первом обращении к кэшу. Вытесняется давно не использованное (`DiskLruCache`).
- **Что читается.** HTTP-заголовки Coil 3 не смотрит. `OkHttpNetworkFetcherFactory()` из `ServiceLoader` берёт `CacheStrategy.DEFAULT` и свой `OkHttpClient()`, не `AppGraph.httpClient` (`OkHttpNetworkFetcher.kt:16–18`, `NetworkFetcher.kt:290–294`). `DefaultCacheStrategy.read` отдаёт запись с диска без сети и без проверки срока (`DefaultCacheStrategy.kt:12–19`), а `write` пишет каждый ответ 2xx и ещё 300, 301, 404, 405, 410, 414 и 501 (`:36–48`). Ключ записи — сам URL (`options.diskCacheKey ?: url`, `NetworkFetcher.kt:284–285`). Обложка, которую видели раз, показывается без сети, пока её не вытеснят или пока Android не очистит `cacheDir` при нехватке места.
- **Заголовки обложек Яндекса** (`avatars.yandex.net/get-music-content/…/400x400`, `curl -sI` 2026-10-17): 200, `cache-control: max-age=86400,immutable`, `last-modified`, без `ETag`; JPEG 21 739 Б, WebP 8 082 Б с `Accept: image/webp`. Coil 2.7 им следовал (сутки), Coil 3 их не читает.

**Traps (Coil):**
- Без `coil-network-okhttp` у Coil 3 нет загрузчика `http(s)`, и обложки молча остаются пустыми: ни ошибки сборки, ни исключения. Модуль Coil находит сам через `ServiceLoader` (`META-INF/services`), регистрировать его в коде не нужно.
- Ответ 404 или 410 тоже ложится на диск, и `NetworkFetcher` отдаёт по нему ошибку, не спрашивая сеть (`NetworkFetcher.kt:81–96`). Если по этому адресу обложка появится позже, она не покажется, пока запись не вытеснят. Кода для этого нет.
- Общий дисковый кэш — `singletonDiskCache()`: его берёт любой `ImageLoader.Builder` без своего `diskCache` (`ImageLoader.kt:357–359`). `ImageLoader` или `SingletonImageLoader.Factory` с другим `diskCache` развёл бы кэши экрана и уведомления (`CoverBitmapLoader` берёт `SingletonImageLoader.get`). Второй `DiskCache` в той же папке Coil запрещает: он портит кэш (KDoc `DiskCache.Builder.directory`).

## Строки

Все тексты — `res/values/strings.xml` (английский), `res/values-be/strings.xml` (белорусский) и `res/values-ru/strings.xml`; `queue_loaded`, `queue_loaded_first` (выбор обрезан до первых N, LOCAL-12), `queue_stopped_after_failures` и `jam_guest_limit_hint` — `plurals` (у белорусского и русского формы `one`, `few`, `many`, `other`). «Волна» Яндекса по-английски — **vibe** («My Vibe», «Jam vibe», «Wheel of vibes»), не wave; ключи (`library_my_wave`…) остались прежними. Слова те же, что на ПК: таблица в `translations/README.md` репозитория QiYaa. `StringsTest` проверяет, что каждый текст переведён на оба языка с теми же подстановками, формы plurals и что ни один английский текст не говорит «wave».

**Качество звука** (`spec/player/quality.md`). Внизу библиотеки, над выбором языка: заголовок `quality_title`, строки «Wi-Fi» (`quality_wifi`) и «Мобильная сеть» (`quality_mobile`) с двумя `PillToggle` — `quality_standard` и `quality_economy` — и пояснение `quality_note`. Строки — `ChoiceRow`; при крупном шрифте пилюли переносятся на вторую строку (выше, «Случайный порядок»). Выбор уходит в `AppViewModel.setQuality` / `setQualityMetered` и действует со следующего трека, даже уже подгруженного (QUAL-05: сервис загружает его заново, `playback/README.md`, кэш ссылок); трек, который играет, доигрывает своим файлом. Поэтому `quality_note` («Со следующего трека») верен и строк не прибавилось. Отдельной настройки у Spotify нет: тот же выбор даёт Ogg Vorbis 320 или 96 kbps (SPOT-13), и `quality_note` называет оба. Примечание к «Слушать вместе» (`jam_share_audio_note`) говорит, что слушатели получают файлы только при стандартном качестве (LISTEN-13).

**Отправлять статистику** (TEL-02, `docs/telemetry.md`). Самая нижняя строка библиотеки, под выбором языка, и только в сборке с телеметрией: `AppViewModel.telemetryAvailable` (`appGraph.telemetry != null`, то есть `-PqiyaaWithTelemetry=true`, `docs/release.md`); без неё строки нет (TEL-01). Подпись `telemetry_title` в `LabelStyle`, `OnOffPills` по `Settings.telemetryEnabled` с подписями по умолчанию `toggle_on` / `toggle_off` и пояснение `telemetry_note` в `HintStyle` — оно и есть раскрытие: телеметрия включена с первого запуска без вопроса, как у десктопа. `AppViewModel.setTelemetryEnabled(on)` только пишет настройку (`telemetry/enabled`, `data/README.md`); выключение (забыть запуск и удалить файлы) и новый запуск со своим `start` при включении делает `telemetry/TelemetrySession` на своём потоке, не на главном.

**Язык интерфейса — свой, не системный.** `data/Settings.language` (по умолчанию белорусский). `AppLocale` даёт его приложению и `PlaybackService` в `attachBaseContext` (контекст с локалью) и активности — через `applyOverrideConfiguration`, чтобы она по-прежнему следила за экраном и тёмной темой. `LanguageSwitch` (коды `BE RU EN`, текущий подсвечен) стоит на экране входа, в шапке библиотеки без входа (сменить язык можно до входа: по умолчанию он белорусский) и внизу библиотеки с подписью `language_title` и английским словом «LANGUAGE», чтобы непонятный язык можно было отменить. Выбор: `AppViewModel.setLanguage` сохраняет язык и переводит ресурсы приложения (`AppLocale.apply`), экран пересоздаёт активность; сервис перестраивает кнопки уведомления под новый язык. Системная смена конфигурации сбрасывает локаль ресурсов приложения и сервиса — `onConfigurationChanged` применяет язык заново. В сборке `bundle.language.enableSplit = false`: все языки должны быть в установке. Ключи `jam_*` — экраны джема; `toggle_on` / `toggle_off` — подписи `OnOffPills` по умолчанию (до #79 — `jam_on` / `jam_off`, тексты те же: On / Off, Да / Нет, Так / Не); `vibe_continue_*` — «Продолжать волной» в плейлисте и библиотеке; `shuffle_*` — «Случайный порядок» в библиотеке; `telemetry_title`, `telemetry_note` — «Отправлять статистику»; `jam_refused_*` — причины отказа сервера: все 18 значений `reason` из `spec/jam/protocol/schemas/defs.schema.json` (`JamEventTextTest`), `kicked` — `jam_guest_kicked`, неизвестная причина — `jam_refused_other`, а `queue-limit` гостю — `jam_refused_guest_queue_limit` (его собственный лимит), хозяину — `jam_refused_queue_limit`; `jam_join_*` — «Войти в джем» и отказы ссылки, `jam_host_while_guest`, `jam_library_guest`, `jam_guest_*` — экраны гостя (`jam_guest_sending` — «…», `translatable="false"`); `playlist_hold_hint`, `playlist_actions` (подпись долгого нажатия для TalkBack), `playlist_unlike`, `playlist_open` — долгое нажатие и лист действий с треком, а дизлайк в листе берёт готовые `library_dislike` и `player_dislike_skip`. Ключи `queue_*` рендерят `QueueEvent` (в том числе `queue_waiting_for_network` и plurals `queue_stopped_after_failures`), `theme_*` — подписи `AccentTheme`. `library_local` и `local_*` — раздел «Свои файлы» и его экраны. `lyrics_*` — экран текста, блок LRCLIB в Library и диалог согласия. `spotify_*` — раздел «Spotify» в Library, экран входа в Spotify, «Радио» листа и тосты входа; `queue_autoplay_on`, `player_shuffle_radio`, `player_repeat_radio` — тосты очереди Spotify (`QueueNotices.kt`); подпись `library_spotify` — `translatable="false"`. Формулировки — по переводам ПК (`translations/qiyaa_ru.ts`, `qiyaa_be.ts`: «Liked Songs» — «Любимые треки» / «Улюбёныя трэкі», «%1: radio» — «%1: радио» / «%1: радыё», «Spotify plays only with a Premium account.», статусы `SpotifyAccount` и диалога входа); у ПК страница браузера говорит «эту страницу можно закрыть», здесь ещё — «вернитесь в приложение».

## Not here

- Логика очереди (что играть дальше, догрузка волны) — `queue/QueueController`. Ключи настроек — `data/README.md`.

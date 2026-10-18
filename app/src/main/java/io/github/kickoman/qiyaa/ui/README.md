# `ui/` — Compose

Четыре вкладки (Player · Playlist · EQ · Library), экран входа, экраны джема, мини-плеер и тосты, нарисованные вручную по дизайн-системе «Winamp-inspired mobile music player»: без Material-компонентов, без ripple, IBM Plex Mono для показаний и IBM Plex Sans для контента. Пакет только читает `StateFlow`-ы и вызывает методы view model. Он **не** содержит доменной логики: очередь — `playback/`, сеть — `yandex/`, DSP — `audio/`.

```
ui/
  AppRoot.kt, MainActivity.kt          корень Compose, вкладки, мини-плеер, тост
  AppViewModel.kt, PlayerViewModel.kt, JamViewModel.kt, JamGuestViewModel.kt  состояние экранов
  LinkRouting.kt                       куда ведёт ссылка на джем
  QueueEventText.kt, JamEventText.kt, Format.kt         рендер событий и чисел в строки
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
| `AppViewModel.kt` | `Screen`, `LibrarySection`, `LoginStatus`, `LoginUi`, `LibraryUi`, `AppViewModel` (в том числе `openJamLink`), `PlaylistRef.sourceId` |
| `LinkRouting.kt` | `HostSide`, `GuestSide`, `LinkRefusal`, `LinkAction`, `route(…)`, `GuestPhase.isSession` — решение, что делать со ссылкой на джем (GUEST-01, GUEST-20, GUEST-21) |
| `ListLoader.kt` | `ListState` (`Idle`, `Loading`, `Loaded(items)`, `Failed(error)`), `ListLoader` — загрузка одного списка библиотеки со своей ошибкой и повтором |
| `PlayerViewModel.kt` | `PlayerUi`, `TrackActions`, `PlayerViewModel`, `AccentTheme.labelId` |
| `JamViewModel.kt` | `JamUi`, `JamViewModel` — хозяин джема для экранов: `playback/JamHost`, поиск треков для джема, настройки `jam/*` |
| `JamGuestViewModel.kt` | `GuestTab`, `JamGuestUi`, `JamGuestViewModel` — гость в чужом джеме для экранов: `jam/JamGuest`, свои поиск, лайки и плейлисты через `yandex/Library`, «лайк себе» |
| `JamTrackNote.kt` | `jamTrackNote(entry, isWave, jamOn, names)` — метка строки плейлиста во время джема (HOST-34) как `TextRes` или null |
| `JamEventText.kt` | `JamHostEvent.render(context)`, `JamGuestEvent.render(context)`, `refusedText(reason)` (все 18 причин протокола), `guestRefusedText(reason)`, `GuestPhase.endText()`, `JamGuestState.statusText()`, `GuestAddResult.text()` — отказы, концы джема и сессии гостя, «нет связи» хозяина (`NotConnected`), строка состояния гостя → строки |
| `QueueEventText.kt` | `QueueEvent.render(context)` — событие очереди → строка из ресурсов |
| `ErrorText.kt` | `ErrorKind.text()` — строка вида ошибки (`error_*`, у `ServerError` со статусом), `render(context)` и `@Composable render()`; `LOG_TAG = "QiYaa"` |
| `Format.kt` | `formatTime`, `positionAt` (доля полосы перемотки → мс), `timeLabel` (подпись времени плеера), `balanceLabel`, `formatReadout`, `formatDb` |
| `components/Components.kt` | `QiText`, `Modifier.tap`, `ScreenHeader`, `ActionButton`, `IconActionButton`, `IconWideButton` (широкая кнопка с иконкой и описанием для экранного диктора), `LikeButton`, `PillToggle`, `OnOffPills` (пара `PillToggle` «вкл / выкл»), `RoundButton`, `LedDot`, `Chip` |
| `components/Sliders.kt` | `HorizontalSlider` (0…1), `VerticalFader` (дБ) |
| `components/Icons.kt` | `IconPaths`, `PathIcon` — SVG-пути (viewBox 24): транспорт из макета; сердце, палец вниз и «открыть снаружи» для кнопок лайка, дизлайка и браузера, контуром (`strokeWidth = IconPaths.OUTLINE`), сердце лайкнутого трека — заливкой. Подписи у этих кнопок нет (по-белорусски и по-русски она не помещается), текст — их `contentDescription` |
| `components/QrCode.kt` | `QrCodeImage` — QR (qrcodegen, коррекция M) тёмным по белому с полями в 4 модуля |
| `components/Sections.kt` | `SectionLabel`, `Note`, `Panel`, `Divider`, `SettingRow`, `InsetField` — секции экранов джема (хозяина и гостя), простые значения на входе |
| `screens/LoginScreen.kt` | `LoginScreen` |
| `screens/PlayerScreen.kt` | `PlayerScreen`, `marqueeText`, `queueTitle` |
| `screens/PlaylistScreen.kt` | `PlaylistScreen`, `TrackActionsSheet` — лист действий с треком строки |
| `screens/EqScreen.kt` | `EqScreen`, `PresetsSheet` |
| `screens/LibraryScreen.kt` | `LibraryScreen` |
| `screens/JamScreen.kt` | `JamScreen`, `JamSettingsScreen`, `JamContinueDialog` |
| `screens/JamGuestScreen.kt` | `JamGuestScreen` (комната гостя по фазам), `JamGuestAddScreen` (добавить треки: поиск · лайки · плейлисты), `JoinJamSection` (вставить ссылку; стоит и на `JamScreen`) |
| `screens/LibrarySectionScreen.kt` | `LibrarySectionScreen` — «для вас» / станции / плейлисты / исполнители / альбомы чипами |
| `theme/Color.kt` | `QiColors`, `accentColors`, `LocalQiColors` |
| `theme/Theme.kt` | `Qi.colors`, `QiYaaTheme` |
| `theme/Type.kt` | `PlexMono`, `PlexSans`, `mono()`, `sans()`, `CaptionStyle`, `LabelStyle`, `ButtonStyle`, `ReadoutStyle`, `HintStyle` |
| `visualizer/VisualizerStrip.kt` | `VisualizerStrip` |

## View models

`AppViewModel` — навигация (`screen`, `section`), вход (`login`), есть ли телеметрия (`telemetryAvailable`), списки библиотеки (`forYou`, `playlists`, `artists`, `albums`, `stations`, `wheel` — по `ListLoader` на каждый; `libraryUi` хранит только текст поиска и `wheelMatchesCurrent`), тост (`toast`, 1.8 с). При старте: есть токен → `Screen.PLAYER`, иначе `Screen.LOGIN` и `startDeviceLogin()`. Подключением управляет `yandex/Session` (`AppGraph.session`), view model только слушает `sessionState`: `Online` → `loadLibraryLists()`, `Expired` → очистить очередь, `TokenStore` и списки, открыть вход с `LoginUi.notice = login_expired` и запросить новый код. Вход (`applyToken`) идёт через `session.signIn(token)`; токен сохраняется только после успеха. «Выйти» делает то же, что `Expired`, без сообщения. Оба заканчивают джем хозяина; гостя `Expired` только выводит из комнаты (`jamGuest.leave()`, членства остаются: это тот же человек на том же устройстве, GUEST-03), а «Выйти» забывает все членства (`jamGuest.forgetAll()`). Стартовый экран с токеном — `Screen.JAM_GUEST`, если `jamGuest.restore()` поднял сессию гостя (GUEST-19), иначе `Screen.PLAYER`. Собирает `queue.events` и рендерит их через `QueueEvent.render`. Заголовки источников (`library_liked`, `library_my_wave`, `queue_search_title`) берёт из ресурсов и передаёт в очередь как данные. Неудачный вход — по коду устройства (`LoginStatus.Failed`) или токеном (`applyToken`) — ещё и `error login/failed` телеметрии (`graph.telemetry?.loginFailed()`, без телеметрии `telemetry` — `null`); `SessionState.Expired` туда же отправляет сам `AppTelemetry` (`telemetry/README.md`).

`PlayerViewModel` — `MediaController` к `PlaybackService`, снимок `PlayerUi` (позиция тикает каждые 250 мс, пока играет), транспорт, громкость/баланс/визуализатор/тема, EQ, флаг «продолжать волной» текущей очереди. Свои сообщения (shuffle, повтор, тема, EQ) публикует в `notices`, которые `AppRoot` перекладывает в `AppViewModel.say`. Громкость, баланс и EQ только записывает в `Settings`; к плееру и `AudioBus` их применяют `AppGraph` и `PlaybackService`, так что настройки действуют и без открытого экрана (см. `data/README.md`).

`seekToFraction(fraction)` переводит долю полосы в миллисекунды через `positionAt` из `Format.kt` (тот же расчёт, что у подписи времени), перематывает и в том же `update {}` пишет цель в `positionMs`, чтобы ни один кадр не показал старую позицию (TR-08). При неизвестной длительности (`positionAt` вернул `null`) ничего не делает. Дальше цель держит `MediaController`: он маскирует перемотку (`BUFFERING`, `isPlaying = false`, позиция — цель) и откладывает состояние от сессии, пока команду не подтвердят, поэтому `refresh` на `onEvents` читает цель и останавливает тикер. Тикер снова идёт, только когда плеер заиграет, и уже с цели; пауза остаётся паузой. Как это устроено в Media3 — `playback/README.md`, «Перемотка и буфер». Перетаскивания полосы view model не видит: оно живёт в `PlayerScreen` (см. «Компоненты и экраны»).

`toggleShuffle()` и `toggleRepeat()` зовут `QueueController.setShuffle`/`setRepeat` (не `MediaController`) и показывают тост по выбору после команды (`queue.modes`); во время волны очередь не трогают и показывают `player_shuffle_wave` / `player_repeat_wave`. Кнопки SHF и RPT на экране плеера читают `queue.modes`, а не режим ExoPlayer: подсвечены, когда режим выбран и очередь не заперта, приглушены (`dimmer`), когда заперта (волна, станция: `modes.locked` = `isWave`). Джем очередь не запирает: над базой с головой SHF и RPT работают и во время джема, над «Моей волной» и станцией приглушены, как вне джема. Правило живёт в `queue/WaveModeRule`.

`next()` и `previous()` вызывают `seekToNext`/`seekToPrevious` у `MediaController`, `stop()` — `queue.stop()`; правила (3 с, конец очереди, конец волны) живут в `queue/QueueController` и одинаковы для приложения, уведомления и гарнитуры (см. `playback/README.md`, «Один хозяин плеера»). «Loading more…» показывает событие `QueueEvent.LoadingMore`.

**Лайк и дизлайк.** ♥ экрана плеера и библиотеки — `toggleLikeCurrent()` → `queue.toggleLike(ui.current)`. Их дизлайк — `dislikeCurrent()` → `queue.dislike(ui.current)`: запрос уходит показанному треку, а «вперёд» — только если он ещё текущий, когда очередь получает вызов (TR-10, `queue/README.md`, «Дизлайк»). Нажатие во время смены трека дизлайкает показанный (старый) трек и не пропускает новый.

**Действия с треком плейлиста** (Kickoman/QiYaa-android#84, TR-10):

```kotlin
data class TrackActions(val track: Track, val liked: Boolean, val isCurrent: Boolean)
val trackActions: StateFlow<TrackActions?>
fun showTrackActions(track: Track);  fun closeTrackActions()
fun likeOrUnlikePicked();  fun dislikePicked()
```

`trackActions` — открытый лист, `null` — закрыт. `showTrackActions(track)` захватывает трек строки и оба флага в момент долгого нажатия: `liked` — по `library.likedIds` (тот же набор, что у ♥ плеера и у уведомления), `isCurrent` — `track.id == ui.value.current?.id` (тот же трек, что подсвечен в плейлисте и показан на экране плеера). Дальше лист из очереди ничего не перечитывает: под открытым листом строки могут сдвинуться (догрузка волны, правки джема), а действие должно уйти тому треку, что показан. `likeOrUnlikePicked()` — `queue.setLiked(track, !liked)`: шлёт состояние, которое видел пользователь, а не переключение, как `setLiked(key, !liked)` на десктопе. `dislikePicked()` — `queue.dislike(track)`: очередь заново решает в момент действия, текущий ли трек. Оба сначала закрывают лист, а при закрытом листе ничего не делают, поэтому второй тап по строке листа второго запроса не шлёт.

**Traps:**
- Обе view model — `AndroidViewModel` с областью Activity; `PlayerViewModel.onCleared` освобождает контроллер.
- `PlayerUi.durationMs` берёт длительность из `MediaItem.extras`, пока ExoPlayer её не знает.
- `refresh` на каждое событие плеера строит `PlayerUi` заново (`PlayerUi(...)`, не `copy`), а тикер каждые 250 мс переписывает `positionMs`. Поле, которое не приходит из плеера, первое же событие сбросит в значение по умолчанию. Поэтому чисто экранное состояние (перетаскивание полосы и подобное) в `PlayerUi` не хранится никогда: оно живёт в `remember` экрана. Когда предпросмотр перетаскивания писался в `positionMs`, подпись времени мигала между пальцем и плеером (#90).
- `LoginStatus.Failed(error)` и `ListState.Failed(error)` хранят `yandex/ErrorKind`, а не текст; на экране — `error.render()` заглавными. Исключение пишется в logcat (`Log.w(LOG_TAG, …)`) там же, где поймано: `AppViewModel.kindOf`, отказ подключения `MediaController` в `PlayerViewModel`. Тост «сервис недоступен» текста исключения не показывает.
- `sessionState` меняется с `Dispatchers.Default` (`AppGraph.applicationScope`); коллектор во view model работает на главном потоке, поэтому `queue.clear()` там безопасен.

`AppRoot` при `SessionState.Offline` показывает над экраном строку `session_offline` («НЕТ СЕТИ · ПОВТОРЮ САМ») с красным `LedDot`; на экране входа строки нет. Повторы идут сами, кнопки «повторить» нет.

**«Назад».** `Screen.backTarget`: с вкладок EQ, Плейлист и Библиотека — на вкладку «Плеер» (`AppRoot`, `BackHandler`), с комнаты гостя (`JAM_GUEST`) — тоже на «Плеер», с «Добавить треки» гостя (`JAM_GUEST_ADD`) — в комнату, а открытый там плейлист сначала закрывается; с «Плеера» и экрана входа — системное поведение, то есть выход из приложения. Обработчики, объявленные позже, важнее: открытый раздел библиотеки сначала закрывается (`LibrarySectionScreen`), открытый лист пресетов EQ — тоже (`closePresets`), лист действий с треком — тоже (`closeTrackActions`, объявлен после пресетов), и только потом срабатывает переход на «Плеер».

`PlaylistScreen` показывает внизу списка строку `playlist_loading_more`, пока `QueueState.loadingMore`, а не всё время, пока играет волна. Когда это так, решает очередь (`queue/README.md`, «Ждёт продолжения»): у своей волны — пока идёт догрузка, у волны по истории — только пока очередь ждёт её треков после конца головы; тихий запрос за два трека до конца головы строку не показывает (CONT-04: без статуса).

**Продолжать волной** (CONT-01, CONT-02, `spec/player/continuation.md`). Два переключателя `OnOffPills`:
- **Плейлист** — строка под шапкой: `vibe_continue_title` и «вкл / выкл» по `QueueState.continueWithVibe`. Есть, только когда у очереди нет своего продолжения (не «Моя волна», не станция) и она не пуста или идёт джем: `!isWave && (tracks.isNotEmpty() || phase == ACTIVE)`. Во время джема это флаг базы, и пустой тоже (HOST-14, D15): когда кончится часть джема, он решает, пойдёт ли волна джема. Переключение — `PlayerViewModel.setQueueContinuesWithVibe(on)` → `queue.setContinueWithVibe(on)` и тост по флагу после команды: `vibe_continue_on` («за этой очередью пойдёт волна») или `vibe_continue_off` («эта очередь остановится в конце»). Меняет только эту очередь, не настройку.
- **Библиотека** — блок над «Качеством»: подпись `vibe_continue_title`, строка «Новые очереди» (`vibe_continue_new_queues`) с «вкл / выкл» по `Settings.continueWithVibe` и пояснение `vibe_continue_note`. Выбор — `AppViewModel.setContinueWithVibe` → `Settings.setContinueWithVibe` (ключ `playback/continueWithVibe`, по умолчанию вкл, `data/README.md`); действует с новой очереди: очередь берёт значение при создании и при `clear()` через лямбду `continueWithVibeByDefault` из `AppGraph`. На десктопе эта настройка есть только в `settings.ini`; у Android нет файла, который пользователь может править, поэтому без экрана умолчание было бы не изменить.

Отметки на треках волны в плейлисте нет: на десктопе отмечена только волна джема (HOST-34).

## Джем

Экраны хозяина джема (Kickoman/QiYaa-android#62, сценарии HOST-20…35 в `spec/jam/host.md`). Логика — `playback/JamHost`; `JamViewModel` только передаёт действия и превращает `JamHostEvent` и свои отказы («нет связи», «не настроено») в тосты через `notices` → `AppViewModel.say`.

- **Вход**: строка «Джем» в библиотеке (мета — «Начать» или «Идёт · N гостей»), а во время джема — тап по названию очереди на экране плеера (оно подсвечено акцентом). Это название базы: своего названия у джема нет (D4, как на десктопе), пустая база — `player_no_queue`. `Screen.JAM` → назад на «Плеер», `Screen.JAM_SETTINGS` → назад на «Джем».
- **`JamScreen`** по фазе `JamHostState.phase`: `NONE` — имя хозяина (по умолчанию имя аккаунта, ≤ 24 символа), «Начать джем», при пустом сервере — подсказка и кнопка настроек; `CREATING` — «Подключаюсь…» и «Отмена»; `ACTIVE` — QR `joinUrl` на 72 % ширины, ссылка, «Поделиться ссылкой» (`ACTION_SEND` через системный выбор) и «Копировать» (`LocalClipboard.setClipEntry` в корутине экрана; `ClipData.newPlainText` без метки, чтобы не заводить видимую пользователю строку), поиск треков для джема (`Library.searchTracks`, «Следом» = `playNext`, «В джем» = `add`), гости (онлайн, вид, сколько ждёт, «Убрать»), настройки комнаты (порядок, пропуск гостями, вход), «Новая ссылка», «Закончить джем» с подтверждением вторым нажатием за 3 с. Состояние связи — в шапке.
- **Без связи** (HOST-25) «В джем», «Следом», «Убрать» и «Новая ссылка» приглушены, а любое действие с комнатой даёт тост `jam_no_connection`; над любым экраном — красная строка `jam_offline_strip`, тап ведёт на экран джема. Строка «нет сети» Яндекса важнее и показывается вместо неё.
- **«Продолжить джем?»** (HOST-23) — `JamContinueDialog` поверх любого экрана, кроме входа, пока `storedSession`.
- **Плейлист** (HOST-34): метку строки даёт чистая `jamTrackNote(entry, isWave, jamOn, names)` по записи очереди (`QueueState.entries`), только пока джем идёт (`phase == ACTIVE`): у элемента этого джема (`item.itemId` есть, ждёт он или уже сыграл, текущий тоже) — `jam_added_by` («+ имя» по `item.addedBy` среди `room.participants`; незнакомому гостю — ничего); у записи `CONTINUATION` базы без своей волны (`!isWave`) — `jam_wave_mark` («Волна джема»), в том числе у треков волны по истории, пришедших до джема (D14); остальная база — голова, `OTHER`, своя волна «Моей волны» или станции, элементы прошлого джема — без метки, как вне джема. После конца джема меток нет. REM всегда зовёт `QueueController.removeIndices(selected)` (HOST-36): ждущие элементы джема очередь отправляет серверу (`remove{itemId}`, из плейлиста они уйдут со следующим `state`), остальные выделенные строки — трек базы, сыгранная строка, текущий трек — уходят сразу, а удаление текущего трека останавливает воспроизведение. Без связи REM доступен: строки базы убираются, а за элементы джема `JamHostEvent.NotConnected` даёт тост `jam_no_connection` (HOST-25). CLEAR оставляет текущий трек и часть джема и очищает базу. Тап по строке — переход: ждущие элементы переезжают за выбранную строку (HOST-12, `queue/README.md`). ADD ведёт на экран джема.
- **Выбор источника во время джема** (HOST-21): плейлист, альбом, артист, волна, станция и поиск в библиотеке (D5) становятся новой базой, с теми же тостами, что вне джема; ничего не начинается сразу. Треки для джема ищут на экране джема (HOST-20).
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

`HostSide` и `GuestSide` строит `AppViewModel.routeLink` из `jamHost.state` и `jamGuest.state`. `signedIn` — есть ли токен в `TokenStore`, а не `Library.isLoggedIn`: на холодном старте аккаунт ещё не загружен. `openJamLink(text, launchedFromHistory = false): Boolean` — `true`, когда ссылка принята (`AskSignIn`, `OpenHostJam`, `ShowGuest`, `OpenGuest`). Обратное исключение (D1): `JamViewModel.start()` во время гостевой сессии — тост `jam_host_while_guest`, джем не создаётся. `NoSecret` (GUEST-05) сессией не считается: членства нет и ничего не соединено, поэтому системное «назад» вместо «Закрыть» не мешает начать джем и не делает строку «Джем» библиотеки гостевой.

**`JamGuestScreen`** по `GuestPhase`:

| Фаза | Экран |
|---|---|
| `None` | `JoinJamSection` |
| `NeedName` | приглашение `jam_guest_invited`, сервер ссылки моноширинным, поле имени, «Войти» (`join`; отказ — тост `jam_guest_name_invalid`), «Отмена». Имя (GUEST-02) — `lastName`, иначе `displayName` или `login` аккаунта, через `GuestNames.prefill` (≤ 24 кодовых точки) |
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

`QiText` — `BasicText` без отступов Material. `Modifier.tap` — `clickable` без индикации. `OnOffPills(value, onText, offText, onChange)` — две `PillToggle` (подписи заглавными, по умолчанию `toggle_on` / `toggle_off`); `onChange` зовётся только при смене значения, тап по горящей пилюле ничего не делает. Ею пользуются настройки джема («Гости могут пропускать», «Новые гости» со своими подписями, «Учить волну джема», «Гости могут слушать»), строка плейлиста, блок библиотеки «Продолжать волной» и «Отправлять статистику» внизу библиотеки. `Chip` с `onLongClick` использует `combinedClickable` (тоже без индикации); в этой версии Compose он `@ExperimentalFoundationApi`, отсюда `@OptIn` на `Chip`. `VerticalFader` округляет до 0.1 дБ, тап по значению над фейдером сбрасывает полосу в 0.

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

**Долгое нажатие на строку плейлиста.** Строка — `combinedClickable` без индикации (как `Chip`, отсюда `@OptIn(ExperimentalFoundationApi::class)` на `PlaylistScreen`): тап играет строку, долгое нажатие — `showTrackActions(track)`; `onLongClickLabel = playlist_actions`: без неё TalkBack предлагает безымянное долгое нажатие. Номер слева — свой `tap` внутри строки и получает касание первым: тап по номеру выделяет строку, долгое нажатие на номер лист не открывает. Подсказка `playlist_hold_hint` стоит отдельной строкой под «ВЫБРАНО n · m:ss / h:mm:ss». Оценка по ширине знака `HintStyle` (около 7,6 dp, не замер на устройстве): справа от счётчика, рядом с `playlist_tap_hint`, ей осталось бы 120–190 dp на телефоне 360–412 dp, то есть 2–3 строки; в своей строке ей нужно около 266 dp по-английски, 296 по-русски и 319 по-белорусски, и на 360 dp (328 dp внутри отступов) она помещается в одну.

`TrackActionsSheet(actions, viewModel)` рисуется из `AppRoot` поверх всего приложения, включая мини-плеер и вкладки, пока `trackActions != null`: затемнение (тап закрывает) и панель снизу — название и исполнители трека и три строки:

| Строка | Подпись | Действие |
|---|---|---|
| лайк | `player_like` с контуром сердца или `playlist_unlike` с залитым, по `liked` | `likeOrUnlikePicked()` |
| дизлайк | `player_dislike_skip`, когда `isCurrent`, иначе `library_dislike` | `dislikePicked()` |
| браузер | `playlist_open` | `Intent.ACTION_VIEW` с `track.webUrl`, как кнопка библиотеки; `Intent` нужен `Context`, поэтому живёт в листе |

У строк `Role.Button`, подпись строки — её текст для TalkBack. Панель листа — `paneTitle = playlist_actions`, поэтому TalkBack объявляет лист, когда тот открывается. Каждое действие закрывает лист; «назад» — тоже (`BackHandler` в `AppRoot`).

**Traps (`TrackActionsSheet`):**
- Панель получает `navigationBarsPadding()` после фона. `MainActivity` включает `enableEdgeToEdge()`, а `AppRoot` отступает от системных полос только в колонке экранов; лист рисуется вне её, и без отступа последняя строка (браузер) легла бы под трёхкнопочную навигацию (48 dp), где тапы уходят системе.
- Панель ловит касания сама (`pointerInput { detectTapGestures() }`): иначе тап по названию трека прошёл бы к затемнению под ней и закрыл лист.
- Подпись дизлайка выбирается при открытии листа, а очередь решает при нажатии. Если текущий трек сменился, пока лист открыт, подпись может разойтись с тем, что произойдёт, в любую сторону; поведение при этом верное (TR-10), и десктоп устроен так же: подпись строится вместе с меню, проверка идёт при действии.

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

Все тексты — `res/values/strings.xml` (английский), `res/values-be/strings.xml` (белорусский) и `res/values-ru/strings.xml`; `queue_loaded`, `queue_stopped_after_failures` и `jam_guest_limit_hint` — `plurals` (у белорусского и русского формы `one`, `few`, `many`, `other`). «Волна» Яндекса по-английски — **vibe** («My Vibe», «Jam vibe», «Wheel of vibes»), не wave; ключи (`library_my_wave`…) остались прежними. Слова те же, что на ПК: таблица в `translations/README.md` репозитория QiYaa. `StringsTest` проверяет, что каждый текст переведён на оба языка с теми же подстановками, формы plurals и что ни один английский текст не говорит «wave».

**Качество звука** (`spec/player/quality.md`). Внизу библиотеки, над выбором языка: заголовок `quality_title`, строки «Wi-Fi» (`quality_wifi`) и «Мобильная сеть» (`quality_mobile`) с двумя `PillToggle` — `quality_standard` и `quality_economy` — и пояснение `quality_note`. Выбор уходит в `AppViewModel.setQuality` / `setQualityMetered` и действует со следующего трека, даже уже подгруженного (QUAL-05: сервис загружает его заново, `playback/README.md`, кэш ссылок); трек, который играет, доигрывает своим файлом. Поэтому `quality_note` («Со следующего трека») верен и строк не прибавилось. Примечание к «Слушать вместе» (`jam_share_audio_note`) говорит, что слушатели получают файлы только при стандартном качестве (LISTEN-13).

**Отправлять статистику** (TEL-02, `docs/telemetry.md`). Самая нижняя строка библиотеки, под выбором языка, и только в сборке с телеметрией: `AppViewModel.telemetryAvailable` (`appGraph.telemetry != null`, то есть `-PqiyaaWithTelemetry=true`, `docs/release.md`); без неё строки нет (TEL-01). Подпись `telemetry_title` в `LabelStyle`, `OnOffPills` по `Settings.telemetryEnabled` с подписями по умолчанию `toggle_on` / `toggle_off` и пояснение `telemetry_note` в `HintStyle` — оно и есть раскрытие: телеметрия включена с первого запуска без вопроса, как у десктопа. `AppViewModel.setTelemetryEnabled(on)` только пишет настройку (`telemetry/enabled`, `data/README.md`); выключение (забыть запуск и удалить файлы) и новый запуск со своим `start` при включении делает `telemetry/TelemetrySession` на своём потоке, не на главном.

**Язык интерфейса — свой, не системный.** `data/Settings.language` (по умолчанию белорусский). `AppLocale` даёт его приложению и `PlaybackService` в `attachBaseContext` (контекст с локалью) и активности — через `applyOverrideConfiguration`, чтобы она по-прежнему следила за экраном и тёмной темой. `LanguageSwitch` (коды `BE RU EN`, текущий подсвечен) стоит на экране входа (сменить язык можно до входа) и внизу библиотеки с подписью `language_title` и английским словом «LANGUAGE», чтобы непонятный язык можно было отменить. Выбор: `AppViewModel.setLanguage` сохраняет язык и переводит ресурсы приложения (`AppLocale.apply`), экран пересоздаёт активность; сервис перестраивает кнопки уведомления под новый язык. Системная смена конфигурации сбрасывает локаль ресурсов приложения и сервиса — `onConfigurationChanged` применяет язык заново. В сборке `bundle.language.enableSplit = false`: все языки должны быть в установке. Ключи `jam_*` — экраны джема; `toggle_on` / `toggle_off` — подписи `OnOffPills` по умолчанию (до #79 — `jam_on` / `jam_off`, тексты те же: On / Off, Да / Нет, Так / Не); `vibe_continue_*` — «Продолжать волной» в плейлисте и библиотеке; `telemetry_title`, `telemetry_note` — «Отправлять статистику»; `jam_refused_*` — причины отказа сервера: все 18 значений `reason` из `spec/jam/protocol/schemas/defs.schema.json` (`JamEventTextTest`), `kicked` — `jam_guest_kicked`, неизвестная причина — `jam_refused_other`, а `queue-limit` гостю — `jam_refused_guest_queue_limit` (его собственный лимит), хозяину — `jam_refused_queue_limit`; `jam_join_*` — «Войти в джем» и отказы ссылки, `jam_host_while_guest`, `jam_library_guest`, `jam_guest_*` — экраны гостя (`jam_guest_sending` — «…», `translatable="false"`); `playlist_hold_hint`, `playlist_actions` (подпись долгого нажатия для TalkBack), `playlist_unlike`, `playlist_open` — долгое нажатие и лист действий с треком, а дизлайк в листе берёт готовые `library_dislike` и `player_dislike_skip`. Ключи `queue_*` рендерят `QueueEvent` (в том числе `queue_waiting_for_network` и plurals `queue_stopped_after_failures`), `theme_*` — подписи `AccentTheme`.

## Not here

- Логика очереди (что играть дальше, догрузка волны) — `queue/QueueController`. Ключи настроек — `data/README.md`.

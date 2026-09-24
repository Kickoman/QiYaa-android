# QiYaa for Android

Android-порт [QiYaa](https://github.com/Kickoman/QiYaa) — плеера Яндекс Музыки в стиле Winamp. Интерфейс сделан по мобильной дизайн-системе «Winamp-inspired mobile music player» (Claude Design), а не по десктопным скинам: тёмные поверхности, один LED-акцент (green / amber / ice), IBM Plex Mono для показаний, IBM Plex Sans для контента, четыре вкладки — Player · Playlist · EQ · Library.

## Что умеет

- **Вход** через код устройства (ya.ru/device) или вставкой адреса `music.yandex.ru/#access_token=…` / самого токена. Токен хранится в приватных SharedPreferences приложения, резервное копирование отключено.
- **Библиотека**: Моя волна (бесконечная, догружается по ходу), «Мне нравится», плейлисты, исполнители (популярные треки), альбомы, станции по типам, поиск (лучший результат: исполнитель / альбом / треки). Нравится / не нравится (с пропуском) / открыть трек в браузере. Отметки прослушивания отправляются, как в десктопной версии.
- **Плеер**: обложка, спектр (19 полос) → осциллограф → выкл, бегущая строка с kbps/kHz/стерео, перемотка, оставшееся время по тапу, shuffle / repeat, громкость и баланс. Фоновое воспроизведение с уведомлением и кнопками гарнитуры (Media3 `MediaSessionService`).
- **Плейлист**: выбор по номеру, REM / ALL / CLEAR, время выбранных и общее.
- **Эквалайзер**: 10 полос Winamp (60 Гц … 16 кГц) ± 12 дБ + преамп, 17 встроенных пресетов, график, ON / AUTO. Сам DSP — те же RBJ-биквады, что и в десктопной версии, встроены в аудио-конвейер ExoPlayer.
- Тап по надписи **QIYAA** на экране плеера переключает акцентную тему.

## Сборка

Нужны JDK 17 и Android SDK (platform 35, build-tools 35). Шрифты уже лежат в `app/src/main/res/font`; при необходимости обновить — `bash scripts/fetch-fonts.sh`.

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64      # если по умолчанию другой JDK
printf 'sdk.dir=%s\n' "$HOME/android-sdk" > local.properties   # или export ANDROID_HOME
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # JVM-тесты: подпись ссылок, OAuth, API против mock-сервера, EQ, FFT
./gradlew lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Минимальная версия Android — 8.0 (API 26).

## Проверка вручную

1. Запустить, войти по коду устройства (открыть ya.ru/device в браузере, ввести код) — приложение само перейдёт в Библиотеку.
2. Library → My Wave: очередь заполняется, играет, при приближении к концу догружается.
3. Player: тап по визуализации меняет режим, тап по времени — оставшееся, слайдер перематывает.
4. EQ: пресет Rock слышно, ON выключает; значения сохраняются после перезапуска.
5. Свернуть приложение — воспроизведение продолжается, в шторке есть уведомление с управлением.

## Структура

```
app/src/main/java/io/github/kickoman/qiyaa/
  yandex/    YandexApi (эндпоинты, конверт {invocationInfo,result}), TrackUrl (подпись mp3-ссылки),
             DeviceAuth (код устройства), TokenNormalizer, Library (источники, лайки)
  audio/     EqualizerDsp + EqPresets (порт Equalizer.cpp / EqPresets.h), Analyzer + Spectrum (порт Visualizers.cpp),
             VisTap, Media3-процессоры (EqualizerProcessor, VisTapProcessor), AudioBus
  playback/  PlaybackService (MediaSessionService + ExoPlayer), TrackResolver (qiyaa://track/{id} → подписанный URL),
             QueueManager (очередь, волна, отметки прослушивания, dislike = skip), MediaItems
  data/      Settings, TokenStore
  ui/        Compose: theme (токены дизайн-системы), components, screens, vis, AppRoot, ViewModels
app/src/test/  JUnit-тесты (порт tests/test_yandex.cpp и tests/test_library.cpp + DSP)
```

## Лицензия

MIT, как и у оригинала. Сторонние компоненты — в `THIRD_PARTY.md`.

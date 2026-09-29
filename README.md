# QiYaa for Android

Android-порт [QiYaa](https://github.com/Kickoman/QiYaa) — плеера Яндекс Музыки в стиле Winamp. Интерфейс сделан по мобильной дизайн-системе «Winamp-inspired mobile music player» (Claude Design), а не по десктопным скинам: тёмные поверхности, один LED-акцент (green / amber / ice), IBM Plex Mono для показаний, IBM Plex Sans для контента.

| Экран | Что умеет |
|---|---|
| **Вход** | код устройства (ya.ru/device) или вставка адреса `music.yandex.ru/#access_token=…` / самого токена. Токен — в приватных SharedPreferences, резервное копирование отключено |
| **Player** | обложка, спектр (19 полос) → осциллограф → выкл, бегущая строка с kbps/kHz/стерео, перемотка, оставшееся время по тапу, shuffle / repeat, громкость и баланс, лайк / дизлайк. Тап по надписи QIYAA переключает акцент |
| **Playlist** | выбор по номеру, ADD / REM / ALL / CLEAR, время выбранных и общее |
| **EQ** | 10 полос Winamp (60 Гц … 16 кГц) ± 12 дБ + преамп, 17 встроенных пресетов, график, ON / AUTO |
| **Library** | Моя волна (бесконечная), «Мне нравится», плейлисты, исполнители (популярные треки), альбомы, станции по типам, поиск (лучший результат: исполнитель / альбом / треки), открыть трек в браузере |

Фоновое воспроизведение с уведомлением и кнопками гарнитуры (Media3 `MediaSessionService`). DSP — те же RBJ-биквады, что и в десктопной версии, встроены в аудио-конвейер ExoPlayer. Отметки прослушивания отправляются, как в десктопной версии.

## Сборка и проверка

Нужны JDK 17 и Android SDK (platform 35, build-tools 35). Минимальная версия Android — 8.0 (API 26). Шрифты уже лежат в `app/src/main/res/font`; обновить — `bash scripts/fetch-fonts.sh`.

```sh
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64      # если по умолчанию другой JDK
printf 'sdk.dir=%s\n' "$HOME/android-sdk" > local.properties   # или export ANDROID_HOME
./gradlew ktlintCheck assembleDebug testDebugUnitTest lintDebug   # полная проверка перед сдачей
./gradlew ktlintFormat                                            # поправить форматирование
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

CI ([`.github/workflows/ci.yml`](.github/workflows/ci.yml)) выполняет ту же проверку на каждый push и pull request и собирает release-APK. Сборка обязана быть чистой: предупреждения Kotlin — ошибки (`allWarningsAsErrors`), ktlint по `.editorconfig` (стиль `android_studio`, строка ≤ 110), Android lint с `warningsAsErrors` (отключены только проверки свежести зависимостей и `targetSdk`: их результат зависит от того, что установлено на машине). Правила кода — в `CLAUDE.md`.

## Релизы

Готовые APK лежат в [Releases](https://github.com/Kickoman/QiYaa-android/releases) (появляются, когда в репозиторий пушится тег вида `v0.2.0`) и в артефактах каждого прогона GitHub Actions (неподписанные, для проверки). Версия приложения берётся из тега, все релизы подписаны одним ключом. Как выпустить релиз и настроить ключ — [docs/release.md](docs/release.md).

## Проверка вручную

1. Запустить, войти по коду устройства (открыть ya.ru/device в браузере, ввести код) — приложение само перейдёт в Библиотеку.
2. Library → My Wave: очередь заполняется, играет, при приближении к концу догружается.
3. Player: тап по визуализации меняет режим, тап по времени — оставшееся, слайдер перематывает.
4. EQ: пресет Rock слышно, ON выключает; значения сохраняются после перезапуска.
5. Свернуть приложение — воспроизведение продолжается, в шторке есть уведомление с управлением.

## Модули

Один Gradle-модуль `:app`; модули — пакеты под `app/src/main/java/io/github/kickoman/qiyaa/`, у каждого свой `README.md`. Зависимости направлены только вниз по таблице.

| Пакет | Что делает | README |
|---|---|---|
| `yandex/` | API Яндекс Музыки, OAuth по коду устройства, подпись mp3-ссылки, источники библиотеки. Чистая JVM | [yandex/README.md](app/src/main/java/io/github/kickoman/qiyaa/yandex/README.md) |
| `audio/` | Эквалайзер, FFT, спектр, кольцо визуализатора, PCM16. Чистая JVM | [audio/README.md](app/src/main/java/io/github/kickoman/qiyaa/audio/README.md) |
| `data/` | Настройки и токен в SharedPreferences | [data/README.md](app/src/main/java/io/github/kickoman/qiyaa/data/README.md) |
| `playback/` | ExoPlayer + MediaSession, очередь и волна, разрешение ссылок, аудиопроцессоры | [playback/README.md](app/src/main/java/io/github/kickoman/qiyaa/playback/README.md) |
| `ui/` | Compose: экраны, view model, тема | [ui/README.md](app/src/main/java/io/github/kickoman/qiyaa/ui/README.md) |
| корень | `AppGraph` (ручной граф зависимостей, один на процесс), `QiYaaApp` | — |
| `app/src/test/` | JVM-тесты | [test/README.md](app/src/test/README.md) |

Как трек доходит до динамика: `Library` → `QueueManager` → `MediaItem` с виртуальным `qiyaa://track/{id}` → ExoPlayer → `TrackResolver` подписывает ссылку при открытии → `OkHttpDataSource` → `EqualizerProcessor` → `VisualizerTapProcessor` → `AudioTrack`. Подробности — в `playback/README.md`.

## Лицензия

MIT, как и у оригинала. Сторонние компоненты — в `THIRD_PARTY.md`.

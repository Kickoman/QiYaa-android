# Сборка в CI и релизы

Workflow `.github/workflows/ci.yml` устроен так же, как в [QiYaa](https://github.com/Kickoman/QiYaa): один job `build` на каждый push, pull request и ручной запуск, плюс job `release`, который срабатывает только на тегах `v*`.

## Что делает CI

`build` (Ubuntu 24.04, Temurin 17, кэш Gradle через `gradle/actions/setup-gradle`) выполняет ту же проверку, что требует `CLAUDE.md`, и собирает release-APK:

```sh
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleRelease -PqiyaaWithTelemetry=true [-PqiyaaVersion=<тег>]
```

`-PqiyaaWithTelemetry=true` — на каждом прогоне, как в десктопном CI: APK из веток неподписанные и никем не ставятся, а APK тега — это релиз, и в нём должна быть телеметрия ([telemetry.md](telemetry.md)). Флаг даёт `BuildConfig.TELEMETRY = true` и `BuildConfig.TELEMETRY_URL` — Gradle-свойство `qiyaaTelemetryUrl`, по умолчанию `<qiyaaJamUrl>/api/telemetry` (`https://qiyaa.kanstancin.net/api/telemetry`). Адрес обязан быть `https://` с хостом, иначе конфигурация Gradle падает: в манифесте `usesCleartextTraffic="false"`. Без флага `TELEMETRY = false`, `TELEMETRY_URL = ""`, и приложение телеметрию не запускает (TEL-01).

```sh
./gradlew assembleRelease -PqiyaaWithTelemetry=true
grep TELEMETRY app/build/generated/source/buildConfig/release/io/github/kickoman/qiyaa/BuildConfig.java
```

Результат загружается артефактом `QiYaa-Android`:

| Запуск | Файлы | Подпись | Версия |
|---|---|---|---|
| ветка, PR, ручной | `QiYaa-0.0.0-dev-<sha7>-unsigned.apk`, `QiYaa-0.0.0-dev-<sha7>-mapping.txt` | нет | `0.0.0-dev`, `versionCode 1` |
| тег `v1.2.3` | `QiYaa-1.2.3.apk`, `QiYaa-1.2.3-mapping.txt` | ключ проекта | `1.2.3`, `versionCode 10203` |

Release собирается R8 (Kickoman/QiYaa-android#22): `isMinifyEnabled` и `isShrinkResources` в `app/build.gradle.kts`, правила — `proguard-android-optimize.txt` из AGP, правила самих библиотек и `app/proguard-rules.pro`. В нём одно правило: имена наследников `Throwable` остаются читаемыми для телеметрии ([telemetry.md](telemetry.md)). Своих `-keep` нет: приложение не использует рефлексию, а сериализаторы kotlinx.serialization передаются явно (`X.serializer()`). R8 выкидывает неиспользуемый код и ресурсы и переименовывает классы и методы, поэтому стек из logcat или из отчёта о падении читается только через `mapping.txt` той же сборки (ниже, «Расшифровать стек»). CI кладёт его рядом с APK (около 50 МБ текста, в артефакте сжимается), а на теге `release` публикует его в Releases вместе с APK. Debug-сборки R8 не трогает.

Неподписанный APK из ветки не установится как есть; он нужен, чтобы проверить, что release собирается. Если проверка упала, отчёты ktlint, lint и тестов из `app/build/reports/` загружаются артефактом `reports` на 7 дней.

На теге CI дополнительно проверяет подпись (`apksigner verify --print-certs`, SHA-256 сертификата попадает в лог) и сверяет `versionCode`/`versionName` из `aapt2 dump badging` с тегом. Затем `release` публикует APK и его `mapping.txt` в GitHub Releases с автоматически собранными release notes (`softprops/action-gh-release`, `generate_release_notes: true`).

## Расшифровать стек

В release-сборке кадр стека выглядит так: `at sk2.u(r8-map-id-be690cc…:11)`. Класс и метод переименованы, вместо файла — id mapping, а вместо строки — позиция в методе: R8 убирает таблицы строк, и с `minSdk 26` ART показывает смещение инструкции плюс один, а mapping переводит его в настоящую строку. Нужен `mapping.txt` именно этой сборки: `# pg_map_id` в его шапке совпадает с id в кадре. Для релиза он лежит в Releases, для ветки — в артефакте `QiYaa-Android` прогона CI, для локальной сборки — `app/build/outputs/mapping/release/mapping.txt`.

```sh
adb logcat -d -s AndroidRuntime > stack.txt          # или вставить стек в файл руками
curl -fLO https://github.com/Kickoman/QiYaa-android/releases/download/v0.7.0/QiYaa-0.7.0-mapping.txt
head -7 QiYaa-0.7.0-mapping.txt | grep pg_map_id      # тот же id, что в кадрах
~/android-sdk/cmdline-tools/latest/bin/retrace QiYaa-0.7.0-mapping.txt stack.txt
```

`retrace` оставляет префиксы logcat как есть и разворачивает встроенные (inline) методы в несколько кадров. Если `retrace` из cmdline-tools окажется старше формата mapping, тот же Retrace есть внутри AGP:

```sh
java -cp ~/.gradle/caches/modules-2/files-2.1/com.android.tools.build/builder/9.3.3/*/builder-9.3.3.jar \
    com.android.tools.r8.retrace.Retrace QiYaa-0.7.0-mapping.txt stack.txt
```

Проверено 2026-10-18 на mapping локальной release-сборки (R8 9.3.28, формат mapping 2.2): `retrace` 8.2.33 из cmdline-tools 12.0 и Retrace 9.3.28 из AGP 9.3.3 дают одно и то же, в том числе с префиксами logcat. Кадры для проверки взяты из таблицы позиций самого dex (`dexdump -d`), а не из падения на устройстве. Кадры из отчётов телеметрии — [telemetry.md](telemetry.md#как-прочитать-кадры-release-сборки).

## Проверка release-APK на телефоне

R8 и Media3 1.11 (Kickoman/QiYaa-android#22) на устройстве не проверялись. **Первый тег после #22 ждёт этой проверки** (Kickoman/QiYaa-android#91): иначе в релиз уйдут сборка R8 и поведение Media3 1.11, которых никто не запускал.

Установочная сборка: задать четыре переменные `QIYAA_*` (ниже, «Ключ подписи») и собрать

```sh
./gradlew assembleRelease -PqiyaaWithTelemetry=true -PqiyaaVersion=v<следующая версия>
adb install -r app/build/outputs/apk/release/app-release.apk
```

Она подписана ключом проекта и с большим `versionCode`, поэтому встаёт поверх текущего релиза с токеном и настройками. Откатиться на старую версию без удаления нельзя: Android не ставит меньший `versionCode` поверх большего, а у не-debuggable APK без root нет `adb install -d`. Удаление стирает токен, настройки и очередь.

Всё время держать открытым `adb logcat -s QiYaa AndroidRuntime`: не должно быть `ClassNotFoundException`, `NoSuchMethodError`, `SerializationException`, «Serializer for class … is not found» и падений.

- [ ] Обновление поверх прошлого релиза сохраняет токен, настройки, EQ, язык и сохранённую очередь (`queue.json`): тот же трек, индекс и позиция.
- [ ] Вход по коду устройства после очистки данных приложения.
- [ ] **Моя волна** играет и догружается у конца. Лайк и дизлайк работают. Колесо волны работает.
- [ ] Библиотека: Мне нравится, Для вас, плейлисты (долгое нажатие → похожие, лайк, «открыть в браузере»), исполнители, альбомы, станции, поиск.
- [ ] **Обложка грузится на экране плеера** (Coil 3 под R8) и в «Сейчас играет» гостя джема.
- [ ] Обложка в уведомлении и на экране блокировки (Media3 грузит её через Coil, `playback/CoverBitmapLoader`), в том числе без сети после перезапуска (пункт 13 в [README](../README.md#проверка-вручную)).
- [ ] Shuffle: в перемешанной очереди выбранный трек играет первым, «вперёд» идёт по порядку, который показывает плейлист (CONT-09: `HeadShuffleOrder.cloneAndSet`). Удаление текущей строки в перемешанной очереди переходит к следующей по порядку.
- [ ] Плеер: спектр, потом осциллограф, потом выкл. Картинка **совпадает со звуком и не дёргается** (динамическое планирование Media3 1.11). В бегущей строке kbps/kHz. Перемотка и оставшееся время. Заполнить таблицу задержки в `audio/README.md` по `Visualizer: the newest written audio sounds in N ms` для проводного выхода и Bluetooth, с устройством и пометкой «release».
- [ ] EQ: пресет Rock слышно, ON выключает, баланс работает, значения переживают перезапуск.
- [ ] Уведомление: появляется при игре. «Назад», «плей/пауза», «вперёд» работают. **♥** следует за текущим треком и за лайками из приложения, **👎** пропускает; ♥ и 👎 — в развёрнутом виде после трёх кнопок. Подписи следуют за сменой языка. Также экран блокировки и кнопка гарнитуры.
- [ ] Фон: свернуть и заблокировать больше чем на 10 минут — игра идёт. На паузе дольше 10 минут — продолжение из уведомления работает. Смахнуть приложение из недавних на паузе — сервис останавливается и уведомление уходит (`pauseAllPlayersAndStopSelf`); во время игры — игра продолжается.
- [ ] Возобновление: после force-stop или перезагрузки системная карточка медиа или «плей» гарнитуры возобновляют очередь.
- [ ] ERR-09: режим полёта во время игры — ожидание; выключить — тот же трек продолжается. С выключенным экраном во время ожидания уведомление остаётся (10 минут на переднем плане).
- [ ] Качество: «Экономия» на мобильной сети показывает `64K`, «Стандарт» на Wi-Fi — `192K`.
- [ ] Джем: начать, QR-код виден. **Скопировать ссылку** кладёт URL в буфер. Гость из браузера входит и добавляет трек. Убить и открыть приложение → «Продолжить джем?» восстанавливает его (`jam.json`). Закончить джем.
- [ ] Джем гостем в приложении: «Вставить» берёт ссылку из буфера, вход, «Добавить треки», выход.
- [ ] Язык be/ru/en. Открыть трек в браузере. Поделиться ссылкой джема.
- [ ] Телеметрия (сборка с `-PqiyaaWithTelemetry=true`): строка «Отправлять статистику» внизу Library есть, в logcat `Telemetry: HTTP 204` (или `HTTP 400 … dropped`, пока сервер не принимает Android). Если за время проверки было падение, следующий запуск отправляет отчёт, и его кадры расшифровываются по `mapping.txt` этой сборки ([telemetry.md](telemetry.md#как-прочитать-кадры-release-сборки)).
- [ ] Если есть: Android 8.0 (`minSdk 26`), Android 13+ (запрос разрешения на уведомления), Android 14/15.

## Версия

Версия не хранится в коде: её задаёт тег через свойство Gradle `qiyaaVersion`.

- `v<major>.<minor>.<patch>` → `versionName = "<major>.<minor>.<patch>"`, `versionCode = major·10000 + minor·100 + patch`.
- `minor` и `patch` должны быть меньше 100, иначе сборка падает: иначе `v1.100.0` и `v2.0.0` дали бы один `versionCode`.
- Тег другого вида (`v1.2`, `v1.2.3-rc1`) — ошибка конфигурации Gradle. Предрелизы этой схемой не поддерживаются.
- Без `qiyaaVersion` сборка получает `0.0.0-dev` и `versionCode 1`.

Проверить локально:

```sh
./gradlew assembleRelease -PqiyaaVersion=v1.2.3     # нужны переменные подписи, см. ниже
~/android-sdk/build-tools/36.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -1
```

## Ключ подписи

Все релизы подписываются одним ключом: Android ставит обновление поверх установленного приложения только при совпадении сертификата. Gradle берёт ключ из четырёх переменных окружения; если хотя бы одна не задана, release собирается неподписанным, а сборка с `qiyaaVersion` падает с перечнем недостающих.

| Переменная Gradle | Секрет GitHub | Что это |
|---|---|---|
| `QIYAA_KEYSTORE_PATH` | `ANDROID_KEYSTORE_BASE64` | файл keystore; в секрете — его base64, CI раскодирует его в `$RUNNER_TEMP/release.jks` |
| `QIYAA_KEYSTORE_PASSWORD` | `ANDROID_KEYSTORE_PASSWORD` | пароль хранилища |
| `QIYAA_KEY_ALIAS` | `ANDROID_KEY_ALIAS` | алиас ключа |
| `QIYAA_KEY_PASSWORD` | `ANDROID_KEY_PASSWORD` | пароль ключа |

Один раз создать ключ и положить его в секреты:

```sh
keytool -genkeypair -v -keystore qiyaa-release.jks -alias qiyaa \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=QiYaa"
base64 -w0 qiyaa-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R Kickoman/QiYaa-android
gh secret set ANDROID_KEYSTORE_PASSWORD -R Kickoman/QiYaa-android     # спросит значение
gh secret set ANDROID_KEY_ALIAS -R Kickoman/QiYaa-android -b qiyaa
gh secret set ANDROID_KEY_PASSWORD -R Kickoman/QiYaa-android
```

Без `gh` — то же через Settings → Secrets and variables → Actions → New repository secret. `*.jks` и `*.keystore` в `.gitignore`; сам файл храни вне репозитория и с резервной копией.

## Выпустить релиз

```sh
git switch master && git pull
git tag v0.1.0
git push origin v0.1.0
```

Через несколько минут в Releases появляется `QiYaa-0.1.0.apk`.

**Traps:**
- Потерянный или заменённый keystore означает, что новые APK не встанут поверх старых: пользователям придётся удалить приложение вместе с настройками и токеном.
- `versionCode` обязан расти: Android не ставит APK с меньшим `versionCode` поверх большего. Теги должны идти по возрастанию.
- Тег без секретов не публикуется: job `build` падает на шаге «Release signing key» с именем недостающего секрета, `release` не запускается.
- Секреты недоступны в PR из форков; такие PR собираются неподписанными, как и любая ветка.
- Перевыпустить тот же тег: удалить релиз и тег на GitHub (`git push --delete origin v0.1.0`), затем поставить тег заново.
- Первый релиз с телеметрией примет только сервер QiYaa-jam, который знает пакеты `app: "android"`: PR Kickoman/QiYaa-jam#19 (ждёт Kickoman/QiYaa-jam#17; на 2026-10-17 не смержен и не задеплоен). Пока на `qiyaa.kanstancin.net` старый сервер, каждый пакет получает 400, а 400 приложение по спеке выбрасывает: события и отчёты о падениях этого периода теряются молча, ничего не ломается и не повторяется. Перед тегом проверить сервер (204 — принимает, 400 — ещё нет):

  ```sh
  curl -s -o /dev/null -w '%{http_code}\n' -H 'content-type: application/json' \
      --data @spec/telemetry/examples/android-start.json https://qiyaa.kanstancin.net/api/telemetry
  ```

  Проверка оставляет на сервере одно событие примера под машиной примеров спеки.

## App Links

`MainActivity` объявляет ссылки `https://<сервер джема>/j/*` с `android:autoVerify="true"` (GUEST-21, `spec/jam/guest.md`): проверенная ссылка из мессенджера или камеры открывает гостя в приложении сразу. Хост и префикс пути — из `qiyaaJamUrl` (`gradle.properties`) через плейсхолдеры манифеста `jamHost` и `jamPathPrefix` (`app/build.gradle.kts`):

| `qiyaaJamUrl` | `jamHost` | `jamPathPrefix` |
|---|---|---|
| `https://qiyaa.kanstancin.net` (по умолчанию) | `qiyaa.kanstancin.net` | `/j/` |
| `https://jam.example.org/x` (`-PqiyaaJamUrl=…`) | `jam.example.org` | `/x/j/` |
| пусто или не URL | `jam.invalid` | `/j/` |

`.invalid` не резолвится никогда, а пустой хост сломал бы lint `AppLinkUrlError`. У каждого `<data>` один атрибут (`scheme`, `host`, `pathPrefix`): этого требует lint `IntentFilterUniqueDataAttributes`, а у нас `warningsAsErrors`. Схема только `https`: `usesCleartextTraffic="false"`. Проверить манифест собранного APK:

```sh
~/android-sdk/build-tools/36.0.0/aapt2 dump xmltree app/build/outputs/apk/debug/app-debug.apk --file AndroidManifest.xml | grep -B2 -A8 autoVerify
```

Android проверяет домен при установке по `https://<хост>/.well-known/assetlinks.json`. Пока файла нет (на 2026-10-17 сервер отвечает 404) или в нём чужой отпечаток, ссылка не подтверждена: Android 12+ открывает браузер, то есть веб-гостя (рабочая замена, а не поломка), Android 8–11 предлагают выбор.

**Отпечаток релизного ключа** (SHA-256 сертификата `CN=QiYaa`; посчитан 2026-10-17 `apksigner verify --print-certs` по `QiYaa-0.5.9.apk` и `QiYaa-0.5.0.apk` из Releases, у обоих один и тот же):

```
8D:E3:0B:9B:C1:26:64:C4:87:BF:48:A0:FC:F5:4D:B7:E5:72:DF:54:05:8E:39:94:4D:52:77:18:3F:54:A5:99
```

Посчитать заново (`keytool -printcert -jarfile` APK не прочтёт: при `minSdk 26` AGP подписывает только схемой v2):

```sh
curl -fLO https://github.com/Kickoman/QiYaa-android/releases/download/v0.5.9/QiYaa-0.5.9.apk
~/android-sdk/build-tools/36.0.0/apksigner verify --print-certs QiYaa-0.5.9.apk \
    | grep 'SHA-256 digest' | awk '{print $NF}' | tr a-f A-F | sed 's/../&:/g; s/:$//'
```

Тот же отпечаток печатает CI на каждом теге (шаг `apksigner verify --print-certs`) и `keytool -list -v -keystore qiyaa-release.jks -alias qiyaa | grep SHA256`.

**Шаги владельца** (сервер джема на VPS; из репозитория их не сделать):

1. В `~/qiyaa-jam/.env` добавить строку — **в одинарных кавычках**, чтобы compose оставил JSON как есть:

   ```
   ASSETLINKS_JSON='[{"relation":["delegate_permission/common.handle_all_urls"],"target":{"namespace":"android_app","package_name":"io.github.kickoman.qiyaa","sha256_cert_fingerprints":["8D:E3:0B:9B:C1:26:64:C4:87:BF:48:A0:FC:F5:4D:B7:E5:72:DF:54:05:8E:39:94:4D:52:77:18:3F:54:A5:99"]}}]'
   ```

2. Передеплоить **тот же** тег: `cd ~/qiyaa-jam && ./deploy.sh "$(tail -1 deployed.log | awk '{print $2}')"`. Не голый `docker compose up -d`: без `--env-file tag.env` `JAM_TAG` станет `edge`. Комнаты переживают перезапуск (REC-10).
3. Проверить, что файл отдаётся:

   ```sh
   curl -i https://qiyaa.kanstancin.net/.well-known/assetlinks.json   # 200, application/json, без редиректа
   curl 'https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://qiyaa.kanstancin.net&relation=delegate_permission/common.handle_all_urls'
   ```

4. Поставить **релизную** сборку с этим фильтром (первый тег после #63) и на телефоне:

   ```sh
   adb shell pm verify-app-links --re-verify io.github.kickoman.qiyaa
   adb shell pm get-app-links io.github.kickoman.qiyaa     # qiyaa.kanstancin.net: verified
   ```

   Без adb: Настройки → Приложения → QiYaa → «Открывать по умолчанию».
5. Debug-сборки подписаны другим ключом и проверку не пройдут. Для них ссылку включают вручную:

   ```sh
   adb shell pm set-app-links-user-selection --user cur --package io.github.kickoman.qiyaa true qiyaa.kanstancin.net
   ```

   или вторым элементом `sha256_cert_fingerprints` добавляют отпечаток debug-ключа: `keytool -list -v -keystore ~/.android/debug.keystore -alias androiddebugkey -storepass android | grep SHA256`.

**Traps:**
- Без adb неподтверждённую ссылку пользователь включает сам: Настройки → Приложения → QiYaa → Открывать по умолчанию → Добавить ссылку → `qiyaa.kanstancin.net`. «Войти в джем» (вставить ссылку) на экране джема работает всегда.
- Подтверждённые ссылки открывают приложение, поэтому на телефоне с QiYaa до «Слушать» веб-гостя (LISTEN-07…12) доходят, только открыв ссылку в браузере вручную (`spec/jam/guest.md`).
- App Links ловят только хост сборки; ссылки на другие серверы вставляют (GUEST-01).
- Новый ключ подписи — новый отпечаток в `ASSETLINKS_JSON`, иначе проверка не пройдёт ни у кого.
- Встроенные браузеры некоторых мессенджеров App Links не открывают, а некоторые теряют часть ссылки после `#`: тогда гость без членства видит «нет секрета» (GUEST-05).
- Сетевые проверки lint `AppLinksAutoVerify*` по умолчанию выключены; не включать: их результат зависит от сети и сервера, а не от кода.

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

| Запуск | Файл | Подпись | Версия |
|---|---|---|---|
| ветка, PR, ручной | `QiYaa-0.0.0-dev-<sha7>-unsigned.apk` | нет | `0.0.0-dev`, `versionCode 1` |
| тег `v1.2.3` | `QiYaa-1.2.3.apk` | ключ проекта | `1.2.3`, `versionCode 10203` |

Неподписанный APK из ветки не установится как есть; он нужен, чтобы проверить, что release собирается. Если проверка упала, отчёты ktlint, lint и тестов из `app/build/reports/` загружаются артефактом `reports` на 7 дней.

На теге CI дополнительно проверяет подпись (`apksigner verify --print-certs`, SHA-256 сертификата попадает в лог) и сверяет `versionCode`/`versionName` из `aapt2 dump badging` с тегом. Затем `release` публикует APK в GitHub Releases с автоматически собранными release notes (`softprops/action-gh-release`, `generate_release_notes: true`).

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

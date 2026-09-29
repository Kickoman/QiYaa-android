# Сборка в CI и релизы

Workflow `.github/workflows/ci.yml` устроен так же, как в [QiYaa](https://github.com/Kickoman/QiYaa): один job `build` на каждый push, pull request и ручной запуск, плюс job `release`, который срабатывает только на тегах `v*`.

## Что делает CI

`build` (Ubuntu 24.04, Temurin 17, кэш Gradle через `gradle/actions/setup-gradle`) выполняет ту же проверку, что требует `CLAUDE.md`, и собирает release-APK:

```sh
./gradlew ktlintCheck testDebugUnitTest lintDebug assembleRelease [-PqiyaaVersion=<тег>]
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
~/android-sdk/build-tools/35.0.0/aapt2 dump badging app/build/outputs/apk/release/app-release.apk | head -1
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

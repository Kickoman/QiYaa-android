# `local/` — свои файлы из медиатеки телефона

Свои файлы пользователя — из медиатеки телефона (`MediaStore`), а не открытием файлов и папок, как на ПК (`spec/player/local.md`, LOCAL-10…14). Пакет читает медиатеку, раскладывает её на выборы («Все треки», альбомы, исполнители, папки) в порядке ПК и сверяет восстановленную очередь с медиатекой. Он ничего не играет: свой трек — `track.Track` с `TrackSource.LOCAL`, а играет его `playback/` (`content://`, `LocalMediaSources`). Экран и разрешение — `ui/`. Очередь получает выбор через `queue.loadSource`, как альбом: своего `MusicSource` у медиатеки нет.

Андроидный пакет (`LocalLibrary` — `ContentResolver`), но всё, что решает, — чистые функции в отдельных файлах, их тесты идут на JVM:

```bash
grep -lE '^import (android|androidx)' app/src/main/java/io/github/kickoman/qiyaa/local/{NaturalOrder,LocalTracks,LocalPicks,LocalCheck}.kt   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `LocalLibrary.kt` | `LocalLibrary` — разрешение, запрос к медиатеке, выборка по `_ID`, есть ли декодер FLAC |
| `LocalTracks.kt` | `LocalRow`, `LocalFolder`, `LocalTracks` — строка медиатеки, её трек и какие форматы берутся |
| `LocalPicks.kt` | `LocalPick`, `LocalAlbum`, `LocalArtist`, `LocalFolderPick`, `LocalPicks` — выборы и их порядок |
| `NaturalOrder.kt` | `NaturalOrder` — естественный порядок имён и путей, как на ПК |
| `LocalCheck.kt` | `LocalCheck` — сверка восстановленной очереди (LOCAL-14) |

## `LocalLibrary`

```kotlin
class LocalLibrary(context: Context) {
    val permission: String                       // READ_MEDIA_AUDIO с API 33, иначе READ_EXTERNAL_STORAGE
    val flacDecodes: Boolean                     // MediaCodecList: есть ли декодер audio/flac (один раз за процесс)
    fun hasPermission(): Boolean
    fun takeChanged(): Boolean                   // менялась ли медиатека с прошлого вызова; первый вызов — true
    fun rows(): List<LocalRow>                   // блокирует: звать на Dispatchers.IO
    fun rowsById(ids: List<Long>): Map<Long, LocalRow>   // порциями по 500 _ID
}
```

Запрос — `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` (`content://media/external/audio/media`; с API 29 это все внешние тома, как `getContentUri(VOLUME_EXTERNAL)`). `rows()` — один запрос с `IS_MUSIC != 0`. Этот признак медиатека ставит по пути: мимо проходят `Ringtones/`, `Notifications/`, `Alarms/`, `Podcasts/`, `Recordings/`, а голосовые сообщения и записи звонков вне этих папок могут попасть внутрь. Строки, чей `MIME_TYPE` не берётся (`LocalTracks.isTaken`), `rows()` отбрасывает сразу. `rowsById` — без обоих фильтров, только по `_ID` («`LocalCheck`» ниже).

`takeChanged()` при первом вызове вешает `ContentObserver` на `EXTERNAL_CONTENT_URI` с потомками (зовут его только с разрешением: без него Android 8+ отказывает в подписке на чужой провайдер) и дальше отвечает, приходило ли с прошлого вызова уведомление медиатеки. Так `ui/` перечитывает медиатеку, только когда она менялась (сканирование нового файла, удаление). Наблюдатель живёт, пока жив процесс: `LocalLibrary` одна, в `AppGraph`.

Столбцы: `_ID`, `TITLE`, `DISPLAY_NAME`, `ARTIST`, `ALBUM`, `ALBUM_ID`, `DURATION`, `TRACK`, `MIME_TYPE`, `SIZE`; с API 29 — `VOLUME_NAME` и `RELATIVE_PATH` (папка), до него — `DATA` (полный путь, папка — его родитель, том пустой); с API 30 — `BITRATE`.

**Разрешения** (манифест): `READ_MEDIA_AUDIO` и `READ_EXTERNAL_STORAGE` с `maxSdkVersion=32`. Других нет: картинки из папки отдаёт сама медиатека (обложка), а `READ_MEDIA_IMAGES` для этого не нужен. Без разрешения пакет ничего не читает. Его может не стать и потом: API 30+ сбрасывает права неиспользуемых приложений, пользователь отзывает их сам. Тогда восстановленная очередь своих файлов падает трек за треком, и после трёх подряд воспроизведение останавливается (ERR-05).

**Traps:**
- `MIME_TYPE` не отличает AAC от ALAC в `.m4a` и Vorbis от Opus в Ogg, а на API 29 `.m4a` бывает `audio/mpeg`. Кодек решает только при игре: дорожку без декодера ловит `playback/Media3Engine` (LOCAL-13). Читать заголовок каждого из 10 000 файлов дорого.
- `.opus` на API 26–28 медиатека как аудио не видит; `.mp4` только со звуком на API 29 лежит среди видео; `.oga` на API 26–28 индексируется без тегов.
- `ARTIST` — одна строка: у ID3v2.4 с несколькими TPE1 медиатека оставляет первого, у нескольких Vorbis `ARTIST=` — последнего. `YEAR` у ID3v2.4 и FLAC с одним `DATE=` пуст. Поэтому трек берёт только название, исполнителя одной строкой, длительность и номер.
- `ALBUM_ID` — хэш названия альбома и исполнителя альбома, а без него — папки: один альбом без исполнителя альбома в двух папках — два альбома.
- `_ID` переживает перезагрузку и пересканирование, на API 30+ — переименование и перенос в пределах тома. Новая база медиатеки (очистка «Хранилища мультимедиа», порча) нумерует заново: см. `LocalCheck`.

## `LocalTracks`

```kotlin
data class LocalRow(id: Long, title: String?, displayName: String, artist: String?, album: String?, albumId: Long,
                    durationMs: Long, trackNumber: Int, mimeType: String?, sizeBytes: Long,
                    bitrateBitsPerSecond: Long?, folder: LocalFolder)
data class LocalFolder(volume: String, path: List<String>)    // путь — части RELATIVE_PATH (или каталога DATA)
object LocalTracks { fun isTaken(mimeType: String?, flacDecodes: Boolean): Boolean;  fun toTrack(row): Track
                     fun title(row): String;  fun bitrateKbps(row): Int }
```

- **Форматы** (LOCAL-13): `audio/mpeg`, `audio/wav`, `audio/x-wav`, `audio/mp4`, `audio/x-m4a`, `audio/ogg`, `application/ogg` (Ogg на API 26–28), `audio/flac` и `audio/x-flac` — только если есть декодер FLAC: на Android 8.0 его в AOSP нет, такой FLAC не берётся вовсе.
- **Трек** (LOCAL-04): `id` — `_ID`; название — `TITLE`, без него — `DISPLAY_NAME` без расширения; исполнитель — `ARTIST` одной строкой, `<unknown>` — без исполнителя; `coverUrl` — `content://media/external/audio/media/<_ID>/albumart` (LOCAL-05: его открывает `ContentUriFetcher` самого Coil, своего `Fetcher` нет; медиатека отдаёт вшитую картинку, иначе любую `.jpg`/`.png` из папки, до половины короткой стороны экрана); `bitrateKbps` — `BITRATE` (с API 30), иначе оценка `SIZE × 8 / DURATION`, как на ПК.

## `LocalPicks`, `NaturalOrder`

```kotlin
sealed interface LocalPick { All; Album(id: Long); Artist(name: String); Folder(folder: LocalFolder) }
class LocalPicks(rows: List<LocalRow>, order: NaturalOrder = NaturalOrder()) {
    val albums: List<LocalAlbum>;  val artists: List<LocalArtist>;  val folders: List<LocalFolderPick>
    fun tracks(pick: LocalPick): List<Track>;  fun rowsOf(pick: LocalPick): List<LocalRow>
}
class NaturalOrder(locale: Locale = Locale.getDefault()) : Comparator<String> { fun comparePaths(a: List<String>, b: List<String>): Int }
```

Порядок (LOCAL-11, правило ПК из `src/sources/local/README.md`):
- имя делится на серии ASCII-цифр и прочего; цифры раньше текста; цифры — по значению («2» раньше «10», «02» равно «2»); текст — `java.text.Collator` локали, без учёта регистра: регистр сворачивает `lowercase(Locale.ROOT)`, как `toCaseFolded()` на ПК, а не правило локали (в турецкой `I` стало бы `ı` и ушло бы раньше `i`); равные до конца — как простые строки, так что порядок полный. ICU `Collator` из android.jar в JVM-тестах — заглушка, поэтому `java.text`;
- путь — по частям, и имя папки соперничает с именами файлов рядом: `A/a.mp3`, `A/b/x.mp3`, `A/c.mp3`; тома — по имени;
- **«Все треки»** и **папка** — по пути; папка берётся с подпапками (как LOCAL-02), а в списке папок — те, где лежит хотя бы один файл, по пути; ключ папки — том и путь, так что `Music/` во внутренней памяти и на SD-карте — разные папки;
- **альбом** — по `TRACK` (диск × 1000 + номер), потом по названию, потом по пути; **исполнитель** — по названию альбома, альбомы одного названия — по `ALBUM_ID`, потом как альбом; списки альбомов и исполнителей — по имени (исполнитель без имени — `""`, первым), альбомы одного имени — по `ALBUM_ID`, так что порядок полный и не зависит от порядка строк медиатеки.

`LocalPicks` сортирует всё в конструкторе: n log n сравнений с `Collator`, поэтому строить его не на главном потоке; время на 10 000 строк — в проверке на телефоне (#118).

Предел в 10 000 (LOCAL-12) режет очередь: `queue.loadSource(…, limit = 10_000)`, и событие говорит «первые N».

## `LocalCheck`

```kotlin
object LocalCheck { fun localIds(tracks: List<Track>): List<Long>;  fun renumbered(saved: List<Track>, found: Map<Long, LocalRow>): Boolean }
```

LOCAL-14. После старта `AppGraph.checkRestoredLocalQueue` на `Dispatchers.IO` спрашивает медиатеку о `_ID` своих треков восстановленной очереди (`rowsById`). Спрашивает без `IS_MUSIC` и без фильтра форматов: после пересборки старый `_ID` может указать на запись диктофона, будильник или `.amr`, а отброшенная строка выглядела бы как пропавший файл, и очередь сыграла бы чужой файл под старым названием. Если медиатека даёт хоть одному из них другое название (по тому же правилу `title`) или длительность, база перенумерована, и `queue.clearIfRestored()` очищает очередь — только если её никто не заменил после восстановления. Пропавший файл (строки нет) очереди не трогает: он упадёт при игре (LOCAL-07). Без разрешения сверки нет. `MediaStore.getVersion` для этого не годится: на API 26–28 это версия самого провайдера (очистка данных её не меняет), а на 29+ она меняется и при обновлении модуля, когда `_ID` живы.

## Тесты

`app/src/test/.../local/`: `NaturalOrderTest`, `LocalPicksTest` (LOCAL-11), `LocalTracksTest` (LOCAL-04, LOCAL-05, LOCAL-13), `LocalCheckTest` (LOCAL-14); строки для них — `support/LocalRows.kt`. Очистку очереди проверяет `queue/QueueLocalTest` (LOCAL-14). `LocalLibrary` — запрос к системе — проверяется на телефоне (Kickoman/QiYaa-android#118).

## Not here

- Как играет свой трек (`content://`, буфер позади, дорожка без декодера) — `playback/README.md`.
- Что очередь делает со своим треком (отчёты, сиды, лайки, CONT-12) — `queue/README.md`.
- Экран «Свои файлы» и разрешение — `ui/README.md`.

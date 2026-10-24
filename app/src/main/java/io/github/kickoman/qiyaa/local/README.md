# `local/` — свои файлы из медиатеки телефона

Свои файлы пользователя — из медиатеки телефона (`MediaStore`), а не открытием файлов и папок, как на ПК (`spec/player/local.md`, LOCAL-10…14). Пакет читает медиатеку, раскладывает её на выборы («Все треки», альбомы, исполнители, папки) в порядке ПК, сверяет восстановленную очередь с медиатекой и читает тексты своих файлов (`.lrc` и теги, LYR-10) — как источник для `lyrics/`. Он ничего не играет: свой трек — `track.Track` с `TrackSource.LOCAL`, а играет его `playback/` (`content://`, `LocalMediaSources`). Экран и разрешение — `ui/`. Очередь получает выбор через `queue.loadSource`, как альбом: своего `MusicSource` у медиатеки нет.

Андроидный пакет (`LocalLibrary` — `ContentResolver`), но всё, что решает, — чистые функции в отдельных файлах, их тесты идут на JVM:

```bash
grep -lE '^import (android|androidx)' app/src/main/java/io/github/kickoman/qiyaa/local/{NaturalOrder,LocalTracks,LocalPicks,LocalCheck,LocalLyricsFiles}.kt   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `LocalLibrary.kt` | `LocalLibrary` — разрешение, запрос к медиатеке, выборка по `_ID`, есть ли декодер FLAC |
| `LocalTracks.kt` | `LocalRow`, `LocalFolder`, `LocalTracks` — строка медиатеки, её трек и какие форматы берутся |
| `LocalPicks.kt` | `LocalPick`, `LocalAlbum`, `LocalArtist`, `LocalFolderPick`, `LocalPicks` — выборы и их порядок |
| `NaturalOrder.kt` | `NaturalOrder` — естественный порядок имён и путей, как на ПК |
| `LocalCheck.kt` | `LocalCheck` — сверка восстановленной очереди (LOCAL-14) |
| `LocalLyrics.kt` | `LocalLyrics` — текст своего файла как `lyrics.LyricsSource` (LYR-10): где файл, `.lrc` рядом, теги |
| `LocalLyricsFiles.kt` | `LocalLyricsFiles` — имя `.lrc`, его кодировка, кадр USLT, чанк `id3 ` у WAV, порядок `.lrc` и тегов |
| `TagLyrics.kt` | `TagLyrics` — текст из записей метаданных Media3 (ID3, MP4, Vorbis) |

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

`takeChanged()` при первом вызове вешает `ContentObserver` на `EXTERNAL_CONTENT_URI` с потомками (`ui/` зовёт его только с разрешением) и дальше отвечает, приходило ли с прошлого вызова уведомление медиатеки. Флаг один: второй, кто стал бы его спрашивать, забирал бы изменения у первого. Так `ui/` перечитывает медиатеку, только когда она менялась (сканирование нового файла, удаление). Наблюдатель живёт, пока жив процесс: `LocalLibrary` одна, в `AppGraph`.

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
    val albums: List<LocalAlbum>;  val artists: List<LocalArtist>;  val folders: List<LocalFolderPick>;  val trackCount: Int
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

LOCAL-14. `AppGraph.checkRestoredLocalQueue` один раз за процесс, как только есть разрешение (на старте или когда его выдали, `ui/README.md`), на `Dispatchers.IO` спрашивает медиатеку о `_ID` своих треков восстановленной очереди (`rowsById`). Спрашивает без `IS_MUSIC` и без фильтра форматов: после пересборки старый `_ID` может указать на запись диктофона, будильник или `.amr`, а отброшенная строка выглядела бы как пропавший файл, и очередь сыграла бы чужой файл под старым названием. Если медиатека даёт хоть одному из них другое название (по тому же правилу `title`) или длительность, база перенумерована, и `queue.clearIfRestored()` очищает очередь — только если её никто не заменил после восстановления. Пропавший файл (строки нет) очереди не трогает: он упадёт при игре (LOCAL-07). Пока разрешения нет, сверки нет. `MediaStore.getVersion` для этого не годится: на API 26–28 это версия самого провайдера (очистка данных её не меняет), а на 29+ она меняется и при обновлении модуля, когда `_ID` живы.

## `LocalLyrics`, `LocalLyricsFiles`, `TagLyrics` — тексты своих файлов (LYR-10)

```kotlin
class LocalLyrics(context: Context) : LyricsSource          // всё на Dispatchers.IO
object LocalLyricsFiles {
    const val MAX_TAG_CHUNK_BYTES = 64 * 1024 * 1024
    fun lrcNameFor(audioName: String, names: List<String>): String?   // то же имя, ".lrc" в любом регистре; первое по имени
    fun decodeLrc(bytes: ByteArray): String                            // BOM UTF-8/UTF-16, иначе строгий UTF-8, иначе windows-1251
    fun usltText(frame: ByteArray): String?                            // тело USLT/ULT: кодировка 0–3, язык, описание, текст
    fun riffId3(input: InputStream): ByteArray?                        // чанк "id3 "/"ID3 " WAV-файла
    fun readLimited(input: InputStream, limit: Int): ByteArray?        // null — больше limit
    fun choose(lrc: Lyrics?, tags: Lyrics?): LyricsAnswer
}
@UnstableApi object TagLyrics { fun textOf(entries: List<Metadata.Entry>): String?;  fun entriesOf(metadata: Metadata?): List<Metadata.Entry> }
```

Порт `src/sources/local/lyrics.cpp` десктопа; правило — `spec/player/lyrics.md`, LYR-10, с оговорками Android ниже.

1. **Где файл.** Запрос к `MediaStore.Audio.Media` по `_ID`: `DISPLAY_NAME`, `MIME_TYPE`, с API 29 — `VOLUME_NAME` и `RELATIVE_PATH`, до него — `DATA`.
2. **`.lrc` рядом** — то же имя, расширение `.lrc` в любом регистре, первое по имени (`lrcNameFor`, как `LrcPathOf` десктопа):
   - API 30+: `MediaStore.Files` того же тома и `RELATIVE_PATH` с `MEDIA_TYPE_SUBTITLE` (5): `.lrc` медиатека считает субтитрами и с правом на аудио отдаёт. Имя сравнивается в коде, не `LIKE`: тот не различает регистр только в ASCII, а `_` и `%` в имени пришлось бы экранировать. Читается через content URI.
   - API 26–28: список папки `File(DATA).parentFile`, право на чтение хранилища это даёт.
   - API 29: не читается: `.lrc` там `MEDIA_TYPE_NONE`, а чужие немедийные файлы scoped storage не отдаёт. `requestLegacyExternalStorage` не взят: он меняет всё хранилище ради одного случая. На Android 10 — только теги.
   - Больше 256 КиБ (LYR-11) — не читается: `readLimited` останавливается на 256 КиБ + 1 байте, у папки ещё раньше — по `File.length`.
   - Кодировка (`decodeLrc`): BOM UTF-8 или UTF-16, иначе строгий UTF-8, иначе windows-1251. «Системная кодовая страница» ПК на Android пуста (`Charset.defaultCharset()` — всегда UTF-8), слушатели — be/ru; цена — однобайтовый западный текст (cp1252) выйдет кириллицей.
3. **Теги:**
   - WAV (`audio/wav`, `audio/x-wav`, `audio/wave`, `audio/vnd.wave`): `WavExtractor` Media3 1.11.1 чанк `id3 ` пропускает, поэтому `riffId3` идёт по чанкам сам — `data` проматывает (`skip`), размер чанка проверяет до выделения памяти (64 МиБ, как `kMaxReadBytes` десктопа: в `id3 ` бывают картинки), нечётный размер — с байтом выравнивания — и отдаёт найденный `Id3Decoder().decode`.
   - Остальное: `MetadataRetriever` из `media3-inspector` (`retrieveTrackGroups().await()`), записи `Format.metadata` всех дорожек.
   - `TagLyrics.textOf` берёт первое из: `BinaryFrame` `USLT` (ID3v2.3/2.4) или `ULT` (ID3v2.2) — `usltText`, описание не важно; `TextInformationFrame` `USLT` — так Media3 отдаёт `©lyr` из MP4; `VorbisComment` `LYRICS` в любом регистре (FLAC, Ogg).
4. **Порядок** (`choose`): синхронный `.lrc` — сразу, теги тогда не читаются; иначе текст тегов (синхронный, если в нём метки времени), `FILE_TAGS`; иначе обычный `.lrc`, `LRC_FILE`; иначе `Missing`. Файл, который не открылся (`IOException`, `SecurityException`), — `Missing`, никогда не `Failed`: повтор при сети ему не поможет.

**Traps:**
- `Id3Decoder` на JVM не работает: `ParsableByteArray` при первой загрузке читает `Build.FINGERPRINT`, а в заглушке android.jar это `null`. Поэтому JVM-тесты кормят `TagLyrics` готовыми кадрами, а что USLT приходит `BinaryFrame`, сверено по байткоду Media3 1.11.1 и проверяется на телефоне (#118).
- `TextInformationFrame.value` устарел — `values.firstOrNull()`.
- `MetadataRetriever` открывает файл через `DefaultMediaSourceFactory`, со своим потоком; вызов на `Dispatchers.IO`, ожидание — `await` из `kotlinx-coroutines-guava`. Его 8 с держит `lyrics.LyricsFinder`, а не он.

## Тесты

`app/src/test/.../local/`: `NaturalOrderTest`, `LocalPicksTest` (LOCAL-11), `LocalTracksTest` (LOCAL-04, LOCAL-05, LOCAL-13), `LocalCheckTest` (LOCAL-14), `LocalLyricsFilesTest` и `TagLyricsTest` (LYR-10, LYR-11); строки для них — `support/LocalRows.kt`. Очистку очереди проверяет `queue/QueueLocalTest` (LOCAL-14). `LocalLibrary` и `LocalLyrics` — запросы к системе — проверяются на телефоне (Kickoman/QiYaa-android#118).

## Not here

- Как играет свой трек (`content://`, буфер позади, дорожка без декодера) — `playback/README.md`.
- Что очередь делает со своим треком (отчёты, сиды, лайки, CONT-12) — `queue/README.md`.
- Экран «Свои файлы» и разрешение — `ui/README.md`.

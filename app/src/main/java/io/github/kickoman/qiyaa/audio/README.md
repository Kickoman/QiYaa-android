# `audio/` — DSP

Порт `src/audio` и `src/vis` десктопного QiYaa: десятиполосный эквалайзер на RBJ-биквадах, FFT-анализатор, спектр с падающими пиками, кольцевой буфер для визуализатора и конвертация PCM16. Пакет работает с `FloatArray` и ничего не знает про ExoPlayer, `ByteBuffer`-ы Media3 и Compose: это `playback/EqualizerProcessor`, `playback/VisualizerTapProcessor` и `ui/visualizer`.

Чистая JVM, тестируется синтезированными сигналами:

```bash
grep -rlnE '^import (android|androidx)' app/src/main/java/io/github/kickoman/qiyaa/audio/   # ничего не печатает
```

| Файл | Содержит |
|---|---|
| `EqualizerDsp.kt` | `EqSettings` (данные) и `EqualizerDsp` (фильтр) |
| `EqPresets.kt` | `EqPreset`, `EqPresets` — 17 пресетов Winamp |
| `Analyzer.kt` | `Analyzer` — окно Ханна + radix-2 FFT → dBFS |
| `Spectrum.kt` | `Spectrum` — 19 логарифмических полос с пиками |
| `VisualizerTap.kt` | `VisualizerTap` — lock-free кольцо последних кадров с метками времени звука |
| `Pcm16.kt` | `Pcm16` — 16-bit PCM ↔ float |
| `AudioBus.kt` | `AudioBus` — что процессоры публикуют для UI |

## `EqSettings`, `EqualizerDsp`

```kotlin
data class EqSettings(enabled = true, preampDb = 0.0, bandsDb: List<Double> = 10 × 0.0) {
    val isFlat: Boolean
    fun withBand(index: Int, db: Double): EqSettings          // db зажимается в ±MAX_DB
    companion object { BAND_COUNT = 10; MAX_DB = 12.0; BAND_HZ = [60, 170, 310, 600, 1000, 3000, 6000, 12000, 14000, 16000]; FLAT }
}

class EqualizerDsp(sampleRate: Int = 44_100) {
    val sampleRate: Int
    fun setSampleRate(rate: Int)                               // ≤ 0 → 44 100
    fun publish(settings: EqSettings)                          // с любого потока
    fun reset()                                                // обнуляет состояние фильтров
    fun process(frames: FloatArray, frameCount: Int, channels: Int)   // на месте, interleaved
    companion object { Q = 1.2; MAX_CHANNELS = 2; fun compute(settings, sampleRate: Double): Coefficients; fun responseDb(settings, hz, sampleRate): Double }
}
```

`EqSettings` требует ровно 10 полос (`require` в `init`). Полоса с |dB| < 0.05 или с центром выше 0.49 × sampleRate — тождественный биквад. Преамп — линейный множитель `10^(dB/20)`. Коэффициенты считаются заранее в `compute` (RBJ cookbook, peaking EQ, `a0`-нормированные `b0 b1 b2 a1 a2`) и публикуются одним неизменяемым объектом через `@Volatile`; аудиопоток берёт актуальный набор на следующем блоке. Фильтр — транспонированная прямая форма II, состояние `delay1/delay2` на полосу и канал; после блока значения < 1e-15 обнуляются (денормалы).

`responseDb` считает АЧХ каскада в точке `hz` — им пользуется тест и может пользоваться график.

**Traps:**
- `process` обрабатывает первые `MAX_CHANNELS = 2` канала; `channels` в шаге индексации — реальное число каналов буфера.
- Тождественная полоса обнуляет своё состояние, чтобы возврат к ненулевому усилению не щёлкал.
- `publish` и `setSampleRate` меняют коэффициенты, но не состояние фильтров; `reset()` вызывает процессор на flush.
- Настройки, частота и коэффициенты публикуются одним неизменяемым объектом в `@Volatile`-поле: `process` на потоке воспроизведения читает его один раз за буфер. `publish` (главный поток) и `setSampleRate` (поток воспроизведения) пишут под общей блокировкой, поэтому смена частоты не теряет только что опубликованные настройки и не пересчитывает старые.

## `EqPresets`

Таблица `WINAMP_EQF` — значения из `presets/builtin.json` webamp (MIT, см. `THIRD_PARTY.md`) в шкале `.eqf` 1…64. `levelToDb(level)` зажимает уровень в 1…64 и даёт `(level − 1)/63·24 − 12` дБ, а 33 — ровно 0 дБ (по формуле было бы +0.19; Winamp считает 33 «плоским»). Правило то же, что в `spec/dsp/eqf.json`. Преамп всех пресетов — 0 дБ. `byName` возвращает `null` для неизвестного имени.

## `Analyzer`, `Spectrum`

```kotlin
class Analyzer(val size: Int = 1024) {          // степень двойки ≥ 8, иначе IllegalArgumentException
    val binCount: Int = size / 2 + 1
    fun analyze(mono: FloatArray, spectrumDb: FloatArray)   // выход в dBFS: синус полной шкалы → 0 dBFS
}

class Spectrum(val barCount: Int = 19) {
    val levels: FloatArray; val peaks: FloatArray   // 0..1
    fun reset()
    fun update(spectrumDb: FloatArray, fftSize: Int, sampleRate: Int)   // spectrumDb.size == fftSize/2 + 1
    data class Band(lowHz, highHz, firstBin, endBin)
    companion object { fun bands(fftSize: Int, sampleRate: Int, barCount: Int = 19): List<Band> }
}
```

Полосы спектра (`bands`) логарифмические от 60 Гц до min(16 кГц, sampleRate/2); бины полосы — `[floor(lowHz/binHz), ceil(highHz/binHz))`, зажатые в 1…N/2 и не пустые; уровень — максимум dBFS в полосе, отображённый из [−72, −6] дБ в [0, 1]. Уровень падает на 0.07 за кадр, пик падает по квадрату возраста с коэффициентом 0.0004 (`Spectrum.PEAK_GRAVITY`). Кадр — один вызов `update`; `ui/visualizer` вызывает его каждые 33 мс.

Всё это сверяется с эталонами десктопа из `spec/dsp` (`app/src/test/.../audio/DspVectorsTest.kt`): АЧХ EQ до 1e-4 дБ в 50 случаях на 44 100 и 48 000 Гц, пресеты и уровни `.eqf` до 1e-9 дБ, границы полос и один кадр спектра для 24 синусов до 1e-3. Изменение DSP, которое сдвигает эти числа, — изменение поведения: сначала в QiYaa-spec.

`analyze` и `VisualizerTap.read` пишут в массивы вызывающего: это единственные out-параметры в проекте, чтобы не аллоцировать на каждый кадр.

## `VisualizerTap`

```kotlin
class VisualizerTap(capacityFrames: Int = 131_072) {
    var sampleRate: Int
    fun announceInput(ptsUs: Long)                                  // метка времени следующего входного буфера
    fun write(frames: FloatArray, frameCount: Int, channels: Int)   // аудиопоток; моно дублируется в оба канала
    fun reportPlaying(ptsUs: Long, atNanos: Long)                   // какой момент звучит сейчас
    fun readPlaying(outLeft, outRight, frameCount, nowNanos: Long)  // окно, которое кончается на звучащем кадре
    fun read(outLeft, outRight, frameCount)                         // окно из самых свежих кадров
    fun lagUs(nowNanos: Long): Long?                                // от звучащего кадра до самого свежего
    fun clear()
}
```

Кольцо на `capacityFrames` стереокадров (степень двойки; по умолчанию 131 072 — 2,7 с при 48 кГц, чтобы вместить задержку вывода вместе с окном FFT). Процессор копирует PCM **до** буфера `AudioTrack`, поэтому самые свежие кадры звучат позже на задержку вывода. Чтобы картинка шла вместе со звуком, у кольца есть метки: `announceInput` задаёт время звука первого кадра следующей записи, `write` запоминает пару «номер кадра — время» (до 1 024 меток) и продолжает время по числу кадров и `sampleRate`. `reportPlaying` сообщает, какой момент звучит, и `readPlaying` находит по меткам кадр, который звучит в `nowNanos`: от последнего отчёта позиция досчитывается по часам, но не больше чем на 0,5 с и не дальше самого свежего записанного кадра. Без меток и отчёта `readPlaying` ведёт себя как `read`. Время — в единицах ExoPlayer (`presentationTimeUs` входного буфера звукового выхода, со смещением рендерера), а не позиция трека: откуда оно берётся, см. `playback/README.md`, `TimedAudioSink`.

Курсор — `AtomicLong`, данные и метки не защищены: рваное чтение допускается по замыслу (это картинка, не звук). `read`/`readPlaying` с `frameCount > capacityFrames` — `IllegalArgumentException`.

**Traps:**
- `clear()` (процессор на flush: перемотка, смена формата) забывает метки и отчёт. При смене формата на бесшовном переходе старый трек ещё звучит, а меток у него уже нет: до конца задержки видно начало нового трека.
- Метку ставит первый вызов `write` после `announceInput`; процессоры Media3 перед нашими (обрезка тишины кодека) могут сдвинуть её на десятки миллисекунд.

**Замер задержки.** Звуковой выход раз в 5 с пишет в logcat `Visualizer: the newest written audio sounds in N ms` — насколько раньше картинка шла бы без меток (`adb logcat -s QiYaa` во время воспроизведения). Число зависит от устройства и выхода, поэтому в коде оно не зашито: метки берут задержку у самого ExoPlayer. Замеры:

| Устройство | Выход | N, мс | Сборка |
|---|---|---|---|
| — | проводные наушники | ещё не замерено | — |
| — | Bluetooth | ещё не замерено | — |

## `Pcm16`

`decode(ShortBuffer, sampleCount, out)` делит на 32768; `encode(samples, sampleCount, ByteBuffer)` умножает на 32767, зажимает в [−32768, 32767] и пишет `putShort` в порядке байтов буфера.

## `AudioBus`

Одна точка обмена между процессорами и UI: `equalizer`, `visualizerTap`, `StateFlow`-ы `sampleRate`, `channels`, `bitrateKbps` и `@Volatile` `gainLeft`/`gainRight` для баланса. `setBalance(−100…100)`: положительный баланс ослабляет левый канал линейно, отрицательный — правый. `volumeGain(0…100) = (v/100)²` — кривая громкости Winamp.

## Not here

- Media3-процессоры и байтовые буферы — `playback/`. Отрисовка спектра и осциллографа — `ui/visualizer/VisualizerStrip.kt`. Сохранение настроек EQ — `data/Settings`.

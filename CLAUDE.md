# Code style

These rules are the Kotlin/Android adaptation of the MatrixGui (C++20) rules,
applied to this project. Sections 1–4 hold in any language. Section 5 is
specific to Kotlin, Android and Jetpack Compose. Where existing code disagrees
with these rules, match the newest, most-reviewed module rather than the
oldest.

Prose documentation (`README.md` files) is written in Russian. Identifiers,
comments, commit messages and the default (`values/`) string resources are in
English.

## 1. Comments: as few as possible, ideally none

Explanations belong in the module's README, not in the source.

Allowed in code:
- a short technical note that the code itself cannot tell you: a units
  convention, a platform quirk (`replaceOutputBuffer(0)` returns the shared
  buffer), where a fixture's numbers come from;
- a genuinely exceptional decision, where the obvious reading of the code is
  wrong and the reason is not written anywhere else. One or two lines.
- a one-line reason inside an intentionally empty `catch (ignored: …)`.

Not allowed:
- KDoc on every function, class or property (`/** Returns the … */`);
- restating what the next line does, or naming the section of a screen
  (`// Preset row.`);
- trailing comments after data class fields to explain each one;
- design rationale, "why not the other approach", history, or anything that is
  really a paragraph of documentation;
- commented-out code, `TODO` without an owner, and banner separators
  (`// ---- transport ----`).

When you edit code and feel the need to explain it, update the README instead.
If a name needs a comment, rename it first.

## 2. Documentation lives next to the code, one README per module

Every package that is a module of its own has a `README.md` next to its
sources (`app/src/main/java/io/github/kickoman/qiyaa/<module>/README.md`). The
top-level `README.md` says what the project is, gives a table of screens, a
quick start, the module index and the check commands. `docs/` holds narrative
documentation when there is any; module READMEs are the file-level reference.

A module README follows this shape:

1. `# \`module/\` — what it is`, then one paragraph: what the package does,
   what it does *not* do, and which other package does that.
2. A file table:

   ```markdown
   | File | Contains |
   |---|---|
   | `Errors.kt` | `YandexException` and its four children |
   | `TrackUrl.kt` | `TrackUrl` — signing of the mp3 link |
   ```

3. Layout and dependency direction when there are subfolders. State any
   module-wide invariant as something you can check with one command:

   ```bash
   grep -rln '^import android' app/src/main/java/io/github/kickoman/qiyaa/yandex/   # must print nothing
   ```

4. One section per file or unit. Start with the public signatures in a code
   block, where short trailing comments are fine (`// null when absent`).
   Then say what the unit guarantees, and end with a **Traps:** list of what
   will bite the next person.
5. Wire and storage formats spelled out: JSON envelope shapes, form fields,
   `SharedPreferences` keys with type and default, `Bundle` extras, URI
   schemes, plus what the parser refuses.
6. The exception tree of the module, and which failures are data, not
   exceptions.
7. A **Not here** section when readers are likely to look for something in the
   wrong place.

Writing rules for READMEs:
- State each fact in one place. Link to another README rather than repeat it.
- Be concrete. Give real numbers, names and limits, not "fast" or "large".
- When you quote a measurement, say how it was measured: build type, device,
  tight loop or real playback. Never quote numbers from a debug build as
  performance. Do not overclaim.
- Update the README in the same change as the code it describes.

## 3. Structure

- **Package = module = README.** There is one Gradle module (`:app`); the
  packages under `io.github.kickoman.qiyaa` are the modules. Each has one
  README and one job:

  | Package | Job | May import from |
  |---|---|---|
  | `yandex` | Yandex Music HTTP API, OAuth, link signing | — (pure JVM) |
  | `audio` | DSP: equalizer, FFT, spectrum, visualizer ring, PCM | — (pure JVM) |
  | `data` | Persistence: settings and token | `audio` |
  | `jam` | Jam client: protocol codec, connection, the host's stored session | — (pure JVM) |
  | `queue` | What plays next: sources, waves, the jam mode, error policy, shuffle rule | `yandex` (pure JVM) |
  | `playback` | ExoPlayer, MediaSession, the Media3 adapter for `queue`, audio processors, the jam WebSocket transport | `queue`, `jam`, `yandex`, `audio`, `data` |
  | `ui` | Compose screens, view models, theme | everything above |
  | root | `AppGraph`, `QiYaaApp` (composition root), `NetworkMonitor` (platform signals) | everything |

  Dependencies point down the table only. `yandex` and `audio` are pure JVM
  and must not import `android.*` or `androidx.*`; that keeps them testable on
  the JVM without Robolectric:

  ```bash
  grep -rlnE '^import (android|androidx)' app/src/main/java/io/github/kickoman/qiyaa/{yandex,audio,queue,jam}/   # must print nothing
  grep -rln 'qiyaa\.ui\.' app/src/main/java/io/github/kickoman/qiyaa/{yandex,audio,data,queue,playback}/      # must print nothing
  ```

  Android entry points (`MainActivity`, `PlaybackService`) reach the graph
  through `Context.appGraph`; that is the only upward reference allowed.
- **Core logic has no UI.** `yandex`, `audio`, `data`, `queue` and `playback` never
  produce user-facing text. Anything a user should see is a typed event or a
  status value (`QueueEvent`, `LoginStatus`) that the `ui` package renders
  through string resources. Titles that come from the user or the server
  (playlist names, search text) are data and may pass through.
- **Pure functions apart from I/O.** Parsing is separate from transport
  (`TrackParsing`, `TrackUrl` take `JsonElement`, `YandexApi` does the HTTP).
  DSP classes take arrays and return arrays; only the Media3 processors in
  `playback` touch `ByteBuffer`s and the player. The queue sees the player
  through the `PlayerEngine` interface and the library through `MusicSource`,
  so every queue rule runs in a JVM test with fakes.
- **Entry points only wire and dispatch.** `QiYaaApp.onCreate` builds the
  graph, `MainActivity.onCreate` sets the content, `PlaybackService.onCreate`
  assembles the player. Behaviour lives in view models and in the modules.
- **Compose is a thin front end.** Screens take a view model (or state plus
  callbacks), read `StateFlow`s with `collectAsStateWithLifecycle`, and call
  back. Components (`ui/components`) take plain values. No screen computes
  domain state.
- **Split by stage, not by kind.** `yandex/`, `audio/`, `playback/`, `data/`,
  `ui/` — not `utils/` or `helpers/`. Inside `ui/`, the Compose convention
  `screens/`, `components/`, `theme/`, `visualizer/` is the accepted layout.
- Vendored third-party data (the Winamp preset table) is documented in
  `THIRD_PARTY.md` with its licence.

## 4. Behaviour

**Errors.**
- Anything that is nobody's fault at the keyboard throws: a broken response, a
  rejected token, a contract mismatch.
- Each module has its own hierarchy, rooted at `<Module>Exception`, with one
  child per way a caller would react differently. `yandex` has
  `YandexException` with `HttpException(status, method, path, reason)`,
  `NetworkException`, `MalformedResponseException` and `AuthException`.
- The `yandex` root extends `IOException` on purpose: ExoPlayer's
  `ResolvingDataSource.Resolver` may only throw `IOException`, and the
  resolver calls the API directly.
- Expected misses from user input (an unknown preset name, a token that does
  not parse, a JSON field that is absent) are **data**: `null`, `""`, a
  default, or a status value. They are not exceptions.
- Error messages carry the actual values and the request:
  `"HTTP 401 on GET /users/42/likes/artists: Token expired"`, not
  `"size mismatch"`. `require` messages include the offending value.
- Never classify an error by parsing its message. Use the type and its fields
  (`failed is HttpException && failed.isTokenRejected`).
- Catch once at the top: the coroutine that starts an operation (a
  `viewModelScope.launch` or the queue's `scope.launch`) catches, names the
  error `failed`, and turns it into an event or a status. Deeper code does
  not catch. An error you deliberately drop is named `ignored` and the
  `catch` body says why in one line.

**Validation.**
- Validate early, next to the setting that causes the problem: `Analyzer`
  rejects a non-power-of-two size in `init`, `EqSettings` rejects the wrong
  band count, `Settings` clamps volume and balance before storing them.
- Treat every size read from a response as untrusted: chunk request lists
  (250 track ids per call), coerce indices, check ring reads against the
  capacity.
- Parsers are lenient on shape (a field may be a number or a string) and
  strict on meaning (a download link without a host is `null`).

**Determinism.**
- Output must not depend on hash-map iteration order. Groups shown in the UI
  are sorted by an explicit order list with a stated tie-break.
- Persisted enums store an explicit value (`VisualizerMode.storedValue`,
  `AccentTheme.key`), never `ordinal`.

**Formats.**
- Every persisted key, JSON envelope, form field, URI scheme and `Bundle`
  extra is a named constant in code and a row in the module README.

**Names.** Use whole words: `visualizerTap`, not `visTap`; `viewModel`, not
`vm`; `colors`, not `c`; `failed`, not `e`. Established abbreviations of the
domain are fine: `id`, `uid`, `url`, `uri`, `json`, `http`, `db`, `hz`, `ms`,
`kbps`, `fft`, `eq`, `dsp`, `rms`, `pcm`, and the RBJ cookbook coefficient
names `b0 b1 b2 a1 a2`. Loop indices `i`, `k` and lambda `it` are fine when
the body is a few lines.

## 5. Kotlin and Android specifics

**Language and build**
- Kotlin 2.0, JVM target 17, `minSdk 26`. The build is clean under
  `allWarningsAsErrors`, ktlint (`.editorconfig`, `android_studio` style, max
  line 110) and Android lint with `warningsAsErrors`. Fix warnings, do not
  silence them. The three lint checks that are disabled (`GradleDependency`,
  `AndroidGradlePluginVersion`, `OldTargetApi`) report freshness, not
  defects, and their verdict depends on what the machine has installed: the
  CI runner has newer SDK platforms than a local setup. Raising `targetSdk`
  is a separate change that needs testing on a device.
- `lint.xml`/`@Suppress` need a reason in the same line or in the README.

**Naming**

| What | Style | Example |
|---|---|---|
| Functions, properties, locals, parameters | `camelCase`, no `m`, `_` or trailing `_` | `resolveTrackUrl`, `sampleRate` |
| Composable functions | `PascalCase` (Compose convention) | `PlayerScreen`, `HorizontalSlider` |
| Types, sealed members, objects | `PascalCase` | `QueueEvent.SourceLoaded`, `TrackParsing` |
| Enum entries | `UPPER_SNAKE_CASE` | `VisualizerMode.SPECTRUM` |
| Constants (`const val`, immutable top-level tables) | `UPPER_SNAKE_CASE` | `TRACKS_PER_REQUEST`, `KEY_VOLUME`, `BAND_LABELS` |
| Mutable backing flow of a public `StateFlow` | `mutableX` behind `x` | `mutableAccount` behind `account` |
| Packages | one lowercase word per segment | `yandex`, `ui.visualizer` |
| Files | `PascalCase`; named after the main type, or after the content when there is none | `QueueController.kt`, `Format.kt`, `Errors.kt` |
| Tests | `<Unit>Test.kt`, backtick sentence names | `` `HTTP errors carry the status, the request and the server message` `` |

If a stored field and its accessor would clash, the field gets the
descriptive name (`mutableState` behind `state`), never the decoration.

**Formatting**
- Indent 4 spaces, opening brace on the same line, trailing commas on every
  multi-line argument and parameter list. ktlint enforces the rest:
  `./gradlew ktlintFormat`.
- Keep lines at about 100 characters, hard limit 110.
- A multi-line signature puts one parameter per line and the closing
  parenthesis on its own line. A one-line `if (x) return` on a single line is
  fine; a body on its own line always gets braces.
- Long string concatenations wrap with `+` at the end of the line (ktlint's
  choice); long boolean expressions wrap before the operator.
- Use digit separators in large literals: `44_100`, `2_000L`.
- Imports are lexicographic in one block, no wildcards (`java.*` sorts among
  the rest; `ktlintFormat` orders them).

**Types and APIs**
- Data is a `data class` with default values; configs, UI state and results
  are all data classes with `val`s and read-only collections (`List`, `Set`),
  never arrays (arrays break `equals`).
- Return results by value. The only out-parameters are the preallocated
  arrays on the audio thread (`Analyzer.analyze`, `VisualizerTap.read`), and
  the README says so.
- Use `null` for "may be absent", `enum class` or a `sealed interface` for a
  kind, never an `Int` or a `String`. A `sealed interface` when variants
  carry different payloads (`QueueEvent`), an `enum` otherwise.
- Observable state is a private `MutableStateFlow` exposed as `StateFlow`
  via `asStateFlow()`. Cross-thread scalars read on the audio thread are
  `@Volatile`. Coefficient sets are published as immutable objects.
- `runBlocking` only on threads that are not the main thread and are
  designed to block (ExoPlayer's loading thread in `TrackResolver`).
- Constructor parameters are `private val` unless the graph needs them.
- Every module's exceptions follow this pattern:

  ```kotlin
  open class YandexException(message: String) : IOException(message)

  class HttpException(val status: Int, val method: String, val path: String, val reason: String) :
      YandexException("HTTP $status on $method $path: $reason")
  ```

**Compose**
- All user-visible text comes from `R.string`/`R.plurals`; both `values/`
  and `values-ru/` are updated in the same change.
- Colours and text styles come from `Qi.colors` and `ui/theme/Type.kt`; no
  literal `Color(0x…)` or `TextStyle(...)` outside `ui/theme`.
- Magic layout numbers that encode the design mock (`10 + i * 344 / 9`) are
  allowed inline with a one-line note; repeated tuning constants get a name.
- No ripple: use `Modifier.tap`.

## 6. Tests

- One layer: JUnit 4 unit tests on the JVM in `app/src/test`, run with
  `./gradlew testDebugUnitTest`. Networked code is tested against
  `MockWebServer`; DSP against synthesised signals. There is no emulator in
  the reference environment, so Android-dependent classes (view models, the
  service) are kept thin and are not unit-tested.
- Test-case names are sentences about behaviour in backticks:
  `` fun `tracksByIds fetches in chunks of 250 and keeps the order`() ``.
- Test helpers are private functions at the bottom of the test class.
  Shared fixtures go in `app/src/test/.../support/` when two classes need
  them.
- **Tests come before refactoring.** A refactor starts by pinning current
  behaviour with characterization tests. That is a separate first step, not a
  check at the end.
- Never re-record an existing expectation to make a change pass. Adding new
  cases is fine. If an expectation really has to change, say so and explain
  why.

## Behaviour lives in `spec/`

`spec/` is a submodule, [Kickoman/QiYaa-spec](https://github.com/Kickoman/QiYaa-spec), shared with
the desktop app. It holds the player scenarios, the API fixtures, the DSP reference vectors and the
parity table; [spec/README.md](spec/README.md) says what is where. Nothing in it is Android-only.

- **Change the spec before the code.** When a change alters behaviour that the spec describes (or
  should), commit the spec change to QiYaa-spec first. Then bump `spec/` here in the same change as
  the code and tests, and open an issue in Kickoman/QiYaa for the other side.
- **Tests name the scenario.** A test that checks a spec scenario names its ID in the test name
  (`` `WAVE-03 …` ``).
- **Never edit `spec/` only here.** A change inside the submodule that is not pushed to QiYaa-spec
  breaks every other checkout.

## 7. For agents

- Never commit or push. Leave the work in the working tree and describe it.
  If the work should be split into several commits, propose the split in
  text.
- Match the newest well-reviewed module, not whatever file you happen to have
  open.
- Before you call a change done, run the full check and report failures as
  they are:

  ```bash
  git submodule update --init
  export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
  ./gradlew ktlintCheck assembleDebug testDebugUnitTest lintDebug
  ```

  Say explicitly that nothing was verified on a device when no device or
  emulator was available.
- CI (`.github/workflows/ci.yml`) runs the same check on every push. A
  release is a pushed tag `vX.Y.Z`; never edit `versionName`/`versionCode`
  in code, they come from the tag (`docs/release.md`).
- Performance claims need a measurement on a release build on a named
  device, reported with how it was taken.

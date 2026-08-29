# Interval Timer (HIIT_app)

An Android interval-training timer built for personal use on a Pixel 5a. Supports multi-stage interval workouts (Prep → repeating block of Work/Rest stages → Cooldown) with time- or distance-based stages, audio cues that overlay music/podcasts, and a persistent notification so a workout keeps running while the app is backgrounded.

Built with Kotlin, Jetpack Compose, Room, and Coroutines. Single-module app (`app/`).

## Requirements

- Android Studio (current stable)
- Target device: Pixel 5a or similar, minSdk 26 / targetSdk 34
- A signing keystore for release builds — see `keystore.properties.template`

## Project structure

```
app/src/main/java/com/example/intervaltimer/
├── data/
├── domain/
├── audio/
├── service/
└── presentation/
    ├── screens/
    └── theme/
app/src/test/java/com/example/intervaltimer/domain/
app/src/androidTest/java/com/example/intervaltimer/data/
```

---

## `data/`

Persistence layer and the core domain model. No Android UI dependencies — this is what everything else is built on.

- **`WorkoutModels.kt`** — The heart of the data model.
  - `StageType` (PREP, WORK, REST, COOLDOWN) and `DurationType` (TIME_BASED, DISTANCE_BASED) enums.
  - `WorkoutStage`: a single stage's duration (canonical units — seconds or meters), plus optional `displayUnit` (for round-tripping the user's original unit choice in the editor) and `displayName` (a user-supplied alias like "Sprint" or "Jog", shown instead of the generic type label).
  - `SequencedStage`: a stage tagged with which repetition of the interval block it belongs to (0 for Prep/Cooldown, 1-based otherwise) — lets `WorkoutExecutor` track progress without inferring repetition count from stage types.
  - `Workout`: the full workout definition — fixed `prepStage`/`cooldownStage` bookends, a repeating `intervalBlockStages` list (1–20 stages, enforced in `init`), `intervals` (repeat count), and `finalRest` (whether the block's trailing Rest stage(s) are included on the final repetition). `buildStageSequence()` expands this into the flat, ordered list the executor actually runs.
  - `WorkoutConverters`: Room `TypeConverter`s for the enum fields and for serializing `intervalBlockStages` to/from a JSON column (Room can't `@Embedded` a `List` directly).

- **`WorkoutDao.kt`** — Room DAO: insert/update/delete a workout, fetch by id, and a `Flow<List<Workout>>` of all saved workouts ordered by most recently created.

- **`WorkoutDatabase.kt`** — Room database singleton (schema version 2). Uses `fallbackToDestructiveMigration()` rather than a real migration path — acceptable for a personal, actively-developed app with no user base to preserve data for across schema changes.

---

## `domain/`

The execution engine — pure Kotlin/Coroutines, no Android framework dependencies beyond what GPS requires.

- **`WorkoutExecutor.kt`** — State machine that drives a `Workout` through its stage sequence. Exposes `start()`/`pause()`/`resume()`/`restart()`/`end()` and a `StateFlow<WorkoutProgress>` for observers. Time-based stages tick once per second via `delay(1000L)`; distance-based stages poll a `distanceTravelledCallback` hook that the GPS layer feeds externally — the executor has no GPS code of its own.

- **`WorkoutState.kt`** — `ExecutionState` enum (IDLE, RUNNING, PAUSED, COMPLETED, CANCELLED) and `WorkoutProgress`, the snapshot type emitted by the executor (current stage, elapsed/remaining, countdown state, interval number, completion flag).

- **`GPSTracker.kt`** — Wraps `FusedLocationProviderClient` to accumulate distance (meters) via the Haversine formula. Filters out low-accuracy fixes, handles signal-loss reacquisition, and — critically — rejects stale/cached location fixes as a new stage's starting baseline, using the fix's own `elapsedRealtimeNanos` timestamp compared against an authoritative "this stage started now" marker rather than a passive freshness heuristic.

---

## `audio/`

- **`AudioCueService.kt`** — Synthesizes and plays the countdown/transition/completion tones as raw 16-bit PCM sine waves via `AudioTrack` (not `ToneGenerator`, which can't produce arbitrary frequencies). Requests `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` with `USAGE_MEDIA` so cues duck (not pause) whatever music/podcast is playing, and reliably route to the same output device (Bluetooth/wired/speaker) the other audio is already using.

---

## `service/`

- **`WorkoutService.kt`** — Foreground `Service` that owns the live `WorkoutExecutor` instance and ties it to `GPSTracker` and `AudioCueService`, so a workout keeps running (with cues and GPS tracking intact) while the app is backgrounded or the screen is locked. Also builds the persistent notification (Pause/Resume/End actions), manages a partial wake lock, and drives the countdown tone cadence on a fixed real-time 1-second clock (decoupled from GPS sampling granularity, since distance fixes arrive too irregularly to trigger cues directly).

- **`WorkoutStatePersistence.kt`** — Saves/restores in-progress workout state to `SharedPreferences` (as hand-rolled JSON via `org.json`) so a workout can be recovered if the app process is killed mid-session.

---

## `presentation/`

ViewModels, screens, and theming.

- **`MainActivity.kt`** — Single-Activity entry point. Hosts the `NavHost` (routes: `workout_list`, `workout_creator`, `workout_detail`, `active_workout`) and requests `ACCESS_FINE_LOCATION`/`POST_NOTIFICATIONS` on first launch.

- **`SelectedWorkoutHolder.kt`** — A small activity-scoped `ViewModel` that holds "which workout is currently selected," letting the edit/run screens receive a full `Workout` object across navigation without custom Parcelable `NavType` boilerplate.

- **`WorkoutListViewModel.kt`** — Backs the workout list screen: exposes all saved workouts as a `StateFlow`, plus delete and lookup-by-id.

- **`WorkoutViewModel.kt`** — Backs the active-workout screen. Binds to `WorkoutService`, forwards its `WorkoutProgress` StateFlow, and exposes derived flows (current stage name — preferring the `displayName` alias — timer value, remaining intervals).

- **`WorkoutCreatorViewModel.kt`** — Saves a new or edited `Workout` to Room (a single upsert, since `insertWorkout` uses `OnConflictStrategy.REPLACE`).

- **`PermissionHandler.kt`** — Permission-check extensions, a rationale-dialog composable, and a reusable `ActivityResultLauncher` wrapper for requesting `ACCESS_FINE_LOCATION`/`POST_NOTIFICATIONS`.

- **`DisplayUnit.kt`** — The user-facing unit system (seconds/minutes/hours; feet/yards/quarter-miles/miles/meters/kilometers) and the conversion math to/from canonical storage units. `buildWorkoutStage()` here is the single function that turns raw creator-screen input into a validated `WorkoutStage`.

### `presentation/screens/`

- **`WorkoutListScreen.kt`** — Displays saved workouts with a per-stage summary line, and Start/Edit/Delete actions per card (delete requires confirmation).

- **`WorkoutCreatorScreen.kt`** — Create/edit form. Prep and Cooldown are fixed single-stage sections; the interval block is a dynamic, user-editable list (1–20 stages) where each row picks Work or Rest, a duration + unit, and an optional display-name alias — with Add/Remove controls enforcing the block size limits.

- **`ActiveWorkoutScreen.kt`** — The live workout display: large countdown timer, current stage name (large font, alias-aware), interval counter, and Start/Pause/Resume/Restart/End controls (Restart/End require confirmation). Also owns the screen-wake-lock behavior (`View.keepScreenOn`) while a session is active.

### `presentation/theme/`

- **`Theme.kt`** — The app's dark-mode-only Compose `ColorScheme` (background/surface/accent colors) and the `IntervalTimerTheme` wrapper composable.

- **`AppDimensions.kt`** — Shared button/padding/text-size constants used across all screens for consistent large-touch-target sizing.

---

## Tests

- **`app/src/test/java/com/example/intervaltimer/domain/WorkoutExecutorTest.kt`** — Unit tests for the state machine (stage progression, pause/resume, restart, countdown detection) using `kotlinx-coroutines-test`'s virtual time rather than real delays.

- **`app/src/androidTest/java/com/example/intervaltimer/data/WorkoutDatabaseTest.kt`** — Instrumented Room DAO tests (insert/update/delete/query) against an in-memory database.

---

## Build configuration

- **`build.gradle.kts`** (root) — Plugin version declarations (AGP, Kotlin, KSP, Compose compiler).
- **`app/build.gradle.kts`** — Module config: dependencies, signing config (reads `keystore.properties`), ProGuard/R8 settings for release builds, and a build-variant hook that renames output APKs to `<project name>-v<version>-<buildType>.apk`.
- **`settings.gradle.kts`** — Repository and module declarations.
- **`gradle.properties`** — Build flags, including two AGP 9 compatibility opt-outs (`android.builtInKotlin=false`, `android.newDsl=false`) needed to keep the existing plugin structure working; documented in-file as temporary (removed in AGP 10.0).
- **`app/proguard-rules.pro`** — Minimal R8 rules; Room and Compose bundle their own consumer rules, so this mainly protects the app's own data/domain model classes.
- **`keystore.properties.template`** — Copy to `keystore.properties` (gitignored) and fill in real values to enable signed release builds.
- **`.gitignore`** — Excludes the keystore, `local.properties`, and build output.

## Known limitations

- No Google Fit integration (stretch goal, not implemented).
- No drag-to-reorder for interval block stages — add/remove only.
- Distance-based stage completion has ~5–10m tolerance due to GPS sampling limitations, by design.
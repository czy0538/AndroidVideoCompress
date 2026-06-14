# AndroidVideoCompress

AndroidVideoCompress is a local Android video compression app. It scans large videos from MediaStore, queues videos that exceed the configured bitrate or resolution, and recompresses them with Media3 Transformer using H.265/HEVC.

The app is designed for on-device batch processing: a foreground media-processing service keeps work alive, Room stores task state, and the UI shows current progress plus recent modified/skipped/failed files.

## Current Behavior

- Scans videos whose file size is greater than the configured minimum size.
- Marks a video for compression when its bitrate is above the target bitrate or its resolution is above the target resolution.
- Writes compressed output to a temporary cache file first.
- Replaces the original MediaStore item only when the compressed file is smaller than the original.
- Skips the replacement when output is not smaller, so the original file is preserved.
- Shows recent processed files on the main screen with status:
  - `Modified`: original file was overwritten with a smaller compressed file.
  - `Skipped`: compression ran, but output was not smaller.
  - `Failed`: compression or replacement failed.
- Provides a `Repair Modified Times` action for completed historical tasks. It updates MediaStore timestamps and best-effort restores filesystem modified time for files that were already overwritten.
- Recent history starts with the latest 20 finished tasks and can be expanded with `Show More` up to 1,000 records.
- Failed tasks can be inspected through `View Failure Logs`, with support for copying one failure log or all failure logs.
- Transformer muxing waits up to 120 seconds between output samples to avoid aborting large/slow MOV transcodes at the default timeout.

## Tech Stack

- Kotlin 2.0.21
- Android Gradle Plugin 8.7.3
- Jetpack Compose + Material3
- Media3 Transformer 1.5.1
- Room 2.6.1
- DataStore Preferences
- Coroutines

Android config:

- `compileSdk`: 35
- `targetSdk`: 35
- `minSdk`: 33
- Package name: `com.videocompress`

## Build

Use JDK 17. The current Gradle/Kotlin toolchain does not work with the machine's default Java 26.

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew test
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install

Connect an Android device with USB debugging enabled:

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The APK can also be copied to the phone and opened manually, but Android must allow installing unknown apps from that file manager or browser.

## Project Layout

```text
app/src/main/java/com/videocompress/
├── MainActivity.kt
├── VideoCompressApp.kt
├── core/
│   ├── FileHelper.kt
│   ├── VideoCompressor.kt
│   └── VideoScanner.kt
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt
│   │   ├── CompressionTask.kt
│   │   ├── CompressionTaskDao.kt
│   │   └── TaskStatus.kt
│   └── settings/
│       └── SettingsRepository.kt
├── service/
│   └── CompressionForegroundService.kt
├── ui/
│   ├── MainScreen.kt
│   ├── MainViewModel.kt
│   ├── SettingsScreen.kt
│   └── theme/
└── util/
    ├── CompressionState.kt
    └── NotificationHelper.kt
```

## Development Notes

- Compression is intentionally serial. Do not parallelize video transforms without testing device thermals, codec availability, and service stability.
- Room database is currently version 2. Add a migration whenever changing `CompressionTask`.
- Room schema files are exported under `app/schemas/` and should be committed.
- The app writes back through MediaStore URI permissions, not by directly moving files on disk.
- `originalPath` is used for display. `originalFilePath` stores the best available absolute path for best-effort modified-time restoration. On modern Android, direct filesystem paths are not always available or reliable.

More detailed engineering notes are in [docs/development.md](docs/development.md).

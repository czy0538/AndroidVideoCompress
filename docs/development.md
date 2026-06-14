# Development Guide

This document captures the current implementation rules for future development. Prefer this file over the older planning documents when behavior differs from early design notes.

## Runtime Flow

1. `MainViewModel.scanVideos()` reads settings from `SettingsRepository`.
2. `VideoScanner` queries `MediaStore.Video`.
3. Videos are marked as compression candidates when `VideoScanner.shouldCompress()` returns true.
4. `MainViewModel.onWritePermissionGranted()` creates `CompressionTask` rows in Room.
5. `CompressionForegroundService` runs the queue in a foreground service.
6. `VideoCompressor` writes each transformed video to a temporary cache file.
7. `CompressionForegroundService` compares output size with original size.
8. `FileHelper.replaceOriginalWithCompressed()` overwrites the original MediaStore item only if output is smaller.
9. The task is marked `COMPLETED`, `SKIPPED`, or `FAILED`.
10. `MainScreen` observes recent finished tasks and shows which files were modified.

## File Replacement Rule

Never write compressed output directly to the original URI.

The required sequence is:

1. Transcode to `context.cacheDir`.
2. Check `result.outputSize < task.originalSize`.
3. If smaller, overwrite `task.videoUri` through `ContentResolver.openOutputStream(uri, "wt")`.
4. If not smaller, delete the temporary file and mark the task `SKIPPED`.

This protects user files from becoming larger after compression.

## Performance Rules

- Keep compression serial. Android hardware encoders are limited resources, and parallel transcodes can fail or make the device unusably hot.
- Use `MediaStore.Video.Media.BITRATE` during scans. Only fall back to `MediaMetadataRetriever` when MediaStore bitrate is missing or zero.
- Keep notification updates throttled. The foreground service should not rebuild and publish notifications for every progress callback.
- Do not load full task lists just to count work. Use count queries such as `getUnfinishedCount()`.
- Keep Room indices aligned with queue queries. Current indices:
  - `(status, id)` for pending/in-progress queue lookup.
  - `batchId` for batch cleanup and resume flows.

## Database Rules

Room database version: `2`.

Current entity:

- `CompressionTask`
- Status values: `PENDING`, `IN_PROGRESS`, `COMPLETED`, `FAILED`, `SKIPPED`
- Completion metadata:
  - `completedAt`
  - `compressedSize`
  - `skippedReason`
  - `errorMessage`
- Path metadata:
  - `originalPath` is the display path, usually `RELATIVE_PATH + DISPLAY_NAME`.
  - `originalFilePath` is the best available absolute path from MediaStore `DATA`, used only for best-effort modified-time restoration.

When changing the schema:

1. Increment `@Database(version = ...)`.
2. Add a `Migration`.
3. Run tests to export schema into `app/schemas/`.
4. Commit the generated schema JSON.

Do not use destructive migrations for normal app upgrades because users may have unfinished compression queues.

## UI Rules

The main screen should expose enough information for the user to know what changed:

- Current file being compressed.
- Current progress and completed count.
- Recent finished tasks.
- For recent tasks, show file name, status, size change, and path/relative path when available.
- If modified time restoration fails, surface that fact in the recent task details.
- `Repair Modified Times` should only touch completed historical tasks. It must not rewrite video content or retry compression.
- History should load incrementally. Keep the default list short and use `Show More` to expand older finished tasks.
- Failed tasks should remain inspectable from the UI. The failure log viewer must show the stored error context and support copying individual logs and all logs.

Keep long paths and file names ellipsized. Many device video paths are too long for compact mobile layouts.

## Build And Test

Use JDK 17:

```bash
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew test
env JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

The current local default Java 26 causes Gradle/Kotlin DSL failure before project evaluation. Treat JDK 17 as the supported local build runtime unless the Gradle/Kotlin toolchain is upgraded and verified.

## Release Notes

The current release build type has minification disabled:

```kotlin
release {
    isMinifyEnabled = false
}
```

Before distributing outside debug installs, add proper signing configuration outside the repository. Do not commit:

- `*.jks`
- `*.keystore`
- `*.p12`
- `*.pem`
- `*.key`
- `.env`
- `local.properties`
- `google-services.json`

These are ignored by `.gitignore`.

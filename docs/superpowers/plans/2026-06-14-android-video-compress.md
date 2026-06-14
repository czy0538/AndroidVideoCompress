# Android Video Compress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an Android app that scans for large videos, batch-compresses them to H.265 using hardware-accelerated MediaCodec (via Media3 Transformer), with foreground service keep-alive and task-level resume capability.

**Architecture:** Single-Activity Compose app with 2 screens (Main + Settings). A Foreground Service drives the compression queue. Room persists task state for resume-after-interrupt. DataStore stores user preferences. Media3 Transformer handles the actual H.265 transcoding. A singleton `CompressionState` object communicates real-time progress from Service to UI.

**Tech Stack:** Kotlin 2.0, Jetpack Compose (Material3), Media3 Transformer 1.5.1, Room 2.6.1, DataStore Preferences, Foreground Service (mediaProcessing), KSP, Coroutines

---

## File Structure

```
AndroidVideoCompress/
├── gradle/
│   └── libs.versions.toml              # Version catalog
├── build.gradle.kts                     # Root build file (plugin declarations)
├── settings.gradle.kts                  # Project settings
├── gradle.properties                    # Gradle properties
├── app/
│   ├── build.gradle.kts                 # App module build file
│   └── src/main/
│       ├── AndroidManifest.xml
│       └── java/com/videocompress/
│           ├── VideoCompressApp.kt      # Application class (DI container)
│           ├── MainActivity.kt          # Single Activity, navigation, permissions
│           ├── ui/
│           │   ├── theme/
│           │   │   ├── Color.kt
│           │   │   ├── Type.kt
│           │   │   └── Theme.kt
│           │   ├── MainScreen.kt        # Video list + compression progress
│           │   ├── SettingsScreen.kt     # Compression parameter settings
│           │   └── MainViewModel.kt     # UI state management
│           ├── service/
│           │   └── CompressionForegroundService.kt
│           ├── core/
│           │   ├── VideoScanner.kt      # MediaStore query + filtering
│           │   ├── VideoCompressor.kt   # Media3 Transformer wrapper
│           │   └── FileHelper.kt        # File replacement + timestamp restore
│           ├── data/
│           │   ├── db/
│           │   │   ├── TaskStatus.kt    # Enum: PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED
│           │   │   ├── CompressionTask.kt  # Room Entity
│           │   │   ├── CompressionTaskDao.kt
│           │   │   └── AppDatabase.kt
│           │   └── settings/
│           │       └── SettingsRepository.kt  # DataStore wrapper
│           └── util/
│               ├── NotificationHelper.kt
│               └── CompressionState.kt  # Singleton progress communication
```

---

### Task 1: Project Scaffolding

**Files:**
- Create: `gradle/libs.versions.toml`
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts` (root)
- Create: `gradle.properties`
- Create: `app/build.gradle.kts`
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/com/videocompress/VideoCompressApp.kt`
- Create: `app/src/main/java/com/videocompress/ui/theme/Color.kt`
- Create: `app/src/main/java/com/videocompress/ui/theme/Type.kt`
- Create: `app/src/main/java/com/videocompress/ui/theme/Theme.kt`
- Create: `app/src/main/java/com/videocompress/MainActivity.kt` (empty shell)

- [ ] **Step 1: Generate Gradle wrapper**

```bash
cd /Users/czy0538/Documents/codes/AndroidVideoCompress
gradle wrapper --gradle-version 8.11.1
```

If `gradle` is not installed, install it first: `brew install gradle`

Expected: `gradlew`, `gradlew.bat`, and `gradle/wrapper/` files created.

- [ ] **Step 2: Create version catalog**

Create `gradle/libs.versions.toml`:

```toml
[versions]
agp = "8.7.3"
kotlin = "2.0.21"
ksp = "2.0.21-1.0.28"
composeBom = "2024.12.01"
media3 = "1.5.1"
room = "2.6.1"
datastore = "1.1.1"
coroutines = "1.9.0"
activityCompose = "1.9.3"
lifecycleViewmodel = "2.8.7"
navigationCompose = "2.8.5"
coreKtx = "1.15.0"

[libraries]
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycleViewmodel" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycleViewmodel" }
androidx-navigation-compose = { group = "androidx.navigation", name = "navigation-compose", version.ref = "navigationCompose" }

compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }

media3-transformer = { group = "androidx.media3", name = "media3-transformer", version.ref = "media3" }
media3-effect = { group = "androidx.media3", name = "media3-effect", version.ref = "media3" }
media3-common = { group = "androidx.media3", name = "media3-common", version.ref = "media3" }

room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

kotlinx-coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

- [ ] **Step 3: Create root build.gradle.kts**

Create `build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
```

- [ ] **Step 4: Create settings.gradle.kts**

Create `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "AndroidVideoCompress"
include(":app")
```

- [ ] **Step 5: Create gradle.properties**

Create `gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
kotlin.code.style=official
android.nonTransitiveRClass=true
```

- [ ] **Step 6: Create app/build.gradle.kts**

Create `app/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.videocompress"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.videocompress"
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.media3.transformer)
    implementation(libs.media3.effect)
    implementation(libs.media3.common)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    implementation(libs.kotlinx.coroutines.android)
}
```

- [ ] **Step 7: Create AndroidManifest.xml**

Create `app/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <application
        android:name=".VideoCompressApp"
        android:allowBackup="true"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/Theme.Material3.DayNight.NoActionBar">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.Material3.DayNight.NoActionBar">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <service
            android:name=".service.CompressionForegroundService"
            android:foregroundServiceType="mediaProcessing"
            android:exported="false" />
    </application>

</manifest>
```

- [ ] **Step 8: Create string resources**

Create `app/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">VideoCompress</string>
</resources>
```

- [ ] **Step 9: Create Application class**

Create `app/src/main/java/com/videocompress/VideoCompressApp.kt`:

```kotlin
package com.videocompress

import android.app.Application
import com.videocompress.data.db.AppDatabase
import com.videocompress.data.settings.SettingsRepository

class VideoCompressApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
}
```

- [ ] **Step 10: Create theme files**

Create `app/src/main/java/com/videocompress/ui/theme/Color.kt`:

```kotlin
package com.videocompress.ui.theme

import androidx.compose.ui.graphics.Color

val Purple80 = Color(0xFFD0BCFF)
val PurpleGrey80 = Color(0xFFCCC2DC)
val Pink80 = Color(0xFFEFB8C8)
val Purple40 = Color(0xFF6650a4)
val PurpleGrey40 = Color(0xFF625b71)
val Pink40 = Color(0xFF7D5260)
```

Create `app/src/main/java/com/videocompress/ui/theme/Type.kt`:

```kotlin
package com.videocompress.ui.theme

import androidx.compose.material3.Typography

val Typography = Typography()
```

Create `app/src/main/java/com/videocompress/ui/theme/Theme.kt`:

```kotlin
package com.videocompress.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Purple80,
    secondary = PurpleGrey80,
    tertiary = Pink80
)

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40
)

@Composable
fun VideoCompressTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
```

- [ ] **Step 11: Create empty MainActivity shell**

Create `app/src/main/java/com/videocompress/MainActivity.kt`:

```kotlin
package com.videocompress

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.Text
import com.videocompress.ui.theme.VideoCompressTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VideoCompressTheme {
                Text("VideoCompress")
            }
        }
    }
}
```

- [ ] **Step 12: Build to verify project setup**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. If it fails, fix the build errors before proceeding.

- [ ] **Step 13: Commit**

```bash
git add -A
git commit -m "feat: project scaffolding with Gradle, Compose, Media3, Room dependencies"
```

---

### Task 2: Data Layer

**Files:**
- Create: `app/src/main/java/com/videocompress/data/db/TaskStatus.kt`
- Create: `app/src/main/java/com/videocompress/data/db/CompressionTask.kt`
- Create: `app/src/main/java/com/videocompress/data/db/CompressionTaskDao.kt`
- Create: `app/src/main/java/com/videocompress/data/db/AppDatabase.kt`
- Create: `app/src/main/java/com/videocompress/data/settings/SettingsRepository.kt`

- [ ] **Step 1: Create TaskStatus enum**

Create `app/src/main/java/com/videocompress/data/db/TaskStatus.kt`:

```kotlin
package com.videocompress.data.db

enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    SKIPPED
}
```

- [ ] **Step 2: Create CompressionTask entity**

Create `app/src/main/java/com/videocompress/data/db/CompressionTask.kt`:

```kotlin
package com.videocompress.data.db

import android.net.Uri
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "compression_tasks")
data class CompressionTask(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoUriString: String,
    val displayName: String,
    val originalSize: Long,
    val originalBitrate: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val originalDateAdded: Long,
    val originalDateModified: Long,
    val originalPath: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val errorMessage: String? = null,
    val compressedSize: Long? = null,
    val batchId: String
) {
    val videoUri: Uri get() = Uri.parse(videoUriString)
}
```

- [ ] **Step 3: Create CompressionTaskDao**

Create `app/src/main/java/com/videocompress/data/db/CompressionTaskDao.kt`:

```kotlin
package com.videocompress.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface CompressionTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tasks: List<CompressionTask>)

    @Query("SELECT * FROM compression_tasks WHERE batchId = :batchId ORDER BY id ASC")
    fun getTasksByBatch(batchId: String): Flow<List<CompressionTask>>

    @Query("SELECT * FROM compression_tasks WHERE status IN ('PENDING', 'IN_PROGRESS') ORDER BY id ASC LIMIT 1")
    suspend fun getNextPendingTask(): CompressionTask?

    @Query("SELECT COUNT(*) FROM compression_tasks WHERE status IN ('PENDING', 'IN_PROGRESS')")
    suspend fun getUnfinishedCount(): Int

    @Query("SELECT * FROM compression_tasks WHERE status IN ('PENDING', 'IN_PROGRESS') ORDER BY id ASC")
    suspend fun getUnfinishedTasks(): List<CompressionTask>

    @Query("UPDATE compression_tasks SET status = :status, errorMessage = :error, compressedSize = :compressedSize WHERE id = :taskId")
    suspend fun updateTaskStatus(taskId: Long, status: TaskStatus, error: String? = null, compressedSize: Long? = null)

    @Query("UPDATE compression_tasks SET status = 'PENDING' WHERE status = 'IN_PROGRESS'")
    suspend fun resetInProgressTasks()

    @Query("SELECT * FROM compression_tasks WHERE batchId = (SELECT batchId FROM compression_tasks WHERE status IN ('PENDING', 'IN_PROGRESS') LIMIT 1) ORDER BY id ASC")
    fun getActiveTasksBatch(): Flow<List<CompressionTask>>

    @Query("SELECT batchId FROM compression_tasks WHERE status IN ('PENDING', 'IN_PROGRESS') LIMIT 1")
    suspend fun getActiveBatchId(): String?

    @Query("DELETE FROM compression_tasks WHERE batchId = :batchId")
    suspend fun deleteTasksByBatch(batchId: String)
}
```

- [ ] **Step 4: Create AppDatabase**

Create `app/src/main/java/com/videocompress/data/db/AppDatabase.kt`:

```kotlin
package com.videocompress.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter
    fun toTaskStatus(value: String): TaskStatus = TaskStatus.valueOf(value)

    @TypeConverter
    fun fromTaskStatus(status: TaskStatus): String = status.name
}

@Database(entities = [CompressionTask::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun compressionTaskDao(): CompressionTaskDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "video_compress_db"
                ).build().also { INSTANCE = it }
            }
        }
    }
}
```

- [ ] **Step 5: Create SettingsRepository**

Create `app/src/main/java/com/videocompress/data/settings/SettingsRepository.kt`:

```kotlin
package com.videocompress.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        val TARGET_BITRATE_MBPS = intPreferencesKey("target_bitrate_mbps")
        val TARGET_RESOLUTION = intPreferencesKey("target_resolution")
        val MIN_FILE_SIZE_MB = longPreferencesKey("min_file_size_mb")

        const val DEFAULT_BITRATE_MBPS = 8
        const val DEFAULT_RESOLUTION = 1080
        const val DEFAULT_MIN_SIZE_MB = 100L

        const val RESOLUTION_720P = 720
        const val RESOLUTION_1080P = 1080
        const val RESOLUTION_ORIGINAL = -1
    }

    val targetBitrateMbps: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TARGET_BITRATE_MBPS] ?: DEFAULT_BITRATE_MBPS
    }

    val targetResolution: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[TARGET_RESOLUTION] ?: DEFAULT_RESOLUTION
    }

    val minFileSizeMb: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[MIN_FILE_SIZE_MB] ?: DEFAULT_MIN_SIZE_MB
    }

    suspend fun setTargetBitrateMbps(value: Int) {
        context.dataStore.edit { it[TARGET_BITRATE_MBPS] = value }
    }

    suspend fun setTargetResolution(value: Int) {
        context.dataStore.edit { it[TARGET_RESOLUTION] = value }
    }

    suspend fun setMinFileSizeMb(value: Long) {
        context.dataStore.edit { it[MIN_FILE_SIZE_MB] = value }
    }
}
```

- [ ] **Step 6: Build to verify data layer compiles**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/videocompress/data/
git commit -m "feat: add data layer - Room entities, DAO, database, settings repository"
```

---

### Task 3: Video Scanner

**Files:**
- Create: `app/src/main/java/com/videocompress/core/VideoScanner.kt`

- [ ] **Step 1: Create VideoScanner**

Create `app/src/main/java/com/videocompress/core/VideoScanner.kt`:

```kotlin
package com.videocompress.core

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class VideoInfo(
    val uri: Uri,
    val displayName: String,
    val size: Long,
    val bitrate: Long,
    val width: Int,
    val height: Int,
    val dateAdded: Long,
    val dateModified: Long,
    val path: String,
    val needsCompression: Boolean
)

class VideoScanner(private val context: Context) {

    suspend fun scanVideos(
        minSizeBytes: Long,
        targetBitrateBps: Long,
        targetResolution: Int
    ): List<VideoInfo> = withContext(Dispatchers.IO) {
        val videos = mutableListOf<VideoInfo>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.DATE_MODIFIED,
            MediaStore.Video.Media.DATA
        )

        val selection = "${MediaStore.Video.Media.SIZE} >= ?"
        val selectionArgs = arrayOf(minSizeBytes.toString())
        val sortOrder = "${MediaStore.Video.Media.SIZE} DESC"

        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)

        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val dateAddedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val dateModifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
            val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idColumn)
                val uri = Uri.withAppendedPath(collection, id.toString())
                val name = cursor.getString(nameColumn)
                val size = cursor.getLong(sizeColumn)
                val width = cursor.getInt(widthColumn)
                val height = cursor.getInt(heightColumn)
                val dateAdded = cursor.getLong(dateAddedColumn)
                val dateModified = cursor.getLong(dateModifiedColumn)
                val path = cursor.getString(dataColumn) ?: ""

                val bitrate = getBitrate(uri)
                val needsCompression = shouldCompress(bitrate, width, height, targetBitrateBps, targetResolution)

                videos.add(
                    VideoInfo(
                        uri = uri,
                        displayName = name,
                        size = size,
                        bitrate = bitrate,
                        width = width,
                        height = height,
                        dateAdded = dateAdded,
                        dateModified = dateModified,
                        path = path,
                        needsCompression = needsCompression
                    )
                )
            }
        }
        videos
    }

    private fun getBitrate(uri: Uri): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, uri)
            val bitrate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull() ?: 0L
            retriever.release()
            bitrate
        } catch (e: Exception) {
            0L
        }
    }

    companion object {
        fun shouldCompress(
            bitrate: Long,
            width: Int,
            height: Int,
            targetBitrateBps: Long,
            targetResolution: Int
        ): Boolean {
            if (targetResolution == -1) {
                return bitrate > targetBitrateBps
            }
            val shortSide = minOf(width, height)
            val longSide = maxOf(width, height)
            val targetLong = when (targetResolution) {
                720 -> 1280
                1080 -> 1920
                else -> return bitrate > targetBitrateBps
            }
            val targetShort = targetResolution
            return bitrate > targetBitrateBps || longSide > targetLong || shortSide > targetShort
        }
    }
}
```

- [ ] **Step 2: Write unit test for shouldCompress**

Create `app/src/test/java/com/videocompress/core/VideoScannerTest.kt`:

```kotlin
package com.videocompress.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoScannerTest {

    private val target8Mbps = 8_000_000L
    private val target1080p = 1080

    @Test
    fun `high bitrate and high resolution should compress`() {
        assertTrue(VideoScanner.shouldCompress(15_000_000L, 3840, 2160, target8Mbps, target1080p))
    }

    @Test
    fun `high bitrate but low resolution should compress`() {
        assertTrue(VideoScanner.shouldCompress(15_000_000L, 1920, 1080, target8Mbps, target1080p))
    }

    @Test
    fun `low bitrate but high resolution should compress`() {
        assertTrue(VideoScanner.shouldCompress(5_000_000L, 3840, 2160, target8Mbps, target1080p))
    }

    @Test
    fun `low bitrate and low resolution should not compress`() {
        assertFalse(VideoScanner.shouldCompress(5_000_000L, 1920, 1080, target8Mbps, target1080p))
    }

    @Test
    fun `exact target values should not compress`() {
        assertFalse(VideoScanner.shouldCompress(8_000_000L, 1920, 1080, target8Mbps, target1080p))
    }

    @Test
    fun `portrait video high resolution should compress`() {
        assertTrue(VideoScanner.shouldCompress(5_000_000L, 2160, 3840, target8Mbps, target1080p))
    }

    @Test
    fun `portrait video low resolution should not compress`() {
        assertFalse(VideoScanner.shouldCompress(5_000_000L, 1080, 1920, target8Mbps, target1080p))
    }

    @Test
    fun `original resolution target only checks bitrate`() {
        assertFalse(VideoScanner.shouldCompress(5_000_000L, 3840, 2160, target8Mbps, -1))
        assertTrue(VideoScanner.shouldCompress(15_000_000L, 3840, 2160, target8Mbps, -1))
    }
}
```

- [ ] **Step 3: Run test to verify it passes**

```bash
./gradlew test
```

Expected: All 8 tests PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/videocompress/core/VideoScanner.kt app/src/test/
git commit -m "feat: add VideoScanner with MediaStore query and filtering logic"
```

---

### Task 4: Video Compressor & Compression State

**Files:**
- Create: `app/src/main/java/com/videocompress/util/CompressionState.kt`
- Create: `app/src/main/java/com/videocompress/core/VideoCompressor.kt`

- [ ] **Step 1: Create CompressionState singleton**

Create `app/src/main/java/com/videocompress/util/CompressionState.kt`:

```kotlin
package com.videocompress.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object CompressionState {
    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning

    private val _currentFileName = MutableStateFlow("")
    val currentFileName: StateFlow<String> = _currentFileName

    private val _currentProgress = MutableStateFlow(0)
    val currentProgress: StateFlow<Int> = _currentProgress

    private val _completedCount = MutableStateFlow(0)
    val completedCount: StateFlow<Int> = _completedCount

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount

    private val _savedBytes = MutableStateFlow(0L)
    val savedBytes: StateFlow<Long> = _savedBytes

    fun updateProgress(fileName: String, progress: Int) {
        _currentFileName.value = fileName
        _currentProgress.value = progress
    }

    fun updateCounts(completed: Int, total: Int) {
        _completedCount.value = completed
        _totalCount.value = total
    }

    fun addSavedBytes(bytes: Long) {
        _savedBytes.value += bytes
    }

    fun start(total: Int) {
        _isRunning.value = true
        _totalCount.value = total
        _completedCount.value = 0
        _currentProgress.value = 0
        _savedBytes.value = 0
    }

    fun stop() {
        _isRunning.value = false
        _currentFileName.value = ""
        _currentProgress.value = 0
    }
}
```

- [ ] **Step 2: Create VideoCompressor**

Create `app/src/main/java/com/videocompress/core/VideoCompressor.kt`:

```kotlin
package com.videocompress.core

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class CompressionResult(
    val outputFile: File,
    val outputSize: Long
)

@UnstableApi
class VideoCompressor(private val context: Context) {

    suspend fun compress(
        inputUri: Uri,
        targetBitrateBps: Int,
        targetResolution: Int,
        originalWidth: Int,
        originalHeight: Int,
        onProgress: (Int) -> Unit
    ): CompressionResult = withContext(Dispatchers.Main) {
        val outputFile = File(context.cacheDir, "compress_temp_${System.currentTimeMillis()}.mp4")

        val encoderFactory = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(
                VideoEncoderSettings.Builder()
                    .setBitrate(targetBitrateBps)
                    .build()
            )
            .build()

        val videoEffects = buildVideoEffects(targetResolution, originalWidth, originalHeight)

        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H265)
            .setEncoderFactory(encoderFactory)
            .build()

        val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
            .setEffects(Effects(emptyList(), videoEffects))
            .build()

        suspendCancellableCoroutine { continuation ->
            val progressHolder = ProgressHolder()
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val progressRunnable = object : Runnable {
                override fun run() {
                    if (!continuation.isActive) return
                    val state = transformer.getProgress(progressHolder)
                    if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress(progressHolder.progress)
                    }
                    handler.postDelayed(this, 500)
                }
            }
            handler.postDelayed(progressRunnable, 500)

            transformer.addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    handler.removeCallbacks(progressRunnable)
                    val result = CompressionResult(
                        outputFile = outputFile,
                        outputSize = outputFile.length()
                    )
                    if (continuation.isActive) continuation.resume(result)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException
                ) {
                    handler.removeCallbacks(progressRunnable)
                    outputFile.delete()
                    if (continuation.isActive) continuation.resumeWithException(exportException)
                }
            })

            transformer.start(editedMediaItem, outputFile.absolutePath)

            continuation.invokeOnCancellation {
                handler.removeCallbacks(progressRunnable)
                transformer.cancel()
                outputFile.delete()
            }
        }
    }

    private fun buildVideoEffects(
        targetResolution: Int,
        originalWidth: Int,
        originalHeight: Int
    ): List<androidx.media3.common.Effect> {
        if (targetResolution == -1) return emptyList()

        val shortSide = minOf(originalWidth, originalHeight)
        val longSide = maxOf(originalWidth, originalHeight)
        val targetShort = targetResolution
        val targetLong = when (targetResolution) {
            720 -> 1280
            1080 -> 1920
            else -> return emptyList()
        }

        if (longSide <= targetLong && shortSide <= targetShort) return emptyList()

        val isPortrait = originalHeight > originalWidth
        val (w, h) = if (isPortrait) targetShort to targetLong else targetLong to targetShort

        return listOf(
            Presentation.createForWidthAndHeight(w, h, Presentation.LAYOUT_SCALE_TO_FIT)
        )
    }
}
```

- [ ] **Step 3: Build to verify compilation**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL. Note: Media3 `@UnstableApi` annotation is required for Transformer APIs.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/videocompress/core/VideoCompressor.kt app/src/main/java/com/videocompress/util/CompressionState.kt
git commit -m "feat: add VideoCompressor (Media3 Transformer H.265) and CompressionState"
```

---

### Task 5: File Helper

**Files:**
- Create: `app/src/main/java/com/videocompress/core/FileHelper.kt`

- [ ] **Step 1: Create FileHelper**

Create `app/src/main/java/com/videocompress/core/FileHelper.kt`:

```kotlin
package com.videocompress.core

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class FileHelper(private val context: Context) {

    suspend fun replaceOriginalWithCompressed(
        originalUri: Uri,
        compressedFile: File,
        originalDateAdded: Long,
        originalDateModified: Long,
        originalPath: String
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.openOutputStream(originalUri, "wt")?.use { outputStream ->
                FileInputStream(compressedFile).use { inputStream ->
                    inputStream.copyTo(outputStream)
                }
            } ?: return@withContext false

            val values = ContentValues().apply {
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.DATE_MODIFIED, originalDateModified)
            }
            context.contentResolver.update(originalUri, values, null, null)

            if (originalPath.isNotEmpty()) {
                try {
                    val originalFile = File(originalPath)
                    originalFile.setLastModified(originalDateModified * 1000)
                } catch (_: Exception) {
                }
            }

            compressedFile.delete()
            true
        } catch (e: Exception) {
            compressedFile.delete()
            false
        }
    }

    fun cleanupTempFiles() {
        context.cacheDir.listFiles()?.filter {
            it.name.startsWith("compress_temp_") && it.name.endsWith(".mp4")
        }?.forEach { it.delete() }
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/videocompress/core/FileHelper.kt
git commit -m "feat: add FileHelper for file replacement and timestamp restoration"
```

---

### Task 6: Notification Helper

**Files:**
- Create: `app/src/main/java/com/videocompress/util/NotificationHelper.kt`

- [ ] **Step 1: Create NotificationHelper**

Create `app/src/main/java/com/videocompress/util/NotificationHelper.kt`:

```kotlin
package com.videocompress.util

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.videocompress.MainActivity

class NotificationHelper(private val context: Context) {

    companion object {
        const val CHANNEL_ID = "video_compression"
        const val NOTIFICATION_ID = 1
        const val COMPLETED_NOTIFICATION_ID = 2
        const val ACTION_STOP = "com.videocompress.ACTION_STOP"
    }

    init {
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Video Compression",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Video compression progress"
        }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun buildProgressNotification(
        fileName: String,
        currentProgress: Int,
        completedCount: Int,
        totalCount: Int
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val stopIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(ACTION_STOP).setPackage(context.packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_rotate)
            .setContentTitle("Compressing: $fileName")
            .setContentText("Progress: $completedCount/$totalCount")
            .setProgress(100, currentProgress, false)
            .setContentIntent(contentIntent)
            .addAction(android.R.drawable.ic_media_pause, "Stop", stopIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    fun buildCompletedNotification(totalCompressed: Int, savedMb: Long): Notification {
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_save)
            .setContentTitle("Compression Complete")
            .setContentText("Compressed $totalCompressed videos, saved ${savedMb}MB")
            .setAutoCancel(true)
            .build()
    }

    fun showCompletedNotification(totalCompressed: Int, savedMb: Long) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(COMPLETED_NOTIFICATION_ID, buildCompletedNotification(totalCompressed, savedMb))
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/videocompress/util/NotificationHelper.kt
git commit -m "feat: add NotificationHelper with progress and completion notifications"
```

---

### Task 7: Foreground Service

**Files:**
- Create: `app/src/main/java/com/videocompress/service/CompressionForegroundService.kt`

- [ ] **Step 1: Create CompressionForegroundService**

Create `app/src/main/java/com/videocompress/service/CompressionForegroundService.kt`:

```kotlin
package com.videocompress.service

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.IBinder
import android.os.PowerManager
import androidx.media3.common.util.UnstableApi
import com.videocompress.VideoCompressApp
import com.videocompress.core.FileHelper
import com.videocompress.core.VideoCompressor
import com.videocompress.data.db.CompressionTaskDao
import com.videocompress.data.db.TaskStatus
import com.videocompress.data.settings.SettingsRepository
import com.videocompress.util.CompressionState
import com.videocompress.util.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@UnstableApi
class CompressionForegroundService : Service() {

    private lateinit var dao: CompressionTaskDao
    private lateinit var settingsRepo: SettingsRepository
    private lateinit var compressor: VideoCompressor
    private lateinit var fileHelper: FileHelper
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var wakeLock: PowerManager.WakeLock

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var compressionJob: Job? = null

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            stopCompression()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val app = application as VideoCompressApp
        dao = app.database.compressionTaskDao()
        settingsRepo = app.settingsRepository
        compressor = VideoCompressor(this)
        fileHelper = FileHelper(this)
        notificationHelper = NotificationHelper(this)

        val powerManager = getSystemService(PowerManager::class.java)
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoCompress::Compression")

        registerReceiver(stopReceiver, IntentFilter(NotificationHelper.ACTION_STOP), RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = notificationHelper.buildProgressNotification("Preparing...", 0, 0, 0)
        startForeground(NotificationHelper.NOTIFICATION_ID, notification)

        if (compressionJob == null || compressionJob?.isActive != true) {
            startCompression()
        }

        return START_STICKY
    }

    private fun startCompression() {
        compressionJob = serviceScope.launch {
            wakeLock.acquire(10 * 60 * 60 * 1000L) // 10 hours max
            try {
                dao.resetInProgressTasks()
                fileHelper.cleanupTempFiles()

                val targetBitrateBps = settingsRepo.targetBitrateMbps.first() * 1_000_000
                val targetResolution = settingsRepo.targetResolution.first()

                val unfinished = dao.getUnfinishedTasks()
                val totalCount = unfinished.size
                if (totalCount == 0) {
                    stopSelf()
                    return@launch
                }

                CompressionState.start(totalCount)
                var completedCount = 0

                while (true) {
                    val task = dao.getNextPendingTask() ?: break

                    dao.updateTaskStatus(task.id, TaskStatus.IN_PROGRESS)
                    CompressionState.updateProgress(task.displayName, 0)
                    CompressionState.updateCounts(completedCount, totalCount)

                    updateNotification(task.displayName, 0, completedCount, totalCount)

                    try {
                        val result = compressor.compress(
                            inputUri = task.videoUri,
                            targetBitrateBps = targetBitrateBps,
                            targetResolution = targetResolution,
                            originalWidth = task.originalWidth,
                            originalHeight = task.originalHeight,
                            onProgress = { progress ->
                                CompressionState.updateProgress(task.displayName, progress)
                                updateNotification(task.displayName, progress, completedCount, totalCount)
                            }
                        )

                        val replaced = fileHelper.replaceOriginalWithCompressed(
                            originalUri = task.videoUri,
                            compressedFile = result.outputFile,
                            originalDateAdded = task.originalDateAdded,
                            originalDateModified = task.originalDateModified,
                            originalPath = task.originalPath
                        )

                        if (replaced) {
                            val savedBytes = task.originalSize - result.outputSize
                            CompressionState.addSavedBytes(savedBytes)
                            dao.updateTaskStatus(task.id, TaskStatus.COMPLETED, compressedSize = result.outputSize)
                        } else {
                            dao.updateTaskStatus(task.id, TaskStatus.FAILED, error = "Failed to replace file")
                        }
                    } catch (e: Exception) {
                        dao.updateTaskStatus(task.id, TaskStatus.FAILED, error = e.message)
                    }

                    completedCount++
                    CompressionState.updateCounts(completedCount, totalCount)
                }

                val savedMb = CompressionState.savedBytes.value / (1024 * 1024)
                notificationHelper.showCompletedNotification(completedCount, savedMb)
            } finally {
                if (wakeLock.isHeld) wakeLock.release()
                CompressionState.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    private fun stopCompression() {
        compressionJob?.cancel()
        CompressionState.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun updateNotification(fileName: String, progress: Int, completed: Int, total: Int) {
        val notification = notificationHelper.buildProgressNotification(fileName, progress, completed, total)
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.notify(NotificationHelper.NOTIFICATION_ID, notification)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(stopReceiver)
        serviceScope.cancel()
        if (wakeLock.isHeld) wakeLock.release()
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/videocompress/service/
git commit -m "feat: add CompressionForegroundService with queue processing and keep-alive"
```

---

### Task 8: ViewModel

**Files:**
- Create: `app/src/main/java/com/videocompress/ui/MainViewModel.kt`

- [ ] **Step 1: Create MainViewModel**

Create `app/src/main/java/com/videocompress/ui/MainViewModel.kt`:

```kotlin
package com.videocompress.ui

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videocompress.VideoCompressApp
import com.videocompress.core.VideoInfo
import com.videocompress.core.VideoScanner
import com.videocompress.data.db.CompressionTask
import com.videocompress.data.db.TaskStatus
import com.videocompress.data.settings.SettingsRepository
import com.videocompress.service.CompressionForegroundService
import com.videocompress.util.CompressionState
import com.videocompress.util.NotificationHelper
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class MainUiState(
    val videos: List<VideoInfo> = emptyList(),
    val isScanning: Boolean = false,
    val hasUnfinishedTasks: Boolean = false
)

sealed class MainEvent {
    data class RequestWritePermission(val pendingIntent: PendingIntent, val uris: List<Uri>) : MainEvent()
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as VideoCompressApp
    private val dao = app.database.compressionTaskDao()
    private val settingsRepo = app.settingsRepository
    private val scanner = VideoScanner(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState

    private val _events = MutableSharedFlow<MainEvent>()
    val events = _events.asSharedFlow()

    val isCompressing = CompressionState.isRunning
    val currentFileName = CompressionState.currentFileName
    val currentProgress = CompressionState.currentProgress
    val completedCount = CompressionState.completedCount
    val totalCount = CompressionState.totalCount
    val savedBytes = CompressionState.savedBytes

    val targetBitrate = settingsRepo.targetBitrateMbps.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_BITRATE_MBPS
    )
    val targetResolution = settingsRepo.targetResolution.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_RESOLUTION
    )
    val minFileSize = settingsRepo.minFileSizeMb.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5000), SettingsRepository.DEFAULT_MIN_SIZE_MB
    )

    init {
        checkUnfinishedTasks()
    }

    private fun checkUnfinishedTasks() {
        viewModelScope.launch {
            val count = dao.getUnfinishedCount()
            _uiState.value = _uiState.value.copy(hasUnfinishedTasks = count > 0)
        }
    }

    fun scanVideos() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isScanning = true)
            val minSizeBytes = settingsRepo.minFileSizeMb.first() * 1024 * 1024
            val targetBitrateBps = settingsRepo.targetBitrateMbps.first() * 1_000_000L
            val targetRes = settingsRepo.targetResolution.first()

            val videos = scanner.scanVideos(minSizeBytes, targetBitrateBps, targetRes)
            _uiState.value = _uiState.value.copy(videos = videos, isScanning = false)
        }
    }

    fun startCompression() {
        viewModelScope.launch {
            val videosToCompress = _uiState.value.videos.filter { it.needsCompression }
            if (videosToCompress.isEmpty()) return@launch

            val uris = videosToCompress.map { it.uri }
            val pendingIntent = MediaStore.createWriteRequest(
                getApplication<VideoCompressApp>().contentResolver,
                uris
            )
            _events.emit(MainEvent.RequestWritePermission(pendingIntent, uris))
        }
    }

    fun onWritePermissionGranted() {
        viewModelScope.launch {
            val videosToCompress = _uiState.value.videos.filter { it.needsCompression }
            if (videosToCompress.isEmpty()) return@launch

            val batchId = UUID.randomUUID().toString()
            val tasks = videosToCompress.map { video ->
                CompressionTask(
                    videoUriString = video.uri.toString(),
                    displayName = video.displayName,
                    originalSize = video.size,
                    originalBitrate = video.bitrate,
                    originalWidth = video.width,
                    originalHeight = video.height,
                    originalDateAdded = video.dateAdded,
                    originalDateModified = video.dateModified,
                    originalPath = video.path,
                    batchId = batchId
                )
            }
            dao.insertAll(tasks)

            val context = getApplication<VideoCompressApp>()
            val intent = Intent(context, CompressionForegroundService::class.java)
            context.startForegroundService(intent)
        }
    }

    fun resumeUnfinishedTasks() {
        viewModelScope.launch {
            val context = getApplication<VideoCompressApp>()
            val intent = Intent(context, CompressionForegroundService::class.java)
            context.startForegroundService(intent)
            _uiState.value = _uiState.value.copy(hasUnfinishedTasks = false)
        }
    }

    fun dismissUnfinishedTasks() {
        viewModelScope.launch {
            val batchId = dao.getActiveBatchId()
            if (batchId != null) {
                dao.deleteTasksByBatch(batchId)
            }
            _uiState.value = _uiState.value.copy(hasUnfinishedTasks = false)
        }
    }

    fun stopCompression() {
        val context = getApplication<VideoCompressApp>()
        val intent = Intent(NotificationHelper.ACTION_STOP).setPackage(context.packageName)
        context.sendBroadcast(intent)
    }

    fun updateTargetBitrate(mbps: Int) {
        viewModelScope.launch { settingsRepo.setTargetBitrateMbps(mbps) }
    }

    fun updateTargetResolution(resolution: Int) {
        viewModelScope.launch { settingsRepo.setTargetResolution(resolution) }
    }

    fun updateMinFileSize(mb: Long) {
        viewModelScope.launch { settingsRepo.setMinFileSizeMb(mb) }
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/videocompress/ui/MainViewModel.kt
git commit -m "feat: add MainViewModel with scan, compress, resume, and settings management"
```

---

### Task 9: Settings Screen

**Files:**
- Create: `app/src/main/java/com/videocompress/ui/SettingsScreen.kt`

- [ ] **Step 1: Create SettingsScreen**

Create `app/src/main/java/com/videocompress/ui/SettingsScreen.kt`:

```kotlin
package com.videocompress.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videocompress.data.settings.SettingsRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    onBack: () -> Unit
) {
    val targetBitrate by viewModel.targetBitrate.collectAsStateWithLifecycle()
    val targetResolution by viewModel.targetResolution.collectAsStateWithLifecycle()
    val minFileSize by viewModel.minFileSize.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Compression Parameters", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(16.dp))

                    var bitrateText by remember(targetBitrate) { mutableStateOf(targetBitrate.toString()) }
                    OutlinedTextField(
                        value = bitrateText,
                        onValueChange = { input ->
                            bitrateText = input
                            input.toIntOrNull()?.let { if (it > 0) viewModel.updateTargetBitrate(it) }
                        },
                        label = { Text("Target Bitrate (Mbps)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Target Resolution", style = MaterialTheme.typography.bodyLarge)
                    Spacer(modifier = Modifier.height(8.dp))

                    val resolutionOptions = listOf(
                        SettingsRepository.RESOLUTION_720P to "720p",
                        SettingsRepository.RESOLUTION_1080P to "1080p",
                        SettingsRepository.RESOLUTION_ORIGINAL to "Original"
                    )

                    resolutionOptions.forEach { (value, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            RadioButton(
                                selected = targetResolution == value,
                                onClick = { viewModel.updateTargetResolution(value) }
                            )
                            Text(label, modifier = Modifier.padding(start = 8.dp))
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    var sizeText by remember(minFileSize) { mutableStateOf(minFileSize.toString()) }
                    OutlinedTextField(
                        value = sizeText,
                        onValueChange = { input ->
                            sizeText = input
                            input.toLongOrNull()?.let { if (it > 0) viewModel.updateMinFileSize(it) }
                        },
                        label = { Text("Minimum File Size (MB)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("About", style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Only videos with bitrate or resolution exceeding target values will be compressed. Output format: H.265 (HEVC).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/videocompress/ui/SettingsScreen.kt
git commit -m "feat: add SettingsScreen with bitrate, resolution, and file size controls"
```

---

### Task 10: Main Screen

**Files:**
- Create: `app/src/main/java/com/videocompress/ui/MainScreen.kt`

- [ ] **Step 1: Create MainScreen**

Create `app/src/main/java/com/videocompress/ui/MainScreen.kt`:

```kotlin
package com.videocompress.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.videocompress.core.VideoInfo

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    viewModel: MainViewModel,
    onNavigateToSettings: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val isCompressing by viewModel.isCompressing.collectAsStateWithLifecycle()
    val currentFileName by viewModel.currentFileName.collectAsStateWithLifecycle()
    val currentProgress by viewModel.currentProgress.collectAsStateWithLifecycle()
    val completedCount by viewModel.completedCount.collectAsStateWithLifecycle()
    val totalCount by viewModel.totalCount.collectAsStateWithLifecycle()
    val savedBytes by viewModel.savedBytes.collectAsStateWithLifecycle()

    if (uiState.hasUnfinishedTasks) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissUnfinishedTasks() },
            title = { Text("Unfinished Tasks") },
            text = { Text("There are unfinished compression tasks. Continue?") },
            confirmButton = {
                TextButton(onClick = { viewModel.resumeUnfinishedTasks() }) {
                    Text("Continue")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissUnfinishedTasks() }) {
                    Text("Discard")
                }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("VideoCompress") },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (isCompressing) {
                CompressionProgressSection(
                    currentFileName = currentFileName,
                    currentProgress = currentProgress,
                    completedCount = completedCount,
                    totalCount = totalCount,
                    savedBytes = savedBytes,
                    onStop = { viewModel.stopCompression() }
                )
            } else {
                Button(
                    onClick = { viewModel.scanVideos() },
                    enabled = !uiState.isScanning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (uiState.isScanning) {
                        CircularProgressIndicator(modifier = Modifier.padding(end = 8.dp))
                    }
                    Text(if (uiState.isScanning) "Scanning..." else "Scan Videos")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (uiState.videos.isNotEmpty() && !isCompressing) {
                val toCompress = uiState.videos.filter { it.needsCompression }
                val estimatedSaving = toCompress.sumOf { it.size } / 2

                Text(
                    "Found ${toCompress.size} videos to compress (${uiState.videos.size} total)",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    "Estimated saving: ~${estimatedSaving / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = { viewModel.startCompression() },
                    enabled = toCompress.isNotEmpty(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Start Compression (${toCompress.size} videos)")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(uiState.videos, key = { it.uri.toString() }) { video ->
                    VideoItem(video)
                }
            }
        }
    }
}

@Composable
private fun CompressionProgressSection(
    currentFileName: String,
    currentProgress: Int,
    completedCount: Int,
    totalCount: Int,
    savedBytes: Long,
    onStop: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text("Compressing...", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))

            Text(
                currentFileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { currentProgress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("$currentProgress%", style = MaterialTheme.typography.bodySmall)
                Text("$completedCount / $totalCount", style = MaterialTheme.typography.bodySmall)
            }

            if (savedBytes > 0) {
                Text(
                    "Saved: ${savedBytes / (1024 * 1024)} MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = onStop,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Stop")
            }
        }
    }
}

@Composable
private fun VideoItem(video: VideoInfo) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = if (!video.needsCompression) {
            CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                video.displayName,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    formatFileSize(video.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${video.width}x${video.height}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${video.bitrate / 1_000_000}Mbps",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!video.needsCompression) {
                Text(
                    "No compression needed",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        String.format("%.1f GB", mb / 1024.0)
    } else {
        String.format("%.0f MB", mb)
    }
}
```

- [ ] **Step 2: Build to verify**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/videocompress/ui/MainScreen.kt
git commit -m "feat: add MainScreen with video list, progress display, and compression controls"
```

---

### Task 11: Navigation & Permissions (MainActivity)

**Files:**
- Modify: `app/src/main/java/com/videocompress/MainActivity.kt`

- [ ] **Step 1: Replace MainActivity with full implementation**

Replace the contents of `app/src/main/java/com/videocompress/MainActivity.kt`:

```kotlin
package com.videocompress

import android.Manifest
import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.ViewModelProvider
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.videocompress.ui.MainEvent
import com.videocompress.ui.MainScreen
import com.videocompress.ui.MainViewModel
import com.videocompress.ui.SettingsScreen
import com.videocompress.ui.theme.VideoCompressTheme

class MainActivity : ComponentActivity() {

    private lateinit var viewModel: MainViewModel

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (!allGranted) {
            Toast.makeText(this, "Permissions required to scan and compress videos", Toast.LENGTH_LONG).show()
        }
    }

    private val writeRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.onWritePermissionGranted()
        } else {
            Toast.makeText(this, "Write permission denied", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        viewModel = ViewModelProvider(this)[MainViewModel::class.java]

        requestPermissions()

        setContent {
            VideoCompressTheme {
                val navController = rememberNavController()

                LaunchedEffect(Unit) {
                    viewModel.events.collect { event ->
                        when (event) {
                            is MainEvent.RequestWritePermission -> {
                                val request = IntentSenderRequest.Builder(event.pendingIntent.intentSender).build()
                                writeRequestLauncher.launch(request)
                            }
                        }
                    }
                }

                NavHost(navController = navController, startDestination = "main") {
                    composable("main") {
                        MainScreen(
                            viewModel = viewModel,
                            onNavigateToSettings = { navController.navigate("settings") }
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = viewModel,
                            onBack = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    private fun requestPermissions() {
        permissionLauncher.launch(
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        )
    }
}
```

- [ ] **Step 2: Build to verify full app compiles**

```bash
./gradlew assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/videocompress/MainActivity.kt
git commit -m "feat: wire up navigation, permissions, and write request flow in MainActivity"
```

---

### Task 12: Final Build, Install & Manual Test

- [ ] **Step 1: Full clean build**

```bash
./gradlew clean assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 2: Install on device**

Connect the OPPO device via USB with developer mode enabled, then:

```bash
./gradlew installDebug
```

Expected: App installed successfully.

- [ ] **Step 3: Manual test checklist**

Test the following scenarios on device:

1. **Permission flow**: App requests READ_MEDIA_VIDEO and POST_NOTIFICATIONS on first launch — grant both
2. **Settings**: Navigate to Settings, change bitrate to 8 Mbps, resolution to 1080p, min size to 100 MB — values persist after restarting app
3. **Scan**: Tap "Scan Videos" — video list appears sorted by size, each showing name/size/resolution/bitrate; videos below thresholds show "No compression needed"
4. **Start compression**: Tap "Start Compression" — system dialog asks for write permission → grant → foreground notification appears with progress → progress updates in both notification and main screen
5. **Notification stop**: Tap "Stop" in notification — compression stops, progress resets
6. **Resume**: Kill app during compression → reopen → dialog asks to resume → tap Continue → compression resumes from the next uncompressed video
7. **Completion**: After all videos compressed → completion notification shows count and saved space
8. **File verification**: Check compressed videos in gallery — file appears in same location with same apparent timestamps, playback works normally

- [ ] **Step 4: Fix any issues found during testing**

Address any build errors, runtime crashes, or UI issues discovered during manual testing.

- [ ] **Step 5: Final commit**

```bash
git add -A
git commit -m "chore: final adjustments after manual testing"
```

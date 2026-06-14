# Android 视频压缩工具 - 设计文档

## 概述

一款 Android 应用，扫描本机中体积较大的视频文件，根据用户设定的目标码率和分辨率进行批量压缩。压缩采用 H.265 (HEVC) 编码，使用 Android MediaCodec 硬件加速（通过 Media3 Transformer 封装）。应用具有前台服务保活机制和任务断点续传能力。

## 目标环境

- **编译/目标 SDK**: API 35
- **最低 SDK**: API 33
- **设备**: Android 13+ 设备
- **语言/框架**: Kotlin + Jetpack Compose

> 当前实现状态和后续开发约束以 `README.md` 和 `docs/development.md` 为准；本文保留为初始设计背景。

## 整体架构

```
┌─────────────────────────────────────────────────┐
│                   UI Layer                       │
│  ┌──────────┐  ┌──────────┐  ┌───────────────┐  │
│  │ 主页面    │  │ 设置页面  │  │ 压缩进度页面  │  │
│  │ 视频列表  │  │ 码率/分辨率│  │ 当前/总进度   │  │
│  │ 扫描+启动 │  │ 大小阈值  │  │ 通知栏同步   │  │
│  └──────────┘  └──────────┘  └───────────────┘  │
│                      │                           │
│              ViewModel Layer                     │
│  ┌──────────────────────────────────────────┐    │
│  │ MainViewModel (视频列表/任务状态/进度)    │    │
│  └──────────────────────────────────────────┘    │
│                      │                           │
├──────────────────────┼───────────────────────────┤
│              Service Layer                       │
│  ┌──────────────────────────────────────────┐    │
│  │ CompressionForegroundService              │    │
│  │  - 前台服务 (MEDIA_PROCESSING 类型)       │    │
│  │  - 通知栏进度更新                         │    │
│  │  - 管理压缩队列                           │    │
│  └──────────────────────────────────────────┘    │
│                      │                           │
│              Core Layer                          │
│  ┌──────────────┐  ┌────────────────────────┐    │
│  │ VideoScanner  │  │ VideoCompressor        │    │
│  │ ContentResolver│  │ Media3 Transformer    │    │
│  │ 扫描+筛选     │  │ H.265 编码            │    │
│  └──────────────┘  │ 码率/分辨率控制         │    │
│                     │ 文件替换+时间戳保持     │    │
│                     └────────────────────────┘    │
│                                                   │
│  ┌──────────────────────────────────────────┐    │
│  │ SettingsRepository (DataStore)            │    │
│  │  - 目标码率、分辨率、文件大小阈值          │    │
│  └──────────────────────────────────────────┘    │
└─────────────────────────────────────────────────┘
```

### 关键组件职责

1. **VideoScanner**: 通过 `ContentResolver` 查询 `MediaStore.Video`，获取所有视频的路径、大小、码率、分辨率，按用户设定的大小阈值筛选
2. **VideoCompressor**: 封装 Media3 Transformer 的调用，设置 H.265 编码、目标码率、目标分辨率，执行转码
3. **CompressionForegroundService**: 前台服务，驱动压缩队列，逐个处理视频，更新通知栏进度
4. **SettingsRepository**: 用 Jetpack DataStore 持久化用户设置（码率、分辨率、大小阈值）
5. **MainViewModel**: 连接 UI 与 Service/Repository，暴露视频列表、压缩状态、进度等 StateFlow

## 压缩核心流程

```
用户点击"开始压缩"
       │
       ▼
  VideoScanner 扫描视频
  (ContentResolver 查询 MediaStore)
       │
       ▼
  筛选: 文件大小 >= 用户阈值
       │
       ▼
  逐个检查码率/分辨率:
  原视频码率 <= 目标码率 AND 原视频分辨率 <= 目标分辨率?
       │                    │
      是 → 跳过             否 → 加入压缩队列
       │
       ▼
  将压缩队列持久化到 Room 数据库
       │
       ▼
  启动 CompressionForegroundService
       │
       ▼
  逐个处理队列中 status=PENDING 的任务:
       │
       ▼
  Media3 Transformer 转码
  (输入: 原视频 Uri, 输出: 临时文件, 编码: H.265)
       │
       ▼
  转码成功?
  ├─ 是 → 比较临时文件大小与原文件大小
  │       临时文件更小?
  │       ├─ 是 → 通过原 MediaStore Uri 写回覆盖
  │       │       尝试恢复修改时间
  │       │       标记任务 status=COMPLETED
  │       └─ 否 → 删除临时文件
  │               保留原文件
  │               标记任务 status=SKIPPED
  │
  └─ 否 → 删除临时文件
          标记任务 status=FAILED
          继续下一个
```

### 码率/分辨率判断逻辑

满足**任一条件**即需要压缩:
- 原视频码率 > 目标码率
- 原视频分辨率 (宽或高) > 目标分辨率

两个条件都不满足则跳过。

## 断点续传机制

使用 Room 数据库持久化任务队列:

```kotlin
@Entity(tableName = "compression_tasks")
data class CompressionTask(
    @PrimaryKey(autoGenerate = true) val id: Long,
    val videoUriString: String,
    val displayName: String,
    val originalPath: String,
    val originalSize: Long,
    val originalBitrate: Long,
    val originalWidth: Int,
    val originalHeight: Int,
    val originalDateAdded: Long,
    val originalDateModified: Long,
    val status: TaskStatus,       // PENDING, IN_PROGRESS, COMPLETED, FAILED, SKIPPED
    val errorMessage: String? = null,
    val compressedSize: Long? = null,
    val completedAt: Long? = null,
    val skippedReason: String? = null,
    val batchId: String
)
```

### 续传逻辑

1. App 启动时检查数据库是否有 `status = PENDING` 或 `status = IN_PROGRESS` 的任务
2. 如果有，提示用户"有未完成的压缩任务，是否继续？"
3. 用户确认后，从第一个未完成的任务继续处理
4. `IN_PROGRESS` 状态的任务（被中断的）: 删除可能残留的临时文件，重新开始该视频的压缩
5. 单个视频的转码不做断点续传（Media3 Transformer 不支持），只做任务级别的续传

## 前台服务保活

### 保活策略

1. **前台服务 + 持续通知**: `startForeground()` 搭配 `FOREGROUND_SERVICE_MEDIA_PROCESSING` 类型
2. **`START_STICKY`**: Service 被系统杀掉后自动重启
3. **重启后恢复**: Service 重启后从 Room 数据库查询未完成任务，自动继续处理
4. **WakeLock**: 持有 `PARTIAL_WAKE_LOCK` 防止 CPU 休眠

### 通知栏设计

```
┌─────────────────────────────────────┐
│ 视频压缩中                          │
│ 正在压缩: video_name.mp4            │
│ 当前进度: 45%  |  总进度: 3/12       │
│ ████████░░░░░░░░ 45%                │
│                          [停止]      │
└─────────────────────────────────────┘
```

- 实时更新当前视频名、单个进度百分比、总任务进度
- 提供"停止"按钮
- 压缩完成后显示完成通知（总共压缩了 X 个视频，节省了 Y MB 空间）

### Manifest 声明

```xml
<service
    android:name=".service.CompressionForegroundService"
    android:foregroundServiceType="mediaProcessing"
    android:exported="false" />
```

## UI 设计

### 主页面

- 顶部: 应用标题 + 设置入口
- 扫描按钮: 触发视频扫描
- 扫描结果: 符合条件的视频数量 + 预估可节省空间
- 视频列表: 显示文件名、大小、分辨率、码率，无需压缩的视频标灰
- 压缩控制: 开始/停止按钮 + 进度条
- 压缩中: 当前视频名 + 单个进度 + 总进度

### 设置页面

| 设置项 | 类型 | 默认值 | 说明 |
|-------|------|-------|------|
| 目标码率 | 数字输入 (Mbps) | 8 | 压缩后的目标视频码率 |
| 目标分辨率 | 单选 | 1080p | 可选 720p / 1080p / 原始分辨率 |
| 最小文件大小阈值 | 数字输入 (MB) | 100 | 只扫描大于此值的视频 |

## 项目结构

```
app/src/main/java/com/videocompress/
├── MainActivity.kt
├── ui/
│   ├── MainScreen.kt
│   ├── SettingsScreen.kt
│   ├── MainViewModel.kt
│   └── theme/
│       └── Theme.kt
├── service/
│   └── CompressionForegroundService.kt
├── core/
│   ├── VideoScanner.kt
│   ├── VideoCompressor.kt
│   └── FileHelper.kt
├── data/
│   ├── db/
│   │   ├── AppDatabase.kt
│   │   ├── CompressionTaskDao.kt
│   │   └── CompressionTask.kt
│   └── settings/
│       └── SettingsRepository.kt
└── util/
    └── NotificationHelper.kt
```

## 核心依赖

```kotlin
// Media3 Transformer
implementation("androidx.media3:media3-transformer:1.5.1")
implementation("androidx.media3:media3-effect:1.5.1")
implementation("androidx.media3:media3-common:1.5.1")

// Jetpack Compose
implementation(platform("androidx.compose:compose-bom:2024.12.01"))
implementation("androidx.compose.material3:material3")
implementation("androidx.compose.ui:ui")
implementation("androidx.activity:activity-compose:1.9.3")
implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
implementation("androidx.navigation:navigation-compose:2.8.5")

// Room
implementation("androidx.room:room-runtime:2.6.1")
implementation("androidx.room:room-ktx:2.6.1")
ksp("androidx.room:room-compiler:2.6.1")

// DataStore
implementation("androidx.datastore:datastore-preferences:1.1.1")

// Coroutines
implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
```

## 权限清单

```xml
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_MEDIA_PROCESSING" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
<uses-permission android:name="android.permission.WAKE_LOCK" />
```

Android 16 上 `READ_MEDIA_VIDEO` 和 `POST_NOTIFICATIONS` 需要运行时权限请求。写入/删除视频文件通过 `MediaStore` API 操作，需要 `createWriteRequest` 或 `createDeleteRequest` 获取用户授权。

## 文件替换策略

压缩完成后替换原视频时需要保持:
- **文件名**: 保持与原文件相同（扩展名可不同）
- **文件路径**: 保持在原目录
- **创建时间**: 恢复为原文件的创建时间
- **修改时间**: 恢复为原文件的修改时间

通过先记录原文件元数据，完成压缩后使用 `Files.setAttribute` 或 `File.setLastModified` 恢复时间戳，并通过 `MediaStore` 更新 `DATE_ADDED` 和 `DATE_MODIFIED` 字段。

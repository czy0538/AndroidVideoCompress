package com.videocompress

import android.app.Application
import com.videocompress.data.db.AppDatabase
import com.videocompress.data.settings.SettingsRepository

class VideoCompressApp : Application() {
    val database: AppDatabase by lazy { AppDatabase.getInstance(this) }
    val settingsRepository: SettingsRepository by lazy { SettingsRepository(this) }
}

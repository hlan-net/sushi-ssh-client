package net.hlan.sushi

import android.app.Application

class SushiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppThemeSettings(this).applyThemeMode()
    }
}

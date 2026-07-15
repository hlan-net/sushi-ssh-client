package net.hlan.sushi

import android.app.Application

class SushiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppThemeSettings(this).applyThemeMode()
        SshSettings(this).seedLocalHostIfMissing()

        TerminalSessionHolder.addListener(object : TerminalSessionHolder.ConnectionListener {
            override fun onConnected() {
                SshConnectionService.start(this@SushiApplication)
            }

            override fun onDisconnected() {
                SshConnectionService.stop(this@SushiApplication)
            }
        })
    }
}

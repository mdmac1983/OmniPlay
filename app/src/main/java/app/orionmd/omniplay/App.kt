package app.orionmd.omniplay

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { Vault.init(this) }
    }
}

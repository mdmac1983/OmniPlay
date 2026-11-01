package app.orionmd.omniplay

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        runCatching { Vault.init(this) }
        runCatching { app.orionmd.omniplay.ui.HifiFonts.init(this) }
        // sound settings first, so the hi-res output and Direct Volume Control start the way they were left
        runCatching { Sound.load(Store(this)) }
        HiResSink.active = Dsp.cfg.hiRes
        HiResSink.initMode(this)
        runCatching { Dvc.init(this) }
    }
}

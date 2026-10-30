package com.anybacking.app

import android.app.Application
import com.anybacking.app.data.DeviceKey
import com.anybacking.app.data.Lang
import com.anybacking.app.data.Prefs
import com.anybacking.app.net.Api
import com.anybacking.app.work.DoneWatcher

/** 全 App 共用的單例（只有一個 Activity，不需要 DI 框架）。 */
class App : Application() {
    lateinit var key: DeviceKey
    lateinit var api: Api
    lateinit var prefs: Prefs

    override fun onCreate() {
        super.onCreate()
        key = DeviceKey(this)
        prefs = Prefs(this)
        api = Api(key) { prefs.server }
        Lang.migrate(this, prefs)
        DoneWatcher.createChannel(this)
        if (prefs.pending.isNotEmpty()) DoneWatcher.schedule(this, delaySec = 5)
    }
}

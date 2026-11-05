package ug.kuuma.child

import android.app.Application

class KuumaChildApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KuumaRuntime.init(this)
    }
}

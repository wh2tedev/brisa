package app.brisa.browser

import android.app.Application

class BrisaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLock.attach(this)
    }
}

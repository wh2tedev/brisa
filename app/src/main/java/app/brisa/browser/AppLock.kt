package app.brisa.browser

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager

/**
 * App lock built on the phone's own authentication: fingerprint or face where available, otherwise (or as well)
 * the screen lock PIN, pattern or password. Brisa never sees or stores any of it.
 */
object AppLock {
    private val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL

    enum class Availability { READY, NEEDS_SCREEN_LOCK, UNAVAILABLE }

    private var unlocked = false
    private var started = 0
    private var leftAt = 0L

    /** True from the moment the lock screen is requested until it is closed. */
    var lockActive = false

    private fun prefs(context: Context) = PreferenceManager.getDefaultSharedPreferences(context)

    fun enabled(context: Context) = prefs(context).getBoolean(Prefs.APP_LOCK, false)

    private fun timeoutMs(context: Context) =
        (prefs(context).getString(Prefs.LOCK_TIMEOUT, "3")?.toLongOrNull() ?: 3L) * 1000L

    fun availability(context: Context): Availability = when (BiometricManager.from(context).canAuthenticate(AUTH)) {
        BiometricManager.BIOMETRIC_SUCCESS -> Availability.READY
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED,
        BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE -> Availability.NEEDS_SCREEN_LOCK
        else -> Availability.UNAVAILABLE
    }

    fun unlock() {
        unlocked = true
    }

    fun turnOff(context: Context) {
        prefs(context).edit().putBoolean(Prefs.APP_LOCK, false).apply()
    }

    fun authenticate(
        activity: FragmentActivity,
        title: String,
        onSuccess: () -> Unit,
        onFailure: (errorCode: Int) -> Unit = {},
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) = onFailure(errorCode)
        }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(AUTH)
            .build()
        BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback).authenticate(info)
    }

    /** Hides the app in the recents list when the lock or the "hide content" option is on. */
    fun applyPrivacyFlags(activity: Activity) {
        val hide = prefs(activity).getBoolean(Prefs.HIDE_PREVIEW, false)
        val lock = enabled(activity)
        val secure = hide || (lock && Build.VERSION.SDK_INT < 33)
        if (secure) {
            activity.window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        if (Build.VERSION.SDK_INT >= 33) activity.setRecentsScreenshotEnabled(!(hide || lock))
    }

    /** Watches every activity, so one check covers the main screen, settings and downloads. */
    fun attach(app: Application) {
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) {
                if (started++ == 0 && SystemClock.elapsedRealtime() - leftAt >= timeoutMs(activity)) unlocked = false
                if (activity is LockActivity) return
                applyPrivacyFlags(activity)
                if (enabled(activity) && !unlocked && !lockActive) {
                    lockActive = true
                    activity.startActivity(Intent(activity, LockActivity::class.java))
                }
            }

            override fun onActivityStopped(activity: Activity) {
                if (--started == 0) leftAt = SystemClock.elapsedRealtime()
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }
}

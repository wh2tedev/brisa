package app.brisa.browser

import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.appcompat.app.AppCompatActivity

/** Opaque screen shown on top of Brisa until the phone's own authentication succeeds. */
class LockActivity : AppCompatActivity() {
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.beforeCreate(this)
        super.onCreate(savedInstanceState)
        Appearance.apply(this)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContentView(R.layout.activity_lock)
        Appearance.finish(this)
        findViewById<View>(R.id.unlockButton).setOnClickListener { prompt() }
        onBackPressedDispatcher.addCallback(this) { finishAffinity() }
    }

    override fun onResume() {
        super.onResume()
        if (!prompting) prompt()
    }

    override fun onDestroy() {
        AppLock.lockActive = false
        super.onDestroy()
    }

    private fun prompt() {
        when (AppLock.availability(this)) {
            // Without a screen lock the phone itself is unprotected, so the lock cannot be enforced.
            AppLock.Availability.NEEDS_SCREEN_LOCK -> {
                AppLock.turnOff(this)
                AppLock.unlock()
                Toast.makeText(this, R.string.lock_disabled, Toast.LENGTH_LONG).show()
                finish()
                return
            }
            AppLock.Availability.UNAVAILABLE -> {
                Toast.makeText(this, R.string.lock_unavailable, Toast.LENGTH_SHORT).show()
                return
            }
            AppLock.Availability.READY -> Unit
        }
        prompting = true
        AppLock.authenticate(
            this,
            getString(R.string.lock_prompt_title),
            onSuccess = {
                prompting = false
                AppLock.unlock()
                finish()
            },
            onFailure = { prompting = false },
        )
    }
}

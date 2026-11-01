package app.brisa.browser

import android.os.Bundle
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity

/** Opaque screen shown on top of Brisa until the phone's own authentication succeeds. */
class LockActivity : FragmentActivity() {
    private var prompting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        setContent {
            BrisaTheme {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .systemBarsPadding()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box(
                        Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(colorResource(R.color.launcher_bg)),
                    ) {
                        Image(
                            painter = painterResource(R.drawable.ic_launcher_foreground),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = stringResource(R.string.lock_title),
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(24.dp))
                    Button(onClick = { prompt() }) { Text(stringResource(R.string.lock_unlock)) }
                }
            }
        }
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

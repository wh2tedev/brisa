package app.brisa.browser

import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import com.google.android.material.color.MaterialColors

/** Resolves a Brisa theme attribute (see attrs.xml) to a color for the current theme. */
fun Context.themeColor(attr: Int): Int = MaterialColors.getColor(this, attr, Color.MAGENTA)

object Appearance {
    private fun prefs(c: Context) = PreferenceManager.getDefaultSharedPreferences(c)

    fun isNight(c: Context) =
        c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun nightMode(p: SharedPreferences) = when (p.getString(Prefs.THEME, "system")) {
        "light" -> AppCompatDelegate.MODE_NIGHT_NO
        "dark" -> AppCompatDelegate.MODE_NIGHT_YES
        else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
    }

    fun signature(c: Context): String {
        val p = prefs(c)
        return listOf(p.getString(Prefs.THEME, "system"), p.getBoolean(Prefs.AMOLED, false), p.getString(Prefs.ACCENT, "blue"), isNight(c)).joinToString("|")
    }

    /** Call before super.onCreate. */
    fun beforeCreate(c: Context) {
        AppCompatDelegate.setDefaultNightMode(nightMode(prefs(c)))
    }

    /** Applies accent and pure-black overlays. Call after super.onCreate and before setContentView. */
    fun apply(activity: AppCompatActivity) {
        val p = prefs(activity)
        val accent = when (p.getString(Prefs.ACCENT, "blue")) {
            "green" -> R.style.Accent_Green
            "purple" -> R.style.Accent_Purple
            "orange" -> R.style.Accent_Orange
            "pink" -> R.style.Accent_Pink
            "teal" -> R.style.Accent_Teal
            "red" -> R.style.Accent_Red
            else -> R.style.Accent_Blue
        }
        activity.theme.applyStyle(accent, true)
        if (isNight(activity) && p.getBoolean(Prefs.AMOLED, false)) {
            activity.theme.applyStyle(R.style.ThemeOverlay_Brisa_Amoled, true)
        }
    }

    /** Call after setContentView. */
    fun finish(activity: AppCompatActivity) {
        val light = !isNight(activity)
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

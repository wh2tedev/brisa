package app.brisa.browser

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Colors for the whole app. On Android 12+ the default is Material You (colors taken from the wallpaper);
 * the fixed accents keep the neutral Brisa surfaces and only change the accent.
 */
object Appearance {
    data class Settings(val theme: String, val amoled: Boolean, val accent: String)

    fun defaultAccent(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Prefs.ACCENT_DYNAMIC else "blue"

    /** True when "Material You" colors can be offered on this device. */
    fun dynamicAvailable(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    fun read(p: SharedPreferences): Settings {
        var accent = p.getString(Prefs.ACCENT, null) ?: defaultAccent()
        if (accent == Prefs.ACCENT_DYNAMIC && !dynamicAvailable()) accent = "blue"
        return Settings(p.getString(Prefs.THEME, "system") ?: "system", p.getBoolean(Prefs.AMOLED, false), accent)
    }

    fun systemIsNight(c: Context): Boolean =
        c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES

    fun isDark(c: Context, s: Settings): Boolean = when (s.theme) {
        "light" -> false
        "dark" -> true
        else -> systemIsNight(c)
    }

    fun colorScheme(context: Context, s: Settings, dark: Boolean): ColorScheme {
        val amoled = dark && s.amoled
        if (s.accent == Prefs.ACCENT_DYNAMIC && dynamicAvailable()) {
            val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
            return if (amoled) base.pureBlack() else base
        }
        return fixedScheme(s.accent, dark, amoled)
    }

    private class Palette(
        val bg: Color,
        val toolbar: Color,
        val field: Color,
        val popup: Color,
        val divider: Color,
        val onBg: Color,
        val muted: Color,
        val warning: Color,
        val onAccent: Color,
    )

    private val lightPalette = Palette(
        Color(0xFFFFFFFF), Color(0xFFF1F3F9), Color(0xFFE2E7F1), Color(0xFFFFFFFF), Color(0xFFDDE1E8),
        Color(0xFF1B1C1F), Color(0xFF5F6368), Color(0xFFB3261E), Color(0xFFFFFFFF),
    )
    private val darkPalette = Palette(
        Color(0xFF131314), Color(0xFF1E1F22), Color(0xFF2D2F33), Color(0xFF26272B), Color(0xFF36383D),
        Color(0xFFE8EAED), Color(0xFF9AA0A6), Color(0xFFF28B82), Color(0xFF17181A),
    )
    private val amoledPalette = Palette(
        Color(0xFF000000), Color(0xFF0A0A0A), Color(0xFF1C1C1E), Color(0xFF121212), Color(0xFF2A2A2C),
        Color(0xFFE8EAED), Color(0xFF9AA0A6), Color(0xFFF28B82), Color(0xFF17181A),
    )

    private val lightAccents = mapOf(
        "blue" to Color(0xFF1A73E8), "green" to Color(0xFF188038), "purple" to Color(0xFF8430CE),
        "orange" to Color(0xFFC25E00), "pink" to Color(0xFFD01884), "teal" to Color(0xFF00796B),
        "red" to Color(0xFFD93025), "white" to Color(0xFF1B1C1F),
    )
    private val darkAccents = mapOf(
        "blue" to Color(0xFF8AB4F8), "green" to Color(0xFF81C995), "purple" to Color(0xFFC58AF9),
        "orange" to Color(0xFFFCAD70), "pink" to Color(0xFFFF8BCB), "teal" to Color(0xFF78D9EC),
        "red" to Color(0xFFF28B82), "white" to Color(0xFFFFFFFF),
    )

    private fun fixedScheme(accentId: String, isDark: Boolean, isAmoled: Boolean): ColorScheme {
        val p = when {
            isDark && isAmoled -> amoledPalette
            isDark -> darkPalette
            else -> lightPalette
        }
        val accents = if (isDark) darkAccents else lightAccents
        val accent = accents[accentId] ?: accents.getValue("blue")
        val container = lerp(p.bg, accent, if (isDark) 0.30f else 0.18f)
        val base = if (isDark) darkColorScheme() else lightColorScheme()
        return base.copy(
            primary = accent,
            onPrimary = p.onAccent,
            primaryContainer = container,
            onPrimaryContainer = p.onBg,
            inversePrimary = accent,
            secondary = accent,
            onSecondary = p.onAccent,
            secondaryContainer = p.field,
            onSecondaryContainer = p.onBg,
            tertiary = accent,
            onTertiary = p.onAccent,
            tertiaryContainer = container,
            onTertiaryContainer = p.onBg,
            background = p.bg,
            onBackground = p.onBg,
            surface = p.bg,
            onSurface = p.onBg,
            surfaceVariant = p.field,
            onSurfaceVariant = p.muted,
            surfaceTint = accent,
            inverseSurface = p.onBg,
            inverseOnSurface = p.bg,
            error = p.warning,
            onError = p.onAccent,
            errorContainer = lerp(p.bg, p.warning, 0.18f),
            onErrorContainer = p.onBg,
            outline = p.muted,
            outlineVariant = p.divider,
            scrim = Color.Black,
            surfaceBright = p.popup,
            surfaceDim = p.bg,
            surfaceContainerLowest = p.bg,
            surfaceContainerLow = p.bg,
            surfaceContainer = p.toolbar,
            surfaceContainerHigh = p.popup,
            surfaceContainerHighest = p.field,
        )
    }

    private fun ColorScheme.pureBlack(): ColorScheme = copy(
        background = amoledPalette.bg,
        surface = amoledPalette.bg,
        surfaceDim = amoledPalette.bg,
        surfaceContainerLowest = amoledPalette.bg,
        surfaceContainerLow = amoledPalette.bg,
        surfaceContainer = amoledPalette.toolbar,
        surfaceContainerHigh = amoledPalette.popup,
        surfaceContainerHighest = amoledPalette.field,
        surfaceBright = amoledPalette.field,
    )

    /** "#RRGGBB" for the HTML error page, which lives outside Compose. */
    fun hex(color: Color): String = String.format(java.util.Locale.ROOT, "#%06X", 0xFFFFFF and color.toArgb())
}

tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** Applies the user's theme, accent and pure-black choices, and updates live when they change in Settings. */
@Composable
fun BrisaTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.defaultPrefs() }
    var settings by remember { mutableStateOf(Appearance.read(prefs)) }
    DisposableEffect(prefs) {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { p, key ->
            if (key == null || key == Prefs.THEME || key == Prefs.AMOLED || key == Prefs.ACCENT) {
                settings = Appearance.read(p)
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    val systemDark = isSystemInDarkTheme()
    val dark = when (settings.theme) {
        "light" -> false
        "dark" -> true
        else -> systemDark
    }
    val scheme = remember(settings, dark) { Appearance.colorScheme(context, settings, dark) }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = context.findActivity()?.window ?: return@SideEffect
            window.setBackgroundDrawable(ColorDrawable(scheme.surfaceContainer.toArgb()))
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = scheme) {
        // Without a Surface, icons and text default to black, which disappears on dark backgrounds.
        CompositionLocalProvider(LocalContentColor provides scheme.onSurface, content = content)
    }
}

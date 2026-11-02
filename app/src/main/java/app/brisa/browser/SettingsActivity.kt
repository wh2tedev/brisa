package app.brisa.browser

import android.app.ActivityOptions
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.compose.foundation.selection.toggleable
import androidx.fragment.app.FragmentActivity

private enum class Page(@StringRes val title: Int) {
    HOME(R.string.settings_title),
    GENERAL(R.string.cat_general),
    APPEARANCE(R.string.cat_appearance),
    CONTENT(R.string.cat_content),
    PRIVACY(R.string.cat_privacy),
    DOWNLOADS(R.string.cat_downloads),
    ABOUT(R.string.cat_about),
}

class SettingsActivity : FragmentActivity() {

    private val folderPicker = FolderPicker(this)
    private lateinit var prefs: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        prefs = defaultPrefs()
        // The exceptions list is read from disk once per process; make sure it is loaded before it can be edited.
        SiteExceptions.init(this)
        setContent {
            BrisaTheme {
                SettingsScreen()
                folderPicker.Dialog()
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.enter_from_left, R.anim.exit_to_right)
    }

    private fun openDownloads() {
        val options = ActivityOptions.makeCustomAnimation(this, R.anim.enter_from_right, R.anim.exit_to_left)
        startActivity(Intent(this, DownloadsActivity::class.java), options.toBundle())
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun SettingsScreen() {
        var page by rememberSaveable { mutableStateOf(Page.HOME) }
        val scheme = MaterialTheme.colorScheme
        BackHandler(enabled = page != Page.HOME) { page = Page.HOME }
        Scaffold(
            containerColor = scheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(page.title)) },
                    navigationIcon = {
                        IconButton(onClick = { if (page != Page.HOME) page = Page.HOME else finish() }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surfaceContainer),
                )
            },
        ) { padding ->
            AnimatedContent(
                targetState = page,
                modifier = Modifier.fillMaxSize().padding(padding),
                label = "settingsPage",
                transitionSpec = {
                    val forward = targetState != Page.HOME
                    val slide = tween<androidx.compose.ui.unit.IntOffset>(300, easing = Motion.decelerate)
                    (slideInHorizontally(slide) { if (forward) it / 10 else -it / 10 } + fadeIn(tween(300))) togetherWith
                        (slideOutHorizontally(slide) { if (forward) -it / 10 else it / 10 } + fadeOut(tween(150)))
                },
            ) { target ->
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (target) {
                        Page.HOME -> HomePage(onOpen = { page = it })
                        Page.GENERAL -> GeneralPage()
                        Page.APPEARANCE -> AppearancePage()
                        Page.CONTENT -> ContentPage()
                        Page.PRIVACY -> PrivacyPage()
                        Page.DOWNLOADS -> DownloadsPage()
                        Page.ABOUT -> AboutPage()
                    }
                }
            }
        }
    }

    // region pages

    @Composable
    private fun HomePage(onOpen: (Page) -> Unit) {
        PrefCategory(R.string.cat_browser) {
            PrefRow(
                title = stringResource(R.string.downloads),
                summary = stringResource(R.string.downloads_sum),
                iconRes = R.drawable.ic_download,
                onClick = ::openDownloads,
            )
            PrefRow(
                title = stringResource(R.string.cat_general),
                summary = stringResource(R.string.cat_general_sum),
                iconRes = R.drawable.ic_tune,
                onClick = { onOpen(Page.GENERAL) },
            )
            PrefRow(
                title = stringResource(R.string.cat_appearance),
                summary = stringResource(R.string.cat_appearance_sum),
                iconRes = R.drawable.ic_palette,
                onClick = { onOpen(Page.APPEARANCE) },
            )
        }
        PrefCategory(R.string.cat_safety) {
            PrefRow(
                title = stringResource(R.string.cat_privacy),
                summary = stringResource(R.string.cat_privacy_sum),
                iconRes = R.drawable.ic_shield,
                onClick = { onOpen(Page.PRIVACY) },
            )
            PrefRow(
                title = stringResource(R.string.cat_content),
                summary = stringResource(R.string.cat_content_sum),
                iconRes = R.drawable.ic_web,
                onClick = { onOpen(Page.CONTENT) },
            )
            PrefRow(
                title = stringResource(R.string.cat_downloads),
                summary = stringResource(R.string.cat_downloads_sum),
                iconRes = R.drawable.ic_folder,
                onClick = { onOpen(Page.DOWNLOADS) },
            )
        }
        PrefCategory(R.string.cat_about) {
            PrefRow(
                title = stringResource(R.string.cat_about),
                summary = stringResource(R.string.cat_about_sum),
                iconRes = R.drawable.ic_info,
                onClick = { onOpen(Page.ABOUT) },
            )
        }
    }

    @Composable
    private fun GeneralPage() {
        PrefCategory(R.string.cat_search) {
            ListPreference(
                prefs, Prefs.SEARCH_ENGINE, "google", R.string.pref_engine,
                stringArrayResource(R.array.engine_names).toList(),
                stringArrayResource(R.array.engine_values).toList(),
            )
            TextPreference(prefs, Prefs.HOMEPAGE, R.string.pref_homepage)
        }
        PrefCategory(R.string.cat_startup) {
            SwitchPreference(prefs, Prefs.RESTORE_TABS, true, R.string.pref_restore_tabs, R.string.pref_restore_tabs_sum)
            SwitchPreference(prefs, Prefs.PULL_REFRESH, true, R.string.pref_pull, R.string.pref_pull_sum)
        }
    }

    @Composable
    private fun AppearancePage() {
        PrefCategory(R.string.cat_theme) {
            ListPreference(
                prefs, Prefs.THEME, "system", R.string.pref_theme,
                stringArrayResource(R.array.theme_names).toList(),
                stringArrayResource(R.array.theme_values).toList(),
            )
            SwitchPreference(prefs, Prefs.AMOLED, false, R.string.pref_amoled, R.string.pref_amoled_sum)
            // "Material You" needs Android 12 or later, so it is only offered there.
            val names = stringArrayResource(R.array.accent_names).toList()
            val values = stringArrayResource(R.array.accent_values).toList()
            val keep = values.indices.filter { Appearance.dynamicAvailable() || values[it] != Prefs.ACCENT_DYNAMIC }
            ListPreference(
                prefs, Prefs.ACCENT, Appearance.defaultAccent(), R.string.pref_accent,
                keep.map { names[it] }, keep.map { values[it] },
            )
        }
        PrefCategory(R.string.cat_layout) {
            ListPreference(
                prefs, Prefs.TOOLBAR, "top", R.string.pref_toolbar,
                stringArrayResource(R.array.pos_names).toList(),
                stringArrayResource(R.array.pos_values).toList(),
            )
            ListPreference(
                prefs, Prefs.ONE_HAND, "off", R.string.pref_one_hand,
                stringArrayResource(R.array.hand_names).toList(),
                stringArrayResource(R.array.hand_values).toList(),
            )
        }
        PrefCategory(R.string.cat_pages) {
            SwitchPreference(prefs, Prefs.WEB_DARK, false, R.string.pref_web_dark, R.string.pref_web_dark_sum)
        }
    }

    @Composable
    private fun ContentPage() {
        PrefCategory(R.string.cat_content) {
            SwitchPreference(prefs, Prefs.JAVASCRIPT, true, R.string.pref_js, R.string.pref_js_sum)
            SwitchPreference(prefs, Prefs.AUTOPLAY, false, R.string.pref_autoplay, R.string.pref_autoplay_sum)
            SwitchPreference(prefs, Prefs.DESKTOP, false, R.string.desktop_site, R.string.pref_desktop_sum)
        }
        PrefCategory(R.string.cat_access) {
            SliderPreference(
                prefs, Prefs.TEXT_SIZE, default = 100, min = 50, max = 200, step = 10,
                title = R.string.pref_text_size, summary = R.string.pref_text_size_sum,
            )
        }
    }

    @Composable
    private fun PrivacyPage() {
        val context = LocalContext.current
        var lockOn by remember { mutableStateOf(prefs.getBoolean(Prefs.APP_LOCK, false)) }
        var exceptionCount by remember { mutableStateOf(SiteExceptions.all().size) }
        var showLockSetup by remember { mutableStateOf(false) }
        var showExceptions by remember { mutableStateOf(false) }
        var showClear by remember { mutableStateOf(false) }

        PrefCategory(R.string.cat_access) {
            SwitchRow(
                title = stringResource(R.string.pref_app_lock),
                summary = stringResource(R.string.pref_app_lock_sum),
                checked = lockOn,
            ) { enable ->
                changeAppLock(enable, onApply = { lockOn = it }, onNeedsSetup = { showLockSetup = true })
            }
            ListPreference(
                prefs, Prefs.LOCK_TIMEOUT, "3", R.string.pref_lock_timeout,
                stringArrayResource(R.array.lock_timeout_names).toList(),
                stringArrayResource(R.array.lock_timeout_values).toList(),
                enabled = lockOn,
            )
        }
        PrefCategory(R.string.cat_protection) {
            ListPreference(
                prefs, Prefs.TRACKER_LEVEL, "standard", R.string.pref_tracker_level,
                stringArrayResource(R.array.tracker_level_names).toList(),
                stringArrayResource(R.array.tracker_level_values).toList(),
            )
            PrefRow(
                title = stringResource(R.string.pref_site_exceptions),
                summary = stringResource(R.string.site_exceptions_count, exceptionCount),
                onClick = {
                    if (SiteExceptions.all().isEmpty()) {
                        Toast.makeText(context, R.string.site_exceptions_none, Toast.LENGTH_LONG).show()
                    } else {
                        showExceptions = true
                    }
                },
            )
            SwitchPreference(prefs, Prefs.HTTPS_FIRST, true, R.string.pref_https_first, R.string.pref_https_first_sum)
            SwitchPreference(prefs, Prefs.BLOCK_THIRD_PARTY, true, R.string.pref_third_party, R.string.pref_third_party_sum)
            SwitchPreference(prefs, Prefs.SEND_DNT, true, R.string.pref_dnt, R.string.pref_dnt_sum)
        }
        PrefCategory(R.string.cat_data) {
            SwitchPreference(prefs, Prefs.SAVE_HISTORY, true, R.string.pref_save_history, R.string.pref_save_history_sum)
            SwitchPreference(prefs, Prefs.CLEAR_ON_EXIT, false, R.string.pref_clear_exit, R.string.pref_clear_exit_sum)
            SwitchPreference(prefs, Prefs.HIDE_PREVIEW, false, R.string.pref_hide_preview, R.string.pref_hide_preview_sum)
            PrefRow(
                title = stringResource(R.string.pref_clear_now),
                summary = stringResource(R.string.pref_clear_now_sum),
                onClick = { showClear = true },
            )
        }

        if (showLockSetup) LockSetupDialog { showLockSetup = false }
        if (showExceptions) {
            ExceptionsDialog(
                hosts = SiteExceptions.all(),
                onDismiss = { showExceptions = false },
                onSave = { kept ->
                    SiteExceptions.replace(context, kept)
                    exceptionCount = SiteExceptions.all().size
                    showExceptions = false
                },
            )
        }
        if (showClear) ClearDataDialog { showClear = false }
    }

    @Composable
    private fun DownloadsPage() {
        val activity = this@SettingsActivity
        var location by remember { mutableStateOf(Storage.label(activity)) }
        var showSites by remember { mutableStateOf(false) }
        var siteCount by remember { mutableStateOf(SiteFolders.all(activity).size) }
        PrefCategory(R.string.cat_downloads) {
            PrefRow(
                title = stringResource(R.string.pref_dl_location),
                summary = location,
                onClick = { folderPicker.show { location = Storage.label(activity) } },
            )
            PrefRow(
                title = stringResource(R.string.pref_site_folders),
                summary = stringResource(R.string.pref_site_folders_sum) + " · " +
                    stringResource(R.string.site_folders_count, siteCount),
                onClick = { showSites = true },
            )
            SwitchPreference(prefs, Prefs.CONFIRM_DOWNLOADS, true, R.string.pref_dl_confirm, R.string.pref_dl_confirm_sum)
            PrefRow(
                title = stringResource(R.string.pref_dl_open),
                onClick = ::openDownloads,
            )
        }
        if (showSites) {
            SiteFoldersDialog(
                onDismiss = {
                    showSites = false
                    siteCount = SiteFolders.all(activity).size
                },
            )
        }
    }

    @Composable
    private fun AboutPage() {
        val context = LocalContext.current
        val version = remember {
            runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        }
        var text by remember { mutableStateOf<Pair<Int, String>?>(null) }
        var confirmReset by remember { mutableStateOf(false) }

        fun asset(name: String) = runCatching {
            context.assets.open(name).bufferedReader().use { it.readText() }
        }.getOrDefault("")

        PrefRow(title = stringResource(R.string.pref_version), summary = "Brisa ${version.orEmpty()}")
        PrefRow(
            title = stringResource(R.string.pref_privacy_policy),
            summary = stringResource(R.string.pref_privacy_policy_sum),
            onClick = { text = R.string.pref_privacy_policy to asset("privacy.txt") },
        )
        PrefRow(
            title = stringResource(R.string.pref_licenses),
            summary = stringResource(R.string.pref_licenses_sum),
            onClick = { text = R.string.pref_licenses to asset("licenses.txt") },
        )
        PrefRow(
            title = stringResource(R.string.pref_reset),
            summary = stringResource(R.string.pref_reset_sum),
            onClick = { confirmReset = true },
        )

        text?.let { (title, body) ->
            BrisaDialog(
                onDismissRequest = { text = null },
                title = { Text(stringResource(title)) },
                text = {
                    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                        Text(body, style = MaterialTheme.typography.bodyMedium)
                    }
                },
                confirmButton = { TextButton(onClick = { text = null }) { Text(stringResource(android.R.string.ok)) } },
            )
        }
        if (confirmReset) {
            BrisaDialog(
                onDismissRequest = { confirmReset = false },
                title = { Text(stringResource(R.string.reset_title)) },
                text = { Text(stringResource(R.string.pref_reset_sum)) },
                confirmButton = {
                    TextButton(onClick = {
                        confirmReset = false
                        prefs.edit { clear() }
                        recreate()
                    }) { Text(stringResource(R.string.reset)) }
                },
                dismissButton = {
                    TextButton(onClick = { confirmReset = false }) { Text(stringResource(android.R.string.cancel)) }
                },
            )
        }
    }

    // endregion

    // region dialogs and actions

    /** Turning the lock on or off needs the phone's own authentication, so nobody else can change it. */
    private fun changeAppLock(enable: Boolean, onApply: (Boolean) -> Unit, onNeedsSetup: () -> Unit) {
        val availability = AppLock.availability(this)
        when {
            enable && availability == AppLock.Availability.NEEDS_SCREEN_LOCK -> onNeedsSetup()
            availability == AppLock.Availability.UNAVAILABLE ->
                Toast.makeText(this, R.string.lock_unavailable, Toast.LENGTH_SHORT).show()
            !enable && availability == AppLock.Availability.NEEDS_SCREEN_LOCK -> {
                prefs.edit { putBoolean(Prefs.APP_LOCK, false) }
                onApply(false)
            }
            else -> AppLock.authenticate(this, getString(R.string.lock_confirm_title), onSuccess = {
                prefs.edit { putBoolean(Prefs.APP_LOCK, enable) }
                onApply(enable)
                AppLock.unlock()
            })
        }
    }

    @Composable
    private fun LockSetupDialog(onDismiss: () -> Unit) {
        BrisaDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.lock_setup_title)) },
            text = { Text(stringResource(R.string.lock_setup_message)) },
            confirmButton = {
                TextButton(onClick = {
                    onDismiss()
                    try {
                        startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS))
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this@SettingsActivity, R.string.lock_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }) { Text(stringResource(R.string.lock_open_settings)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    @Composable
    private fun ExceptionsDialog(hosts: List<String>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
        val keep = remember { mutableStateOf(hosts.toSet()) }
        BrisaDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pref_site_exceptions)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    hosts.forEach { host ->
                        val on = host in keep.value
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = on,
                                    role = Role.Checkbox,
                                    onValueChange = { keep.value = if (it) keep.value + host else keep.value - host },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = on, onCheckedChange = null)
                            Text(host, Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onSave(hosts.filter { it in keep.value }) }) {
                    Text(stringResource(R.string.exceptions_save))
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    @Composable
    private fun SiteFoldersDialog(onDismiss: () -> Unit) {
        val activity = this@SettingsActivity
        var sites by remember { mutableStateOf(SiteFolders.all(activity)) }
        var adding by remember { mutableStateOf(false) }
        var draft by remember { mutableStateOf("") }
        if (adding) {
            BrisaDialog(
                onDismissRequest = { adding = false },
                title = { Text(stringResource(R.string.site_folders_add)) },
                text = {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        singleLine = true,
                        placeholder = { Text(stringResource(R.string.site_folders_hint)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = SiteFolders.key(siteOf(draft)) != null,
                        onClick = {
                            val host = siteOf(draft)
                            adding = false
                            draft = ""
                            folderPicker.show(host) { sites = SiteFolders.all(activity) }
                        },
                    ) { Text(stringResource(R.string.site_folders_next)) }
                },
                dismissButton = { TextButton(onClick = { adding = false }) { Text(stringResource(android.R.string.cancel)) } },
            )
        }
        BrisaDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.pref_site_folders)) },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (sites.isEmpty()) Text(stringResource(R.string.site_folders_empty))
                    sites.forEach { (host, tree) ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(host)
                                Text(
                                    Storage.labelOf(activity, tree),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = {
                                SiteFolders.set(activity, host, null)
                                sites = SiteFolders.all(activity)
                            }) {
                                Icon(painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.site_folders_remove))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
            dismissButton = { TextButton(onClick = { adding = true }) { Text(stringResource(R.string.site_folders_add)) } },
        )
    }

    /** Accepts "example.com", "www.example.com" or a full address. */
    private fun siteOf(input: String): String? {
        val text = input.trim()
        if (text.isEmpty()) return null
        return Uri.parse(if ("://" in text) text else "https://$text").host
    }

    @Composable
    private fun ClearDataDialog(onDismiss: () -> Unit) {
        val labels = listOf(
            R.string.clear_history_item, R.string.clear_cookies_item,
            R.string.clear_cache_item, R.string.clear_downloads_item,
        )
        var checked by remember { mutableStateOf(listOf(true, true, true, false)) }
        BrisaDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.clear_what)) },
            text = {
                Column {
                    labels.forEachIndexed { i, label ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(
                                    value = checked[i],
                                    role = Role.Checkbox,
                                    onValueChange = { on -> checked = checked.mapIndexed { j, v -> if (j == i) on else v } },
                                )
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = checked[i], onCheckedChange = null)
                            Text(stringResource(label), Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val items = buildSet {
                        if (checked[0]) add(Prefs.CLEAR_HISTORY)
                        if (checked[1]) add(Prefs.CLEAR_COOKIES)
                        if (checked[2]) add(Prefs.CLEAR_CACHE)
                    }
                    if (items.isNotEmpty()) prefs.edit { putStringSet(Prefs.PENDING_CLEAR, items) }
                    if (checked[3]) Downloads.clearList(this@SettingsActivity)
                    if (items.isNotEmpty() || checked[3]) Toast.makeText(this@SettingsActivity, R.string.data_cleared, Toast.LENGTH_SHORT).show()
                    onDismiss()
                }) { Text(stringResource(R.string.clear)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
        )
    }

    // endregion
}

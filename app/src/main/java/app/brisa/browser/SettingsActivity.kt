package app.brisa.browser

import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.widget.Toast
import androidx.annotation.StringRes
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.fragment.app.Fragment
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceManager
import androidx.preference.SeekBarPreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SettingsActivity : AppCompatActivity(), PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    internal val folderPicker = FolderPicker(this)
    private lateinit var prefs: SharedPreferences
    private val appearanceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.THEME || key == Prefs.AMOLED || key == Prefs.ACCENT) {
            Appearance.beforeCreate(this)
            recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.beforeCreate(this)
        super.onCreate(savedInstanceState)
        Appearance.apply(this)
        setContentView(R.layout.activity_settings)
        Appearance.finish(this)
        prefs = PreferenceManager.getDefaultSharedPreferences(this)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.settingsRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        if (savedInstanceState == null) {
            supportFragmentManager.beginTransaction().replace(R.id.container, HomeFragment()).commit()
        }
    }

    override fun onStart() {
        super.onStart()
        prefs.registerOnSharedPreferenceChangeListener(appearanceListener)
    }

    override fun onStop() {
        prefs.unregisterOnSharedPreferenceChangeListener(appearanceListener)
        super.onStop()
    }

    override fun onPreferenceStartFragment(caller: PreferenceFragmentCompat, pref: Preference): Boolean {
        val fragment = supportFragmentManager.fragmentFactory.instantiate(classLoader, pref.fragment ?: return false)
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(R.anim.enter_from_right, R.anim.exit_to_left, R.anim.enter_from_left, R.anim.exit_to_right)
            .replace(R.id.container, fragment)
            .addToBackStack(null)
            .commit()
        return true
    }

    override fun onSupportNavigateUp(): Boolean {
        if (!supportFragmentManager.popBackStackImmediate()) finish()
        return true
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.enter_from_left, R.anim.exit_to_right)
    }

    abstract class Page(private val xml: Int, @StringRes private val title: Int) : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(xml, rootKey)
            setup()
        }

        override fun onStart() {
            super.onStart()
            requireActivity().setTitle(title)
        }

        open fun setup() {}

        protected fun <T : Preference> pref(key: String): T? = findPreference<T>(key)

        protected fun openDownloads() {
            val options = android.app.ActivityOptions.makeCustomAnimation(requireContext(), R.anim.enter_from_right, R.anim.exit_to_left)
            startActivity(Intent(requireContext(), DownloadsActivity::class.java), options.toBundle())
        }
    }

    class HomeFragment : Page(R.xml.preferences, R.string.settings_title) {
        override fun setup() {
            pref<Preference>("open_downloads")?.setOnPreferenceClickListener {
                openDownloads()
                true
            }
        }
    }

    class GeneralFragment : Page(R.xml.prefs_general, R.string.cat_general)

    class AppearanceFragment : Page(R.xml.prefs_appearance, R.string.cat_appearance)

    class ContentFragment : Page(R.xml.prefs_content, R.string.cat_content) {
        override fun setup() {
            pref<SeekBarPreference>(Prefs.TEXT_SIZE)?.updatesContinuously = false
        }
    }

    class PrivacyFragment : Page(R.xml.prefs_privacy, R.string.cat_privacy) {
        override fun setup() {
            pref<Preference>(Prefs.CLEAR_NOW)?.setOnPreferenceClickListener {
                showClearDialog()
                true
            }
        }

        private fun showClearDialog() {
            val context = requireContext()
            val labels = arrayOf(
                getString(R.string.clear_history_item), getString(R.string.clear_cookies_item),
                getString(R.string.clear_cache_item), getString(R.string.clear_downloads_item),
            )
            val checked = booleanArrayOf(true, true, true, false)
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.clear_what)
                .setMultiChoiceItems(labels, checked) { _, which, on -> checked[which] = on }
                .setPositiveButton(R.string.clear) { _, _ ->
                    val items = buildSet {
                        if (checked[0]) add(Prefs.CLEAR_HISTORY)
                        if (checked[1]) add(Prefs.CLEAR_COOKIES)
                        if (checked[2]) add(Prefs.CLEAR_CACHE)
                    }
                    if (items.isNotEmpty()) {
                        PreferenceManager.getDefaultSharedPreferences(context).edit { putStringSet(Prefs.PENDING_CLEAR, items) }
                    }
                    if (checked[3]) Downloads.clearList(context)
                    if (items.isNotEmpty() || checked[3]) Toast.makeText(context, R.string.data_cleared, Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    class DownloadsFragment : Page(R.xml.prefs_downloads, R.string.cat_downloads) {
        override fun setup() {
            pref<Preference>("download_location")?.setOnPreferenceClickListener {
                (requireActivity() as SettingsActivity).folderPicker.show { renderLocation() }
                true
            }
            pref<Preference>("open_downloads_list")?.setOnPreferenceClickListener {
                openDownloads()
                true
            }
            renderLocation()
        }

        private fun renderLocation() {
            if (!isAdded) return
            pref<Preference>("download_location")?.summary = Storage.label(requireContext())
        }
    }

    class AboutFragment : Page(R.xml.prefs_about, R.string.cat_about) {
        override fun setup() {
            val context = requireContext()
            val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
            pref<Preference>("version")?.summary = "Brisa ${version.orEmpty()}"
            pref<Preference>("licenses")?.setOnPreferenceClickListener {
                val text = runCatching {
                    context.assets.open("licenses.txt").bufferedReader().use { it.readText() }
                }.getOrDefault("")
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.pref_licenses)
                    .setMessage(text)
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
                true
            }
            pref<Preference>("reset_settings")?.setOnPreferenceClickListener {
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.reset_title)
                    .setMessage(R.string.pref_reset_sum)
                    .setPositiveButton(R.string.reset) { _, _ ->
                        PreferenceManager.getDefaultSharedPreferences(context).edit {
                            clear()
                        }
                        requireActivity().recreate()
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
                true
            }
        }
    }
}

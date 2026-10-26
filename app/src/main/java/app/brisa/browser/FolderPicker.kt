package app.brisa.browser

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/** Lets the user choose the folder downloads are saved to. Create it as an activity property. */
class FolderPicker(private val activity: ComponentActivity) {
    private var done: (() -> Unit)? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) setTree(uri)
        finish()
    }

    /** [onDone] runs after the choice is made, or if the person backs out. */
    fun show(onDone: (() -> Unit)? = null) {
        done = onDone
        val custom = Storage.treeUri(activity) != null
        val items = arrayOf(activity.getString(R.string.default_folder), activity.getString(R.string.choose_folder))
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.folder_title)
            .setSingleChoiceItems(items, if (custom) 1 else 0) { dialog, which ->
                dialog.dismiss()
                if (which == 0) {
                    setTree(null)
                    finish()
                } else {
                    launcher.launch(null)
                }
            }
            .setOnCancelListener { finish() }
            .show()
    }

    private fun finish() {
        val callback = done
        done = null
        callback?.invoke()
    }

    private fun setTree(uri: Uri?) {
        val resolver = activity.contentResolver
        val prefs = PreferenceManager.getDefaultSharedPreferences(activity)
        val old = prefs.getString(Prefs.DOWNLOAD_TREE, null)?.let(Uri::parse)
        if (uri != null) {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        if (old != null && old != uri) {
            runCatching {
                resolver.releasePersistableUriPermission(old, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            }
        }
        prefs.edit { if (uri == null) remove(Prefs.DOWNLOAD_TREE) else putString(Prefs.DOWNLOAD_TREE, uri.toString()) }
    }
}

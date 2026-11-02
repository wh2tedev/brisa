package app.brisa.browser

import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.edit

/**
 * Lets the user choose the folder downloads are saved to. Create it as an activity property and call
 * [Dialog] once from the activity's content.
 */
class FolderPicker(private val activity: ComponentActivity) {
    private var done: (() -> Unit)? = null
    private var visible by mutableStateOf(false)
    private var custom by mutableStateOf(false)
    private var site by mutableStateOf<String?>(null)

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) setTree(uri)
        finish()
    }

    /**
     * [host] limits the choice to downloads from that site; without it the general folder is changed.
     * [onDone] runs after the choice is made, or if the person backs out.
     */
    fun show(host: String? = null, onDone: (() -> Unit)? = null) {
        done = onDone
        site = SiteFolders.key(host)
        val current = site
        custom = if (current == null) Storage.generalTree(activity) != null else SiteFolders.get(activity, current) != null
        visible = true
    }

    @Composable
    fun Dialog() {
        if (!visible) return
        val host = site
        val labels = if (host == null) {
            listOf(stringResource(R.string.default_folder), stringResource(R.string.choose_folder))
        } else {
            listOf(stringResource(R.string.site_folder_general), stringResource(R.string.site_folder_choose, host))
        }
        BrisaDialog(
            onDismissRequest = {
                visible = false
                finish()
            },
            title = {
                Text(
                    if (host == null) stringResource(R.string.folder_title)
                    else stringResource(R.string.site_folder_title, host),
                )
            },
            text = {
                Column(Modifier.selectableGroup()) {
                    labels.forEachIndexed { index, label ->
                        val selected = (index == 1) == custom
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = selected,
                                    role = Role.RadioButton,
                                    onClick = {
                                        visible = false
                                        if (index == 0) {
                                            setTree(null)
                                            finish()
                                        } else {
                                            launcher.launch(null)
                                        }
                                    },
                                )
                                .padding(vertical = 8.dp),
                        ) {
                            RadioButton(selected = selected, onClick = null)
                            Text(label, Modifier.padding(start = 16.dp))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = {
                    visible = false
                    finish()
                }) { Text(stringResource(android.R.string.cancel)) }
            },
        )
    }

    private fun finish() {
        val callback = done
        done = null
        callback?.invoke()
    }

    private fun setTree(uri: Uri?) {
        val resolver = activity.contentResolver
        if (uri != null) {
            resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
        val host = site
        if (host != null) {
            SiteFolders.set(activity, host, uri)
            return
        }
        val prefs = activity.defaultPrefs()
        val old = prefs.getString(Prefs.DOWNLOAD_TREE, null)?.let(Uri::parse)
        prefs.edit { if (uri == null) remove(Prefs.DOWNLOAD_TREE) else putString(Prefs.DOWNLOAD_TREE, uri.toString()) }
        if (old != null && old != uri) SiteFolders.releaseIfUnused(activity, old)
    }
}

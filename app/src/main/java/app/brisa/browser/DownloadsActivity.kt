package app.brisa.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.format.DateUtils
import android.text.format.Formatter
import android.webkit.WebSettings
import androidx.activity.compose.setContent
import androidx.annotation.StringRes
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class DownloadsActivity : FragmentActivity() {

    private val syncing = AtomicBoolean(false)
    private val folderPicker = FolderPicker(this)
    private var items by mutableStateOf<List<Downloads.Item>>(emptyList())
    private var folder by mutableStateOf("")
    private val snackbar = SnackbarHostState()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        items = Downloads.all(this)
        folder = Storage.label(this)
        setContent {
            BrisaTheme {
                DownloadsScreen()
                folderPicker.Dialog()
            }
        }
        // Poll the system downloader only while the screen is visible.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    refresh()
                    delay(700)
                }
            }
        }
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.enter_from_left, R.anim.exit_to_right)
    }

    private suspend fun refresh() {
        if (!syncing.compareAndSet(false, true)) return
        try {
            val app = applicationContext
            withContext(Dispatchers.IO) { runCatching { Downloads.sync(app) } }
            if (!isDestroyed) {
                val latest = Downloads.all(this)
                if (latest != items) items = latest
            }
        } finally {
            syncing.set(false)
        }
    }

    private fun render() {
        items = Downloads.all(this)
    }

    private fun message(@StringRes res: Int) {
        lifecycleScope.launch {
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(getString(res))
        }
    }

    private fun open(item: Downloads.Item) {
        val uri = item.uri
        if (item.status != Downloads.DONE || uri == null) return
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), item.mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            message(R.string.cant_open)
        } catch (e: SecurityException) {
            message(R.string.cant_open)
        }
    }

    private fun share(item: Downloads.Item) {
        val uri = item.uri ?: return
        val send = Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, null))
    }

    private fun cancel(item: Downloads.Item) {
        val app = applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                runCatching { app.getSystemService(DownloadManager::class.java).remove(item.dmId) }
                Downloads.remove(app, item.id)
            }
            if (!isDestroyed) render()
        }
    }

    /** The system downloader cannot continue a failed download, so this starts it again. */
    private fun retry(item: Downloads.Item) {
        val app = applicationContext
        val tree = item.tree?.let(Uri::parse)?.takeIf { Storage.hasWriteAccess(app, it) }
        Downloads.remove(app, item.id)
        val started = DownloadStarter.enqueue(app, item.url, WebSettings.getDefaultUserAgent(app), item.mime, item.name, tree)
        render()
        message(if (started) R.string.downloading else R.string.download_failed)
    }

    private fun delete(item: Downloads.Item) {
        val app = applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                Storage.deleteFile(app, item)
                Downloads.remove(app, item.id)
            }
            if (!isDestroyed) render()
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun DownloadsScreen() {
        val scheme = MaterialTheme.colorScheme
        var menuOpen by remember { mutableStateOf(false) }
        Scaffold(
            containerColor = scheme.surface,
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.downloads)) },
                    navigationIcon = {
                        IconButton(onClick = { finish() }) {
                            Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.back))
                        }
                    },
                    actions = {
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.clear_list)) },
                                    onClick = {
                                        menuOpen = false
                                        Downloads.clearList(this@DownloadsActivity)
                                        render()
                                        message(R.string.list_cleared)
                                    },
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.surfaceContainer),
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.fillMaxSize()) {
                    FolderCard()
                    LazyColumn(
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 16.dp),
                    ) {
                        items(items, key = { it.id }) { item ->
                            DownloadRow(item, Modifier.animateItem())
                        }
                    }
                }
                AnimatedVisibility(
                    visible = items.isEmpty(),
                    modifier = Modifier.align(Alignment.Center),
                    enter = fadeIn(tween(Motion.MEDIUM, easing = Motion.decelerate)),
                    exit = fadeOut(tween(Motion.SHORT)),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconTile(R.drawable.ic_download, size = 72, iconPadding = 20, tint = scheme.onSurfaceVariant)
                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.empty_downloads),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun FolderCard() {
        val scheme = MaterialTheme.colorScheme
        Row(
            modifier = Modifier
                .padding(14.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(scheme.surfaceContainer)
                .clickable { folderPicker.show { folder = Storage.label(this@DownloadsActivity) } }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconTile(R.drawable.ic_folder, size = 40, iconPadding = 8, tint = scheme.primary)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(
                    stringResource(R.string.save_to),
                    style = MaterialTheme.typography.labelMedium,
                    color = scheme.onSurfaceVariant,
                )
                Text(
                    folder,
                    style = MaterialTheme.typography.bodyLarge,
                    color = scheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                stringResource(R.string.change),
                style = MaterialTheme.typography.labelLarge,
                color = scheme.primary,
            )
        }
    }

    @Composable
    private fun DownloadRow(item: Downloads.Item, modifier: Modifier = Modifier) {
        val scheme = MaterialTheme.colorScheme
        val context = LocalContext.current
        var menuOpen by remember { mutableStateOf(false) }
        ListItem(
            modifier = modifier.clickable { open(item) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            leadingContent = { IconTile(R.drawable.ic_file, size = 44, iconPadding = 10, tint = scheme.primary) },
            headlineContent = {
                Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            },
            supportingContent = {
                Column {
                    Text(
                        statusText(context, item),
                        color = if (item.status == Downloads.FAILED) scheme.error else scheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val running = item.status == Downloads.RUNNING
                    if (running || item.status == Downloads.MOVING) {
                        Spacer(Modifier.height(8.dp))
                        if (running && item.size > 0) {
                            val target = (item.done.toFloat() / item.size.toFloat()).coerceIn(0f, 1f)
                            val shown by animateFloatAsState(target, label = "downloadProgress")
                            LinearProgressIndicator(
                                progress = { shown },
                                modifier = Modifier.fillMaxWidth(),
                                trackColor = scheme.surfaceContainerHighest,
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier.fillMaxWidth(),
                                trackColor = scheme.surfaceContainerHighest,
                            )
                        }
                    }
                }
            },
            trailingContent = {
                Box {
                    PressableIconButton(onClick = { menuOpen = true }) {
                        Icon(painterResource(R.drawable.ic_more_vert), contentDescription = stringResource(R.string.more))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        if (item.status == Downloads.DONE) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.open_file)) },
                                onClick = {
                                    menuOpen = false
                                    open(item)
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.share)) },
                                onClick = {
                                    menuOpen = false
                                    share(item)
                                },
                            )
                        }
                        if (item.status == Downloads.FAILED && item.url.startsWith("http")) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.retry)) },
                                onClick = {
                                    menuOpen = false
                                    retry(item)
                                },
                            )
                        }
                        if (item.active) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.cancel_download)) },
                                onClick = {
                                    menuOpen = false
                                    cancel(item)
                                },
                            )
                        } else {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.remove_from_list)) },
                                onClick = {
                                    menuOpen = false
                                    Downloads.remove(this@DownloadsActivity, item.id)
                                    render()
                                },
                            )
                        }
                        if (item.status == Downloads.DONE) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.delete_file)) },
                                onClick = {
                                    menuOpen = false
                                    delete(item)
                                },
                            )
                        }
                    }
                }
            },
        )
    }

    private fun statusText(context: Context, item: Downloads.Item): String = when (item.status) {
        Downloads.DONE -> {
            val size = if (item.size > 0) Formatter.formatShortFileSize(context, item.size) else ""
            val age = DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
            listOf(size, age.toString()).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        Downloads.FAILED -> context.getString(R.string.status_failed)
        Downloads.MOVING -> context.getString(R.string.status_finishing)
        else -> if (item.size > 0) {
            context.getString(
                R.string.status_progress,
                Formatter.formatShortFileSize(context, item.done),
                Formatter.formatShortFileSize(context, item.size),
            )
        } else {
            context.getString(R.string.downloading)
        }
    }
}

/** A rounded square holding an icon, used where the old layouts used `bg_icon_tile`. */
@Composable
fun IconTile(res: Int, size: Int, iconPadding: Int, tint: Color) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .padding(iconPadding.dp),
    ) {
        Icon(painterResource(res), contentDescription = null, tint = tint, modifier = Modifier.fillMaxSize())
    }
}

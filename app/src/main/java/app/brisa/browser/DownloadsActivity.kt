package app.brisa.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.animation.AnimationUtils
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.snackbar.Snackbar
import java.util.concurrent.atomic.AtomicBoolean

class DownloadsActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val syncing = AtomicBoolean(false)
    private val folderPicker = FolderPicker(this)
    private lateinit var adapter: DownloadAdapter
    private lateinit var folderLabel: TextView
    private lateinit var empty: View
    private lateinit var list: RecyclerView
    private var first = true

    private val poll = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 700)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.beforeCreate(this)
        super.onCreate(savedInstanceState)
        Appearance.apply(this)
        setContentView(R.layout.activity_downloads)
        Appearance.finish(this)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.downloadsRoot)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        setSupportActionBar(findViewById<MaterialToolbar>(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        setTitle(R.string.downloads)

        folderLabel = findViewById(R.id.folderLabel)
        empty = findViewById(R.id.empty)
        list = findViewById(R.id.list)
        adapter = DownloadAdapter(::open, ::showMenu)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        list.layoutAnimation = AnimationUtils.loadLayoutAnimation(this, R.anim.layout_list_in)
        findViewById<View>(R.id.folderCard).setOnClickListener { folderPicker.show { renderFolder() } }
        renderFolder()
    }

    override fun onStart() {
        super.onStart()
        handler.post(poll)
    }

    override fun onStop() {
        handler.removeCallbacks(poll)
        super.onStop()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, R.string.clear_list).setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == 1) {
            Downloads.clearList(this)
            render()
            Snackbar.make(list, R.string.list_cleared, Snackbar.LENGTH_SHORT).show()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    @Suppress("DEPRECATION")
    override fun finish() {
        super.finish()
        overridePendingTransition(R.anim.enter_from_left, R.anim.exit_to_right)
    }

    private fun renderFolder() {
        folderLabel.text = Storage.label(this)
    }

    private fun refresh() {
        if (!syncing.compareAndSet(false, true)) return
        val app = applicationContext
        Thread {
            runCatching { Downloads.sync(app) }
            handler.post {
                syncing.set(false)
                if (!isDestroyed) render()
            }
        }.start()
    }

    private fun render() {
        val items = Downloads.all(this)
        val wasEmpty = adapter.itemCount == 0
        adapter.submitList(items) {
            if (first && items.isNotEmpty()) {
                first = false
                list.scheduleLayoutAnimation()
            }
        }
        if (items.isEmpty()) {
            if (!empty.isVisible) {
                empty.isVisible = true
                empty.animate().alpha(1f).setDuration(Motion.MEDIUM).setInterpolator(Motion.decelerate).start()
            }
        } else if (empty.isVisible) {
            empty.animate().cancel()
            empty.alpha = 0f
            empty.isGone = true
        }
        if (wasEmpty && items.isEmpty()) first = true
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
            Snackbar.make(list, R.string.cant_open, Snackbar.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Snackbar.make(list, R.string.cant_open, Snackbar.LENGTH_SHORT).show()
        }
    }

    private fun share(item: Downloads.Item) {
        val uri = item.uri ?: return
        val send = Intent(Intent.ACTION_SEND).setType(item.mime).putExtra(Intent.EXTRA_STREAM, Uri.parse(uri))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(Intent.createChooser(send, null))
    }

    private fun showMenu(anchor: View, item: Downloads.Item) {
        val menu = PopupMenu(this, anchor)
        if (item.status == Downloads.DONE) {
            menu.menu.add(0, 1, 0, R.string.open_file)
            menu.menu.add(0, 2, 1, R.string.share)
        }
        if (item.active) menu.menu.add(0, 3, 2, R.string.cancel_download)
        else menu.menu.add(0, 4, 2, R.string.remove_from_list)
        if (item.status == Downloads.DONE) menu.menu.add(0, 5, 3, R.string.delete_file)
        menu.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> open(item)
                2 -> share(item)
                3 -> cancel(item)
                4 -> Downloads.remove(this, item.id).also { render() }
                5 -> delete(item)
            }
            true
        }
        menu.show()
    }

    private fun cancel(item: Downloads.Item) {
        val app = applicationContext
        Thread {
            runCatching { app.getSystemService(DownloadManager::class.java).remove(item.dmId) }
            Downloads.remove(app, item.id)
            handler.post { if (!isDestroyed) render() }
        }.start()
    }

    private fun delete(item: Downloads.Item) {
        val app = applicationContext
        Thread {
            Storage.deleteFile(app, item)
            Downloads.remove(app, item.id)
            handler.post { if (!isDestroyed) render() }
        }.start()
    }

    private class DownloadAdapter(
        val onOpen: (Downloads.Item) -> Unit,
        val onMore: (View, Downloads.Item) -> Unit,
    ) : ListAdapter<Downloads.Item, DownloadAdapter.Holder>(object : DiffUtil.ItemCallback<Downloads.Item>() {
        override fun areItemsTheSame(a: Downloads.Item, b: Downloads.Item) = a.id == b.id
        override fun areContentsTheSame(a: Downloads.Item, b: Downloads.Item) = a == b
    }) {
        class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.dlIcon)
            val name: TextView = view.findViewById(R.id.dlName)
            val status: TextView = view.findViewById(R.id.dlStatus)
            val progress: LinearProgressIndicator = view.findViewById(R.id.dlProgress)
            val more: ImageButton = view.findViewById(R.id.dlMore)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_download, parent, false))

        override fun onBindViewHolder(h: Holder, position: Int) {
            val item = getItem(position)
            val ctx = h.itemView.context
            h.name.text = item.name
            h.itemView.setOnClickListener { onOpen(item) }
            h.more.setOnClickListener { onMore(it, item) }
            h.more.pressable()
            h.status.text = when (item.status) {
                Downloads.DONE -> {
                    val size = if (item.size > 0) Formatter.formatShortFileSize(ctx, item.size) else ""
                    val age = DateUtils.getRelativeTimeSpanString(item.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS)
                    listOf(size, age.toString()).filter { it.isNotEmpty() }.joinToString(" · ")
                }
                Downloads.FAILED -> ctx.getString(R.string.status_failed)
                Downloads.MOVING -> ctx.getString(R.string.status_finishing)
                else -> if (item.size > 0) ctx.getString(
                    R.string.status_progress,
                    Formatter.formatShortFileSize(ctx, item.done),
                    Formatter.formatShortFileSize(ctx, item.size),
                ) else ctx.getString(R.string.downloading)
            }
            h.status.setTextColor(ctx.themeColor(if (item.status == Downloads.FAILED) R.attr.brisaWarning else R.attr.brisaMuted))
            val running = item.status == Downloads.RUNNING
            h.progress.isVisible = running || item.status == Downloads.MOVING
            if (h.progress.isVisible) {
                if (running && item.size > 0) {
                    h.progress.isIndeterminate = false
                    h.progress.setProgressCompat((item.done * 100 / item.size).toInt(), true)
                } else {
                    h.progress.isIndeterminate = true
                }
            }
        }
    }
}

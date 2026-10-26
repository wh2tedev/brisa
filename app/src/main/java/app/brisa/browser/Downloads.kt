package app.brisa.browser

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.preference.PreferenceManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.OutputStream

/** Where downloads are written: the public Downloads folder, or a folder the user picked. */
object Storage {
    fun treeUri(context: Context): Uri? {
        val raw = PreferenceManager.getDefaultSharedPreferences(context).getString(Prefs.DOWNLOAD_TREE, null) ?: return null
        val uri = Uri.parse(raw)
        val ok = context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return uri.takeIf { ok }
    }

    fun label(context: Context): String {
        val tree = treeUri(context) ?: return context.getString(R.string.default_folder)
        return DocumentFile.fromTreeUri(context, tree)?.name ?: context.getString(R.string.default_folder)
    }

    /** Writes a file and returns its Uri, or null on failure. Falls back to public Downloads if the chosen folder fails. */
    fun save(context: Context, name: String, mime: String, tree: Uri?, write: (OutputStream) -> Unit): Uri? {
        val type = mime.ifBlank { "application/octet-stream" }
        if (tree != null) {
            val saved = runCatching {
                val dir = DocumentFile.fromTreeUri(context, tree) ?: error("no folder")
                val file = dir.createFile(type, name) ?: error("no file")
                context.contentResolver.openOutputStream(file.uri)?.use(write) ?: error("no stream")
                file.uri
            }.getOrNull()
            if (saved != null) return saved
        }
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, type)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: error("insert")
            context.contentResolver.openOutputStream(uri)?.use(write) ?: error("stream")
            uri
        }.getOrNull()
    }

    fun deleteFile(context: Context, item: Downloads.Item) {
        runCatching { if (item.dmId > 0) context.getSystemService(DownloadManager::class.java).remove(item.dmId) }
        if (item.tree != null || item.dmId <= 0) {
            val uri = item.uri?.let(Uri::parse) ?: return
            runCatching {
                if (DocumentsContract.isDocumentUri(context, uri)) DocumentsContract.deleteDocument(context.contentResolver, uri)
                else context.contentResolver.delete(uri, null, null)
            }
        }
    }
}

/** The list of downloads shown in the Downloads screen. Kept on the device only. */
object Downloads {
    const val RUNNING = 0
    const val DONE = 1
    const val FAILED = 2
    const val MOVING = 3

    data class Item(
        val id: String,
        val dmId: Long,
        val name: String,
        val url: String,
        val mime: String,
        val size: Long,
        val done: Long,
        val time: Long,
        val status: Int,
        val uri: String?,
        val tree: String?,
        val temp: String?,
    ) {
        val active get() = status == RUNNING || status == MOVING
    }

    private const val FILE = "downloads"
    private const val KEY = "items"
    private const val MAX = 200
    private var cache: MutableList<Item>? = null

    @Synchronized
    fun all(context: Context): List<Item> = entries(context).toList()

    @Synchronized
    fun add(context: Context, item: Item) {
        val list = entries(context)
        list.add(0, item)
        while (list.size > MAX) list.removeAt(list.lastIndex)
        save(context)
    }

    @Synchronized
    fun update(context: Context, id: String, change: (Item) -> Item) {
        val list = entries(context)
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = change(list[i])
        save(context)
    }

    @Synchronized
    fun remove(context: Context, id: String) {
        entries(context).removeAll { it.id == id }
        save(context)
    }

    /** Removes finished and failed entries from the list. Files are left alone. */
    @Synchronized
    fun clearList(context: Context) {
        entries(context).removeAll { !it.active }
        save(context)
    }

    @Synchronized
    private fun claim(context: Context, id: String): Boolean {
        val list = entries(context)
        val i = list.indexOfFirst { it.id == id && it.status == RUNNING }
        if (i < 0) return false
        list[i] = list[i].copy(status = MOVING)
        save(context)
        return true
    }

    /** Pulls the state of running downloads from the system. Safe to call from any background thread. */
    fun sync(context: Context) {
        val dm = context.getSystemService(DownloadManager::class.java)
        all(context).filter { it.status == RUNNING && it.dmId > 0 }.forEach { item ->
            var status = -1
            var done = 0L
            var total = -1L
            val found = dm.query(DownloadManager.Query().setFilterById(item.dmId))?.use { c ->
                if (!c.moveToFirst()) false else {
                    status = c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                    done = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                    total = c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                    true
                }
            } ?: false
            when {
                !found -> update(context, item.id) { it.copy(status = FAILED) }
                status == DownloadManager.STATUS_FAILED -> update(context, item.id) { it.copy(status = FAILED) }
                status == DownloadManager.STATUS_SUCCESSFUL -> finish(context, dm, item, if (total > 0) total else done)
                else -> update(context, item.id) { it.copy(done = done, size = total) }
            }
        }
    }

    private fun finish(context: Context, dm: DownloadManager, item: Item, size: Long) {
        if (item.temp == null) {
            val uri = dm.getUriForDownloadedFile(item.dmId)?.toString()
            update(context, item.id) { it.copy(status = DONE, uri = uri, size = size, done = size) }
            return
        }
        if (!claim(context, item.id)) return
        val file = File(item.temp)
        val saved = runCatching {
            Storage.save(context, item.name, item.mime, item.tree?.let(Uri::parse)) { out ->
                FileInputStream(file).use { it.copyTo(out) }
            }
        }.getOrNull()
        if (saved != null) {
            file.delete()
            file.parentFile?.delete()
        }
        update(context, item.id) {
            if (saved != null) it.copy(status = DONE, uri = saved.toString(), size = size, done = size) else it.copy(status = FAILED)
        }
    }

    private fun entries(context: Context): MutableList<Item> {
        cache?.let { return it }
        val raw = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, null)
        val loaded = runCatching {
            val array = JSONArray(raw)
            MutableList(array.length()) { i ->
                array.getJSONObject(i).let {
                    Item(
                        it.getString("id"), it.getLong("dm"), it.getString("name"), it.getString("url"),
                        it.getString("mime"), it.getLong("size"), it.getLong("done"), it.getLong("time"),
                        it.getInt("status"), it.optString("uri").ifEmpty { null },
                        it.optString("tree").ifEmpty { null }, it.optString("temp").ifEmpty { null },
                    )
                }
            }
        }.getOrDefault(mutableListOf())
        cache = loaded
        return loaded
    }

    private fun save(context: Context) {
        val array = JSONArray()
        entries(context).forEach {
            array.put(
                JSONObject().put("id", it.id).put("dm", it.dmId).put("name", it.name).put("url", it.url)
                    .put("mime", it.mime).put("size", it.size).put("done", it.done).put("time", it.time)
                    .put("status", it.status).put("uri", it.uri.orEmpty()).put("tree", it.tree.orEmpty())
                    .put("temp", it.temp.orEmpty()),
            )
        }
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY, array.toString()).apply()
    }
}

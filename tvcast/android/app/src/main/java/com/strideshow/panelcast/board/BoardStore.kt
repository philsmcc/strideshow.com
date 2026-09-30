package com.strideshow.panelcast.board

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Persistence for the board.
 *
 * Autosave writes a single JSON document to app-private storage. Writes are
 * coalesced (at most one in flight, latest wins) and happen off the main
 * thread, because a page with a few thousand strokes serialises to a few
 * hundred KB and that must never stall inking.
 *
 * The write is atomic (temp file + rename) so a power cut mid-save leaves
 * the previous good copy rather than a truncated one.
 */
class BoardStore(context: Context) {
    private val dir = context.filesDir
    private val file = File(dir, "board.json")
    private val tmp = File(dir, "board.json.tmp")
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "PanelCast/Store").apply { isDaemon = true } }
    private val pending = AtomicBoolean(false)
    @Volatile private var latest: JSONObject? = null
    private val picturesDir = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "PanelCast",
    )
    private val appPictures = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), "PanelCast")

    fun load(): Board? {
        if (!file.exists()) return null
        return try {
            Board.fromJson(JSONObject(file.readText()))
        } catch (t: Throwable) {
            Log.w(TAG, "could not load board: ${t.message}")
            // Keep the corrupt file for diagnosis rather than overwriting it.
            try { file.renameTo(File(dir, "board.corrupt.json")) } catch (_: Exception) {}
            null
        }
    }

    /** Serialise on the caller's thread (cheap-ish), write on the IO thread. */
    fun saveAsync(board: Board) {
        latest = board.toJson()
        if (pending.compareAndSet(false, true)) {
            io.execute {
                try {
                    // Drain: write whatever is newest at the time we run.
                    var doc: JSONObject?
                    do {
                        doc = latest; latest = null
                        if (doc != null) writeAtomic(doc)
                    } while (latest != null)
                } finally {
                    pending.set(false)
                    // A save requested between the drain and the flag reset.
                    if (latest != null) saveAsyncFromLatest()
                }
            }
        }
    }

    private fun saveAsyncFromLatest() {
        if (pending.compareAndSet(false, true)) io.execute {
            try { latest?.let { writeAtomic(it); latest = null } } finally { pending.set(false) }
        }
    }

    /** Synchronous save for onPause/onDestroy where the process may die next. */
    fun saveNow(board: Board) {
        try { writeAtomic(board.toJson()) } catch (t: Throwable) { Log.w(TAG, "saveNow failed: ${t.message}") }
    }

    private fun writeAtomic(doc: JSONObject) {
        try {
            tmp.writeText(doc.toString())
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (t: Throwable) {
            Log.w(TAG, "save failed: ${t.message}")
        }
    }

    /**
     * Export a page as PNG. Prefers the shared Pictures folder so the file is
     * reachable from a file manager / USB; falls back to app-scoped external
     * storage on devices that deny the public directory without permission.
     */
    fun exportPng(bmp: Bitmap, pageIndex: Int): File? {
        val name = "PanelCast_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}_p${pageIndex + 1}.png"
        for (target in listOf(picturesDir, appPictures)) {
            try {
                if (!target.exists() && !target.mkdirs()) continue
                val f = File(target, name)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Log.i(TAG, "exported ${f.absolutePath}")
                return f
            } catch (t: Throwable) {
                Log.w(TAG, "export to $target failed: ${t.message}")
            }
        }
        return null
    }

    companion object { private const val TAG = "PanelCast/Store" }
}

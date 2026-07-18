package com.pahtrik.warden

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Append-only local audit log. Stored in the app's private files dir (not
 * shared storage), capped to the last MAX lines. No network; never leaves
 * the device.
 */
object AuditLog {
    private const val FILE = "audit.log"
    private const val MAX = 2000
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Synchronized
    fun add(ctx: Context, event: String) {
        val line = "${fmt.format(Date())}  $event\n"
        val f = ctx.getFileStreamPath(FILE)
        ctx.openFileOutput(FILE, Context.MODE_APPEND).use { it.write(line.toByteArray()) }
        // Trim if oversized
        if (f.length() > MAX * 200L) {
            val kept = f.readLines().takeLast(MAX)
            ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use {
                it.write(kept.joinToString("\n").toByteArray())
                it.write("\n".toByteArray())
            }
        }
    }

    fun read(ctx: Context): List<String> = try {
        ctx.getFileStreamPath(FILE).readLines().asReversed() // newest first
    } catch (_: Exception) { emptyList() }

    fun clear(ctx: Context) {
        ctx.openFileOutput(FILE, Context.MODE_PRIVATE).use { it.write(ByteArray(0)) }
    }
}

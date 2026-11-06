package ug.kuuma.casefile

import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Hands evidence to the rest of the phone, only when the child asks: a PDF saved to
 * Downloads/Kuuma, opened in the phone's PDF viewer, and a share link sent through the
 * phone's share menu (WhatsApp, SMS, email...).
 */
internal object DeviceFiles {
    /** Copies [file] to Downloads/Kuuma. Returns its content URI, or null before Android 10. */
    fun saveToDownloads(context: Context, file: File, mime: String = "application/pdf"): String? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, file.name)
            put(MediaStore.Downloads.MIME_TYPE, mime)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Kuuma")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(file.readBytes()) } ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri.toString()
    }

    /** Opens a saved file in whatever app handles it. False when no app can. */
    fun open(context: Context, uri: String, mime: String = "application/pdf"): Boolean = start(
        context,
        Intent(Intent.ACTION_VIEW)
            .setDataAndType(Uri.parse(uri), mime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    )

    /** The phone's share menu with [text] (a share link), so the child sends it themselves. */
    fun sendText(context: Context, text: String, title: String): Boolean = start(
        context,
        Intent.createChooser(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text),
            title
        )
    )

    private fun start(context: Context, intent: Intent): Boolean = try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: ActivityNotFoundException) {
        false
    }
}

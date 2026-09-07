package com.music.bitchord.download

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/** Durable cover copies: image-loader caches may be cleared while a song stays downloaded. */
internal object DownloadArtwork {
    private fun file(context: Context, uri: Uri) =
        File(File(context.filesDir, "download-artwork"), "${DownloadFolders.key(uri.toString())}.jpg")

    fun save(context: Context, uri: Uri, bytes: ByteArray?): String? = runCatching {
        val target = file(context, uri)
        if (bytes != null && bytes.isNotEmpty()) {
            target.parentFile?.mkdirs()
            val temporary = File.createTempFile("cover-", ".part", target.parentFile)
            try {
                temporary.writeBytes(bytes)
                check(temporary.renameTo(target))
            } finally {
                temporary.delete()
            }
        }
        target.takeIf { it.isFile && it.length() > 0 }?.let { Uri.fromFile(it).toString() }
    }.getOrNull()

    /** Also restores covers from older tagged downloads without using the network. */
    fun local(context: Context, uri: Uri): String? {
        save(context, uri, null)?.let { return it }
        val bytes = runCatching {
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(context, uri)
                retriever.embeddedPicture
            } finally {
                retriever.release()
            }
        }.getOrNull()
        return save(context, uri, bytes)
    }

    fun delete(context: Context, uri: Uri) { file(context, uri).delete() }
}

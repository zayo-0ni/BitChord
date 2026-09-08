package com.music.bitchord.download

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Shares completed files only. The original download and its embedded tags remain intact. */
internal object DownloadExport {
    suspend fun prepare(context: Context, songs: List<Song>): List<Uri> = withContext(Dispatchers.IO) {
        require(songs.isNotEmpty()) { context.getString(R.string.export_download_first) }
        val originals = songs.map { song ->
            val uri = song.localUri?.let(Uri::parse) ?: Downloads.savedUri(context, song.videoId)
            require(uri != null && DownloadStore.exists(context, uri)) {
                context.getString(R.string.export_download_first)
            }
            uri to song
        }.distinctBy { it.first }
        val root = File(context.cacheDir, "shared/audio").apply { mkdirs() }
        val cutoff = System.currentTimeMillis() - 48 * 60 * 60 * 1000L
        root.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.deleteRecursively() }
        val session = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        try {
            originals.mapIndexed { index, (uri, song) ->
                if (uri.scheme == "content") {
                    val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    }
                    if (name == null || name == DownloadFolders.visibleFileName(name)) return@mapIndexed uri
                    val folder = File(session, index.toString()).apply { mkdirs() }
                    val target = File(folder, File(DownloadFolders.visibleFileName(name)).name)
                    context.contentResolver.openInputStream(uri).use { input ->
                        target.outputStream().use { copy(requireNotNull(input), it) }
                    }
                    return@mapIndexed FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                }
                val source = File(requireNotNull(uri.path))
                if (source.name == "playlist.m3u8") {
                    // Segmented offline audio needs all its companion files to remain usable.
                    val target = File(session, "${index + 1} - ${DownloadFolders.component(song.title)}.zip")
                    ZipOutputStream(target.outputStream()).use { zip ->
                        zip.setLevel(0)
                        val folder = requireNotNull(source.parentFile)
                        require(folder.parentFile?.canonicalFile == File(context.filesDir, "offline-hls").canonicalFile)
                        folder.walkTopDown().filter { it.isFile }.forEach { part ->
                            currentCoroutineContext().ensureActive()
                            require(part.canonicalPath.startsWith(folder.canonicalPath + File.separator))
                            zip.putNextEntry(ZipEntry(part.relativeTo(folder).invariantSeparatorsPath))
                            part.inputStream().use { copy(it, zip) }
                            zip.closeEntry()
                        }
                    }
                    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                } else {
                    // Share private downloads directly, avoiding a second full album in cache.
                    try {
                        require(source.name == DownloadFolders.visibleFileName(source.name))
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", source)
                    } catch (_: IllegalArgumentException) {
                        val folder = File(session, index.toString()).apply { mkdirs() }
                        val target = File(folder, DownloadFolders.visibleFileName(source.name))
                        source.inputStream().use { input -> target.outputStream().use { copy(input, it) } }
                        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", target)
                    }
                }
            }
        } catch (error: Exception) {
            session.deleteRecursively()
            throw error
        }
    }

    fun share(context: Context, uris: List<Uri>, title: String) {
        require(uris.isNotEmpty())
        val types = uris.map { context.contentResolver.getType(it) ?: "application/octet-stream" }.distinct()
        val intent = Intent(if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
            type = types.singleOrNull() ?: if (types.all { it.startsWith("audio/") }) "audio/*" else "*/*"
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            clipData = ClipData.newUri(context.contentResolver, title, uris.first()).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, title).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    suspend fun save(context: Context, uris: List<Uri>, tree: Uri, title: String): Int = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val folder = if (uris.size > 1) {
            DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR,
                DownloadFolders.component(title)) ?: error(context.getString(R.string.audio_export_failed))
        } else parent
        var saved = 0
        for (uri in uris) {
            currentCoroutineContext().ensureActive()
            var target: Uri? = null
            try {
                val name = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                    if (it.moveToFirst()) it.getString(0) else null
                } ?: "Track-${saved + 1}.m4a"
                val mime = resolver.getType(uri)
                    ?: MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.'))
                    ?: "application/octet-stream"
                val created = DocumentsContract.createDocument(resolver, folder, mime, DownloadFolders.visibleFileName(name))
                    ?: error(context.getString(R.string.audio_export_failed))
                target = created
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input)
                    resolver.openOutputStream(created, "w").use { output ->
                        copy(input, requireNotNull(output))
                    }
                }
                saved++
            } catch (error: Exception) {
                target?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) } }
                if (error is CancellationException) throw error
            }
        }
        saved
    }

    private suspend fun copy(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) return
            output.write(buffer, 0, read)
        }
    }
}

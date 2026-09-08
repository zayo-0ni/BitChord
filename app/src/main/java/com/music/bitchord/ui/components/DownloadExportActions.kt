package com.music.bitchord.ui.components

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.music.bitchord.R
import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadExport
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The document picker grants access only to the destination the listener chooses. */
@Composable
internal fun rememberDownloadExportActions(): (List<Song>, String, Boolean) -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    var label by rememberSaveable { mutableStateOf("") }
    var busy by androidx.compose.runtime.remember { mutableStateOf(false) }
    fun message(text: String) { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        val uris = pending.map(Uri::parse)
        pending = arrayListOf()
        if (tree != null && uris.isNotEmpty()) scope.launch {
            busy = true
            message(context.getString(R.string.export_preparing))
            try {
                val saved = DownloadExport.save(context, uris, tree, label)
                message(context.getString(R.string.export_saved, saved, uris.size))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message(context.getString(R.string.export_failed))
            } finally { busy = false }
        }
    }
    return { songs, title, save ->
        if (!busy && pending.isEmpty()) scope.launch {
            busy = true
            message(context.getString(R.string.export_preparing))
            try {
                val uris = DownloadExport.prepare(context, songs)
                if (save) {
                    pending = ArrayList(uris.map(Uri::toString))
                    label = title
                    picker.launch(null)
                } else DownloadExport.share(context, uris, title)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                pending = arrayListOf()
                message(error.message ?: context.getString(R.string.export_failed))
            } finally { busy = false }
        }
    }
}

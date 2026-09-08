package com.music.bitchord.download

import com.music.bitchord.data.model.Song
import java.security.MessageDigest

/** Stable, filesystem-safe names shared by private and exported downloads. */
internal object DownloadFolders {
    fun forSong(song: Song, collection: DownloadTarget? = null): String = when {
        collection?.playlist == true -> "Playlists/${component(collection.title)} [${key(collection.id)}]"
        collection != null -> "Albums/${component(collection.title)} [${key(collection.id)}]"
        !song.albumName.isNullOrBlank() -> "Albums/${component(song.albumName)} [${key(song.albumId ?: "${song.artist}\n${song.albumName}")}]"
        else -> "Songs"
    }

    /** Strip only BitChord's generated suffix; keep titles such as Live [2024]. */
    fun visibleFileName(name: String): String =
        name.replace(Regex(""" \[[0-9a-f]{12}\](?=\.[^.]+$)"""), "")

    fun withDetails(song: Song, collection: DownloadTarget?): Song {
        if (collection == null || collection.playlist) return song
        return song.copy(
            albumName = song.albumName?.takeIf { it.isNotBlank() } ?: collection.title,
            albumId = song.albumId ?: collection.id,
            thumbnailUrl = song.thumbnailUrl?.takeIf { it.isNotBlank() } ?: collection.thumbnailUrl,
        )
    }

    fun key(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).take(6)
        .joinToString("") { "%02x".format(it) }

    fun component(value: String): String {
        val clean = value.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), " ")
            .replace(Regex("\\s+"), " ").trim().trim('.').trim().ifBlank { "Untitled" }
        // A character limit alone overflows the filesystem limit for Arabic and emoji.
        val result = StringBuilder()
        var bytes = 0
        for (point in clean.codePoints().toArray()) {
            val character = String(Character.toChars(point))
            bytes += character.toByteArray(Charsets.UTF_8).size
            if (bytes > 180) break
            result.append(character)
        }
        return result.toString().trimEnd()
    }
}

data class DownloadTarget(
    val id: String,
    val title: String,
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val playlist: Boolean = false,
)

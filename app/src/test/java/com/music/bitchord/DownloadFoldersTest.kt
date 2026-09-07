package com.music.bitchord

import com.music.bitchord.data.model.Song
import com.music.bitchord.download.DownloadFolders
import com.music.bitchord.download.DownloadTarget
import com.music.bitchord.download.DownloadedCollection
import com.music.bitchord.download.albumEntries
import org.junit.Assert.*
import org.junit.Test

class DownloadFoldersTest {
    private val song = Song("track", "أغنية", "فنان", "https://cover", albumName = "ألبوم")

    @Test fun `a playlist gets its own folder without replacing the song album or cover`() {
        val playlist = DownloadTarget("PL1", "المفضلة", thumbnailUrl = "https://playlist", playlist = true)
        assertTrue(DownloadFolders.forSong(song, playlist).startsWith("Playlists/المفضلة ["))
        assertEquals(song, DownloadFolders.withDetails(song, playlist))
    }

    @Test fun `album details fill missing track fields before writing tags`() {
        val track = song.copy(albumName = null, albumId = null, thumbnailUrl = null)
        val album = DownloadTarget("MPRE1", "ألبوم", thumbnailUrl = "https://album")
        val enriched = DownloadFolders.withDetails(track, album)
        assertEquals("ألبوم", enriched.albumName)
        assertEquals("MPRE1", enriched.albumId)
        assertEquals("https://album", enriched.thumbnailUrl)
        assertEquals(DownloadFolders.forSong(enriched), DownloadFolders.forSong(enriched, album))
    }

    @Test fun `songs without albums stay in the singles folder`() {
        assertEquals("Songs", DownloadFolders.forSong(song.copy(albumName = null)))
        assertEquals("Songs", DownloadFolders.forSong(song.copy(albumName = "  ")))
    }

    @Test fun `same named playlists keep distinct identities`() {
        val first = DownloadTarget("PL1", "Favorites", playlist = true)
        assertNotEquals(DownloadFolders.forSong(song, first),
            DownloadFolders.forSong(song, first.copy(id = "PL2")))
    }

    @Test fun `folder names cannot introduce path traversal or hidden files`() {
        listOf("../../", "..", "/absolute/path", "a\\b", "\u0000\n\t", "...").forEach { input ->
            val name = DownloadFolders.component(input)
            assertTrue(name.isNotBlank())
            assertFalse(name.startsWith('.'))
            assertFalse(name.contains('/'))
            assertFalse(name.contains('\\'))
            assertFalse(name.any { it.code < 32 })
        }
    }

    @Test fun `Arabic and emoji names fit the byte limit without splitting unicode`() {
        val name = DownloadFolders.component("موسيقى🎵".repeat(100))
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= 180)
        assertEquals(name, String(name.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        assertTrue(name.startsWith("موسيقى🎵"))
    }

    @Test fun `same titled albums by different artists do not share a folder`() {
        assertNotEquals(DownloadFolders.forSong(song), DownloadFolders.forSong(song.copy(artist = "فنان ثاني")))
    }
    @Test fun `same titled albums remain separate while downloaded tracks are not duplicated`() {
        val second = song.copy(videoId = "second", artist = "Other artist")
        val folder = DownloadedCollection("album", "ألبوم", "فنان", null, false, listOf(song))
        val entries = albumEntries(listOf(song, second), listOf(folder))
        assertEquals(2, entries.size)
        assertEquals(setOf("track", "second"), entries.flatMap { it.songs }.map { it.videoId }.toSet())
        assertEquals(2, entries.sumOf { it.songs.size })
    }

    @Test fun `playlist order is preserved and membership does not hide the source albums`() {
        val second = song.copy(videoId = "second")
        val folder = DownloadedCollection("playlist", "ألبوم", "", null, true, listOf(second, song))
        val playlists = albumEntries(emptyList(), listOf(folder))
        assertEquals(listOf("second", "track"), playlists.single().songs.map { it.videoId })
        val all = albumEntries(listOf(song, second), listOf(folder))
        assertEquals(1, all.count { !it.playlist })
        assertEquals(2, all.map { it.key }.distinct().size)
    }
}

package com.music.bitchord

import com.music.bitchord.data.innertube.InnertubeParser
import com.music.bitchord.data.model.SearchResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test

class AudioVersionParserTest {
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun watch(id: String, type: String? = "MUSIC_VIDEO_TYPE_OMV"): String = """
        {"videoId":"$id","title":{"runs":[{"text":"$id"}]},
         "longBylineText":{"runs":[{"text":"Artist"}]},
         "thumbnail":{"thumbnails":[{"url":"https://example.com/$id.jpg","width":200,"height":200}]},
         "navigationEndpoint":{"watchEndpoint":{"videoId":"$id",
         "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{
         ${if (type == null) "" else "\"musicVideoType\":\"$type\""}}}}}}
    """
    private fun wrapper(primary: String, counterpart: String) = """
        {"playlistPanelVideoWrapperRenderer":{
        "primaryRenderer":{"playlistPanelVideoRenderer":$primary},
        "counterpart":[{"counterpartRenderer":{"playlistPanelVideoRenderer":$counterpart}}]}}
    """
    private fun searchRow(type: String, width: Int, height: Int): JsonObject = json("""
        {"contents":[{"musicResponsiveListItemRenderer":{
         "playlistItemData":{"videoId":"id"},
         "flexColumns":[{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{
           "text":"Track","navigationEndpoint":{"watchEndpoint":{"videoId":"id",
           "watchEndpointMusicSupportedConfigs":{"watchEndpointMusicConfig":{"musicVideoType":"$type"}}}}
         }]}}},{"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Artist • 3:00"}]}}}],
         "thumbnail":{"musicThumbnailRenderer":{"thumbnail":{"thumbnails":[{
          "url":"https://example.com/cover.jpg","width":$width,"height":$height}]}}}
        }}]}
    """)

    @Test fun `square official video remains a video`() {
        val response = searchRow("MUSIC_VIDEO_TYPE_OMV", 200, 200)
        assertTrue(InnertubeParser.parseSearchSongs(response).isEmpty())
        val row = InnertubeParser.parseSearchPage(response, includeVideos = true).rows.single() as SearchResult.Track
        assertTrue(row.song.isVideo)
    }
    @Test fun `official audio with a nonsquare thumbnail remains audio`() {
        val song = InnertubeParser.parseSearchSongs(searchRow("MUSIC_VIDEO_TYPE_ATV", 320, 180)).single()
        assertFalse(song.isVideo)
    }
    @Test fun `watch video classification does not require English views text`() {
        assertTrue(InnertubeParser.parseWatchQueue(json("""{"playlistPanelVideoRenderer":${watch("video")}}""")).single().isVideo)
    }
    @Test fun `explicit counterpart changes id and artwork together`() {
        val response = json(wrapper(watch("video"), watch("audio", "MUSIC_VIDEO_TYPE_ATV")))
        val audio = InnertubeParser.parseAudioCounterpart(response, "video")!!
        assertEquals("audio", audio.videoId)
        assertEquals("https://example.com/audio.jpg", audio.thumbnailUrl)
        assertFalse(audio.isVideo)
    }
    @Test fun `unrelated paired recommendation cannot replace the requested video`() {
        val response = json("""{"contents":[${wrapper(watch("other"), watch("audio", "MUSIC_VIDEO_TYPE_ATV"))},
          {"playlistPanelVideoRenderer":${watch("requested")}}]}""")
        assertNull(InnertubeParser.parseAudioCounterpart(response, "requested"))
    }
    @Test fun `audio primary can be paired with a requested video counterpart`() {
        val response = json(wrapper(watch("audio", "MUSIC_VIDEO_TYPE_ATV"), watch("video")))
        assertEquals("audio", InnertubeParser.parseAudioCounterpart(response, "video")?.videoId)
    }
    @Test fun `square counterpart alone does not prove official audio`() {
        assertNull(InnertubeParser.parseAudioCounterpart(json(wrapper(watch("video"), watch("unknown", null))), "video"))
    }
    @Test fun `autoplay enqueues only primary rows and not their alternatives`() {
        val response = json("""{"contents":[${wrapper(watch("video"), watch("audio", "MUSIC_VIDEO_TYPE_ATV"))},
            {"playlistPanelVideoRenderer":${watch("next", "MUSIC_VIDEO_TYPE_ATV")}}]}""")
        assertEquals(listOf("video", "next"), InnertubeParser.parseWatchQueue(response).map { it.videoId })
    }
    @Test fun `video with no explicit counterpart stays unmatched`() {
        assertNull(InnertubeParser.parseAudioCounterpart(json("""{"playlistPanelVideoRenderer":${watch("video")}}"""), "video"))
    }
}

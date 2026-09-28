package app.folio.library

import org.json.JSONObject
import java.io.File

internal data class AudioTrack(val file: File, val name: String, val startMs: Long, val durationMs: Long)
internal object AudioBundle {
    fun read(file: File): List<AudioTrack> {
        require(file.length() <= 2_000_000)
        val array = JSONObject(file.readText()).getJSONArray("tracks")
        require(array.length() in 1..1000)
        var start = 0L
        return (0 until array.length()).map { i ->
            val track = array.getJSONObject(i); val name = track.getString("file")
            require(name.matches(Regex("[A-Za-z0-9._-]+")) && name != "." && name != "..")
            val source = File(file.parentFile, name); require(source.isFile)
            val duration = track.getLong("durationMs"); require(duration in 1..86_400_000)
            AudioTrack(source, track.getString("name"), start, duration).also { start += duration }
        }
    }
}

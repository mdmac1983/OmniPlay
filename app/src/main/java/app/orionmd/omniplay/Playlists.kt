package app.orionmd.omniplay

import org.json.JSONArray
import org.json.JSONObject

/** Your playlists (shared across every source). Songs are kept by id, so a playlist survives rescans. Two automatic ones
 *  sit on top: Most played and Recently added. */
data class Playlist(val id: String, val name: String, val ids: List<Long>, val created: Long, val smart: Boolean = false)

fun AppState.playlists(): List<Playlist> {
    prefGen
    val a = try { JSONArray(pref("playlists", "[]")) } catch (_: Exception) { JSONArray() }
    return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
        Playlist(o.optString("id"), o.optString("name"), o.optJSONArray("ids")?.let { x -> (0 until x.length()).map { x.optLong(it) } } ?: emptyList(), o.optLong("created"))
    }
}
private fun AppState.savePlaylists(l: List<Playlist>) = setPref("playlists", JSONArray(l.map { p ->
    JSONObject().put("id", p.id).put("name", p.name).put("ids", JSONArray(p.ids)).put("created", p.created)
}).toString())

fun AppState.smartPlaylists(): List<Playlist> {
    val songs = songs()
    val most = plays.entries.sortedByDescending { it.value }.map { it.key }.filter { id -> songs.any { it.i == id } }.take(60)
    val recent = songs.sortedByDescending { it.cr }.take(60).map { it.i }
    return listOfNotNull(
        Playlist("smart:most", "Most played", most, 0, true).takeIf { most.isNotEmpty() },
        Playlist("smart:recent", "Recently added", recent, 0, true).takeIf { recent.isNotEmpty() },
    )
}
fun AppState.playlist(id: String): Playlist? = (smartPlaylists() + playlists()).firstOrNull { it.id == id }
fun AppState.playlistSongs(p: Playlist): List<Item> { val byId = songs().associateBy { it.i }; return p.ids.mapNotNull { byId[it] } }

fun AppState.createPlaylist(name: String, ids: List<Long> = emptyList()): Playlist {
    val p = Playlist("pl" + System.currentTimeMillis().toString(36), name.trim().ifBlank { "My playlist" }, ids.distinct(), System.currentTimeMillis())
    savePlaylists(playlists() + p); return p
}
fun AppState.addToPlaylist(id: String, ids: List<Long>): Int {
    var added = 0
    savePlaylists(playlists().map { p -> if (p.id != id) p else { val new = ids.distinct().filter { it !in p.ids }; added = new.size; p.copy(ids = p.ids + new) } })
    return added
}
fun AppState.removeFromPlaylist(id: String, songId: Long) = savePlaylists(playlists().map { p -> if (p.id == id) p.copy(ids = p.ids - songId) else p })
fun AppState.movePlaylistSong(id: String, from: Int, to: Int) = savePlaylists(playlists().map { p ->
    if (p.id != id || from !in p.ids.indices || to !in p.ids.indices) p else p.copy(ids = p.ids.toMutableList().apply { add(to, removeAt(from)) })
})
fun AppState.renamePlaylist(id: String, name: String) = savePlaylists(playlists().map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }) else it })
fun AppState.deletePlaylist(id: String) = savePlaylists(playlists().filter { it.id != id })
fun AppState.playPlaylist(p: Playlist, shuffle: Boolean = false): String {
    val list = playlistSongs(p)
    if (list.isEmpty()) return "\"${p.name}\" is empty."
    playList(if (shuffle) list.shuffled() else list, 0)
    return "Playing \"${p.name}\" (${list.size} song${if (list.size == 1) "" else "s"}${if (shuffle) ", shuffled" else ""})."
}

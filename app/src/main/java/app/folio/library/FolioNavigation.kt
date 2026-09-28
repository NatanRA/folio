package app.folio.library

import java.net.URI
import java.net.URLDecoder

/** Read-only destinations. Pairing/import/playback deliberately use separate paths. */
data class FolioNavigation(val tab: Int, val query: String? = null) {
    companion object {
        fun from(link: String?): FolioNavigation? {
            if (link == null || link.length > 2048) return null
            val uri = runCatching { URI(link) }.getOrNull() ?: return null
            if (uri.scheme != "folio" || uri.host != "navigate" || !uri.rawPath.isNullOrEmpty()
                || uri.userInfo != null || uri.port != -1 || uri.fragment != null) return null
            val fields = mutableMapOf<String, String>()
            for (entry in (uri.rawQuery ?: return null).split('&')) {
                val pair = entry.split('=', limit = 2)
                if (pair.size != 2 || pair[0] !in setOf("screen", "q") || fields.containsKey(pair[0])) return null
                fields[pair[0]] = runCatching { URLDecoder.decode(pair[1], "UTF-8") }.getOrNull() ?: return null
            }
            val tab = listOf("home", "library", "downloads", "server").indexOf(fields["screen"])
            if (tab < 0) return null
            val query = fields["q"]
            if (query != null && (tab != 1 || query.isBlank() || query.length > 200 || query.any { it.code < 32 || it.code == 127 })) return null
            return FolioNavigation(tab, query)
        }
    }
}

package io.github.kickoman.qiyaa.jam

data class JamLink(val serverUrl: String, val roomId: String, val joinSecret: String?) {
    companion object {
        private const val DEFAULT_PORT = "443"
        private const val TRAILING_PUNCTUATION = ".,;:!?)]}>'\"»"
        private val SECRET = Regex("^[A-Za-z0-9_-]{22}$")
        private val LINK =
            Regex(
                "(?i:https)://([A-Za-z0-9.-]+|\\[[0-9A-Fa-f:.]+])(?::([0-9]{1,5}))?" +
                    "((?:/[^\\s\\p{Z}#?]*)?)/j/([0-9a-hjkmnp-tv-z]{8})([^\\s\\p{Z}]*)",
            )
        private val TAIL = Regex("^/?(?:\\?[^#]*)?(?:#(.*))?$")

        fun find(text: String): JamLink? {
            val match = LINK.find(text) ?: return null
            val (host, port, prefix, roomId, rest) = match.destructured
            val tail = TAIL.matchEntire(rest.trimEnd { it in TRAILING_PUNCTUATION }) ?: return null
            val authority = if (port.isEmpty() || port == DEFAULT_PORT) host else "$host:$port"
            return JamLink(
                serverUrl = "https://" + authority.lowercase() + prefix.trimEnd('/'),
                roomId = roomId,
                joinSecret = tail.groupValues[1].takeIf(SECRET::matches),
            )
        }
    }
}

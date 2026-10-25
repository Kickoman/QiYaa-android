package io.github.kickoman.qiyaa.spotify

data class Endpoints(
    val apresolve: String = "https://apresolve.spotify.com",
    val accounts: String = "https://accounts.spotify.com",
    val clientToken: String = "https://clienttoken.spotify.com",
    val login5: String = "https://login5.spotify.com",
    val pathfinder: String = "https://api-partner.spotify.com",
    val spclientScheme: String = "https",
) {
    companion object {
        const val FALLBACK_ACCESS_POINT = "ap.spotify.com:443"
        const val FALLBACK_SPCLIENT = "spclient.wg.spotify.com:443"
    }
}

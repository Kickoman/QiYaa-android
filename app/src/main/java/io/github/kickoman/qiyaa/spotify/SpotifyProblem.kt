package io.github.kickoman.qiyaa.spotify

sealed interface SpotifyProblem {
    data class Http(val status: Int) : SpotifyProblem

    data object Content : SpotifyProblem

    data object QueryOutdated : SpotifyProblem

    data object KeyRefused : SpotifyProblem

    data object KeyFailed : SpotifyProblem

    data object AccessPoint : SpotifyProblem

    data class LoginRefused(val code: Int) : SpotifyProblem
}

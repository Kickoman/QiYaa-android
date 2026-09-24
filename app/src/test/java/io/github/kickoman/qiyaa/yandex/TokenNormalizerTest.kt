package io.github.kickoman.qiyaa.yandex

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenNormalizerTest {
    @Test
    fun normalizesTokens() {
        assertEquals("y0_AgAAAAAtest_token-123", TokenNormalizer.normalize("  y0_AgAAAAAtest_token-123\n"))
        assertEquals("y0_AgAAAAAtest_token", TokenNormalizer.normalize("\"y0_AgAAAAAtest_token\""))
        assertEquals("y0_AgAAAAAtest_token", TokenNormalizer.normalize("{\"access_token\":\"y0_AgAAAAAtest_token\",\"expires_in\":1}"))
        assertEquals("y0_AgAAAAAtest_token", TokenNormalizer.normalize("https://music.yandex.ru/#access_token=y0_AgAAAAAtest_token&token_type=bearer"))
        assertEquals("", TokenNormalizer.normalize(""))
        assertEquals("", TokenNormalizer.normalize(null))
        assertEquals("", TokenNormalizer.normalize("not a token at all"))
        assertEquals("y0_AgAAAAAtest_token", TokenNormalizer.normalize("OAuth y0_AgAAAAAtest_token"))
    }
}

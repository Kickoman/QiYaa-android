package io.github.kickoman.qiyaa.yandex

import org.junit.Assert.assertEquals
import org.junit.Test

class TokenNormalizerTest {
    @Test
    fun `normalize trims a bare token`() {
        assertEquals("y0_AgAAAAAtest_token-123", TokenNormalizer.normalize("  y0_AgAAAAAtest_token-123\n"))
    }

    @Test
    fun `normalize unwraps a JSON string or an object with access_token`() {
        assertEquals("y0_AgAAAAAtest_token", TokenNormalizer.normalize("\"y0_AgAAAAAtest_token\""))
        assertEquals(
            "y0_AgAAAAAtest_token",
            TokenNormalizer.normalize("{\"access_token\":\"y0_AgAAAAAtest_token\",\"expires_in\":1}"),
        )
    }

    @Test
    fun `normalize extracts the token from the redirect URL fragment`() {
        assertEquals(
            "y0_AgAAAAAtest_token",
            TokenNormalizer.normalize(
                "https://music.yandex.ru/#access_token=y0_AgAAAAAtest_token&token_type=bearer",
            ),
        )
    }

    @Test
    fun `normalize strips an OAuth prefix`() {
        assertEquals("y0_AgAAAAAtest_token", TokenNormalizer.normalize("OAuth y0_AgAAAAAtest_token"))
    }

    @Test
    fun `normalize returns an empty string for anything that is not a token`() {
        assertEquals("", TokenNormalizer.normalize(""))
        assertEquals("", TokenNormalizer.normalize(null))
        assertEquals("", TokenNormalizer.normalize("not a token at all"))
    }
}

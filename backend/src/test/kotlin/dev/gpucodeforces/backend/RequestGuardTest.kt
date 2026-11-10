package dev.gpucodeforces.backend

import jakarta.servlet.FilterChain
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.mockito.Mockito

class RequestGuardTest {
    private fun guard(config: PlatformConfig): RequestGuard {
        val live = Mockito.mock(RuntimeSettings::class.java)
        Mockito.`when`(live.current()).thenReturn(SettingsSnapshot(0, RuntimeValues.defaults(config)))
        return RequestGuard(config, live)
    }
    private fun request(method: String = "POST", path: String = "/api/submissions") =
        MockHttpServletRequest(method, path).also { it.addHeader("Origin", "http://localhost:5173") }

    @Test
    fun `caps chunked bodies even without content length`() {
        val guard = guard(PlatformConfig(maxBodyBytes = 8))
        val request = object : MockHttpServletRequest("POST", "/api/submissions") {
            override fun getContentLengthLong() = -1L
            override fun getContentLength() = -1
        }.also { it.addHeader("Origin", "http://localhost:5173"); it.setContent(ByteArray(9)) }
        val response = MockHttpServletResponse()
        guard.doFilter(request, response, FilterChain { _, _ -> error("oversized request reached controller") })
        assertThat(response.status).isEqualTo(413)
    }

    @Test
    fun `enforces origin and bounded rate-limit buckets`() {
        val guard = guard(PlatformConfig(requestsPerMinute = 1, maxRateLimitKeys = 1))
        val first = MockHttpServletResponse()
        guard.doFilter(request(), first, FilterChain { _, _ -> })
        assertThat(first.status).isEqualTo(200)
        val limited = MockHttpServletResponse()
        guard.doFilter(request(), limited, FilterChain { _, _ -> error("rate limit bypass") })
        assertThat(limited.status).isEqualTo(429)
        val otherIp = MockHttpServletResponse()
        guard.doFilter(
            request().also { it.remoteAddr = "127.0.0.2" },
            otherIp,
            FilterChain { _, _ -> error("unbounded rate-limit map") })
        assertThat(otherIp.status).isEqualTo(429)
        val crossOrigin = MockHttpServletResponse()
        guard(PlatformConfig()).doFilter(
            request().also { it.removeHeader("Origin") },
            crossOrigin,
            FilterChain { _, _ -> error("missing origin accepted") })
        assertThat(crossOrigin.status).isEqualTo(403)
    }
}

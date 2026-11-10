package dev.gpucodeforces.backend

import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.time.Duration
import java.util.concurrent.Executors

class BoundedHttpTest {
    @Test
    fun `limits chunked responses and time spent reading after headers`() {
        val executor = Executors.newVirtualThreadPerTaskExecutor()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            this.executor = executor
            createContext("/large") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write(ByteArray(4096)) }
                exchange.close()
            }
            createContext("/slow") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.flush()
                Thread.sleep(1000)
                exchange.close()
            }
            createContext("/small") { exchange ->
                exchange.sendResponseHeaders(200, 0)
                exchange.responseBody.use { it.write("ok".toByteArray()) }
                exchange.close()
            }
            start()
        }
        try {
            val client = HttpClient.newHttpClient()
            fun request(path: String) =
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.address.port}$path")).GET().build()
            assertThat(
                boundedHttp(client, request("/small"), 10, Duration.ofSeconds(5)).body().decodeToString()
            ).isEqualTo("ok")
            assertThatThrownBy { boundedHttp(client, request("/large"), 10, Duration.ofSeconds(5)) }.isInstanceOf(
                IOException::class.java
            )
            assertThatThrownBy { boundedHttp(client, request("/slow"), 10, Duration.ofMillis(100)) }.isInstanceOf(
                IOException::class.java
            )
        } finally {
            server.stop(0); executor.close()
        }
    }
}

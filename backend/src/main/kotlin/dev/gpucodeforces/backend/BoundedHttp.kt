package dev.gpucodeforces.backend

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Bounds both bytes and elapsed time, including bodies sent slowly after the headers. */
fun boundedHttp(client: HttpClient, request: HttpRequest, maxBytes: Int, timeout: Duration): HttpResponse<ByteArray> {
    val future = client.sendAsync(request, HttpResponse.BodyHandler { LimitedBody(maxBytes) })
    return try {
        future.get(timeout.toMillis(), TimeUnit.MILLISECONDS)
    } catch (error: TimeoutException) {
        future.cancel(true); throw IOException("HTTP response timed out", error)
    } catch (error: ExecutionException) {
        throw IOException("HTTP request failed", error.cause)
    } catch (error: InterruptedException) {
        future.cancel(true); Thread.currentThread().interrupt(); throw error
    }
}

private class LimitedBody(private val maxBytes: Int) : HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private lateinit var subscription: Flow.Subscription
    override fun getBody(): CompletionStage<ByteArray> = result
    override fun onSubscribe(value: Flow.Subscription) {
        subscription = value; value.request(1)
    }

    override fun onNext(items: List<ByteBuffer>) {
        for (item in items) {
            if (item.remaining().toLong() + bytes.size() > maxBytes) {
                subscription.cancel()
                result.completeExceptionally(IOException("HTTP response exceeds size limit"))
                return
            }
            val chunk = ByteArray(item.remaining())
            item.get(chunk)
            bytes.write(chunk)
        }
        subscription.request(1)
    }

    override fun onError(error: Throwable) {
        result.completeExceptionally(error)
    }

    override fun onComplete() {
        result.complete(bytes.toByteArray())
    }
}

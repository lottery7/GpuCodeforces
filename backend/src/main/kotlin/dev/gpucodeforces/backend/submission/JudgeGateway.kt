package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.judge.api.SubmissionAccepted
import dev.gpucodeforces.judge.api.SubmissionRequest
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientException
import org.springframework.web.client.RestClientResponseException
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

@ConfigurationProperties("platform.judge")
data class JudgeProperties(
    val gatewayUrl: String = "http://localhost:8090",
    /** A submission the gateway could not take for this long becomes system_error (the author may recheck it). */
    val sendTimeout: Duration = Duration.ofMinutes(10),
    /** How long "check the example" waits for the verdict. */
    val exampleTimeout: Duration = Duration.ofMinutes(2),
    /** A submission accepted by the gateway without any judge event for this long is reported as possibly stuck. */
    val stuckAfter: Duration = Duration.ofMinutes(10),
    /** Unfinished submissions one user may have at a time. */
    val maxPendingPerUser: Int = 5,
)

sealed interface SendOutcome {
    data class Accepted(val jobId: String) : SendOutcome

    /** Gateway or broker unavailable, or the same id is being accepted right now: send again later. */
    data class Retry(val reason: String) : SendOutcome

    /** The gateway will never take this request (400 / 413). */
    data class Rejected(val reason: String) : SendOutcome
}

/** Client of the judge gateway `POST /api/v1/submissions` (judge/docs/openapi.yaml). Verdicts come back via RabbitMQ. */
@Component
class JudgeGateway(properties: JudgeProperties, builder: RestClient.Builder, private val jsonMapper: JsonMapper) {
    private val client = builder
        .baseUrl(properties.gatewayUrl)
        .requestFactory(
            SimpleClientHttpRequestFactory().apply {
                setConnectTimeout(Duration.ofSeconds(5))
                setReadTimeout(Duration.ofSeconds(30))
            },
        )
        .build()

    fun send(request: SubmissionRequest): SendOutcome = try {
        val accepted = client.post().uri("/api/v1/submissions")
            .contentType(MediaType.APPLICATION_JSON)
            // As bytes: the gateway limits request size and answers 411 to a body without Content-Length.
            .body(jsonMapper.writeValueAsBytes(request))
            .retrieve()
            .body(SubmissionAccepted::class.java)!!
        SendOutcome.Accepted(accepted.jobId)
    } catch (exception: RestClientResponseException) {
        val status = exception.statusCode.value()
        if (status == HttpStatus.BAD_REQUEST.value() || status == HttpStatus.CONTENT_TOO_LARGE.value()) {
            SendOutcome.Rejected(exception.responseBodyAsString)
        } else {
            SendOutcome.Retry("gateway answered $status")
        }
    } catch (exception: RestClientException) {
        SendOutcome.Retry(exception.message ?: exception.javaClass.simpleName)
    }
}

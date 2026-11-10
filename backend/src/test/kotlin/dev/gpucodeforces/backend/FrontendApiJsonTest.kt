package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.Submission
import dev.gpucodeforces.backend.api.model.User
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime

class FrontendApiJsonTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `new user keeps explicit null rating fields`() {
        val user = User().id("new_cuda").handle("new_cuda")
        val json = mapper.readTree(mapper.writeValueAsString(user))

        for (name in listOf("rating", "rank", "ratingDelta", "ratingContestId")) {
            assertThat(json.has(name)).isTrue()
            assertThat(json.path(name).isNull).isTrue()
        }
    }

    @Test
    fun `public submission omits owner fields and keeps nullable context`() {
        val submission = Submission()
            .id("1")
            .problemId("vector-addition")
            .problemTitle("Сложение векторов")
            .user(User().id("alex_cuda").handle("alex_cuda"))
            .createdAt(OffsetDateTime.parse("2026-11-09T12:00:00Z"))
            .status(Submission.StatusEnum.QUEUED)
            .comparable(false)
        val json = mapper.readTree(mapper.writeValueAsString(submission))

        assertThat(json.has("code")).isFalse()
        assertThat(json.has("diagnostic")).isFalse()
        assertThat(json.has("contestId")).isTrue()
        assertThat(json.path("contestId").isNull).isTrue()
        assertThat(json.has("timeMs")).isTrue()
        assertThat(json.path("timeMs").isNull).isTrue()
    }
}

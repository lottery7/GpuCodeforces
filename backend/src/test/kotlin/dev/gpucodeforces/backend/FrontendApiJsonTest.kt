package dev.gpucodeforces.backend

import dev.gpucodeforces.backend.api.model.Score
import dev.gpucodeforces.backend.api.model.ScoreType
import dev.gpucodeforces.backend.api.model.Submission
import dev.gpucodeforces.backend.api.model.User
import dev.gpucodeforces.backend.api.model.UserRating
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import java.time.OffsetDateTime

class FrontendApiJsonTest {
    private val mapper = JsonMapper.builder().build()

    @Test
    fun `a rating keeps explicit null fields, a user without ratings an empty object`() {
        val user = User().id("new_cuda").handle("new_cuda").ratings(mapOf("rtl" to UserRating().rating(1500)))
        val json = mapper.readTree(mapper.writeValueAsString(user))

        val rtl = json.path("ratings").path("rtl")
        assertThat(rtl.path("rating").asInt()).isEqualTo(1500)
        for (name in listOf("rank", "ratingDelta", "ratingContestId")) {
            assertThat(rtl.has(name)).isTrue()
            assertThat(rtl.path(name).isNull).isTrue()
        }
        val newcomer = mapper.readTree(mapper.writeValueAsString(User().id("x").handle("x").ratings(emptyMap())))
        assertThat(newcomer.path("ratings").isObject).isTrue()
        assertThat(newcomer.path("ratings").isEmpty).isTrue()
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
            .processor("rtl")
            .language("systemverilog")
            .comparable(false)
        val json = mapper.readTree(mapper.writeValueAsString(submission))

        assertThat(json.has("code")).isFalse()
        assertThat(json.has("diagnostic")).isFalse()
        assertThat(json.has("contestId")).isTrue()
        assertThat(json.path("contestId").isNull).isTrue()
        assertThat(json.has("score")).isTrue()
        assertThat(json.path("score").isNull).isTrue()
    }

    @Test
    fun `a score carries its type`() {
        val json = mapper.readTree(mapper.writeValueAsString(Score(BigDecimal("4096"), ScoreType.CYCLES_X_DEPTH_X_TRANSISTORS)))
        assertThat(json.path("value").decimalValue()).isEqualByComparingTo("4096")
        assertThat(json.path("type").asString()).isEqualTo("CYCLES_X_DEPTH_X_TRANSISTORS")
    }
}

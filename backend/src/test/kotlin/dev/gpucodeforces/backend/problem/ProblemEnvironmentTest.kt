package dev.gpucodeforces.backend.problem

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ProblemEnvironmentTest {
    @Test
    fun `a round limit from a million on is a power of ten`() {
        assertThat(formatLimit(3.5e9)).isEqualTo("3,5·10⁹")
        assertThat(formatLimit(2e10)).isEqualTo("2·10¹⁰")
        assertThat(formatLimit(1e13)).isEqualTo("10¹³")
        assertThat(formatLimit(1.25e6)).isEqualTo("1,25·10⁶")
    }

    @Test
    fun `a smaller or not round limit keeps its digits`() {
        assertThat(formatLimit(50_000.0)).isEqualTo("50000")
        assertThat(formatLimit(1_234_567.0)).isEqualTo("1234567")
        assertThat(formatLimit(2.5)).isEqualTo("2.5")
    }
}

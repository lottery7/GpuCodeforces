package dev.gpucodeforces.backend.problem

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BankCodeOrderTest {
    @Test
    fun `codes go by number, and a code that is not a number after them`() {
        assertThat(listOf("10", "B", "2", "1", "A1", "012", "11").sortedWith(bankCodeOrder))
            .containsExactly("1", "2", "10", "11", "012", "A1", "B")
    }
}

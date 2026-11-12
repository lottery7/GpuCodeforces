package dev.gpucodeforces.backend.common

import dev.gpucodeforces.backend.problem.ProblemVersion
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class ContentLanguageTest {
    @Test
    fun `the header picks the preferred of the two languages, Russian when it names neither`() {
        assertThat(ContentLanguage.of(null)).isEqualTo(ContentLanguage.RU)
        assertThat(ContentLanguage.of("")).isEqualTo(ContentLanguage.RU)
        assertThat(ContentLanguage.of("en")).isEqualTo(ContentLanguage.EN)
        assertThat(ContentLanguage.of("en-US,en;q=0.9,ru;q=0.8")).isEqualTo(ContentLanguage.EN)
        assertThat(ContentLanguage.of("ru-RU,ru;q=0.9,en;q=0.8")).isEqualTo(ContentLanguage.RU)
        assertThat(ContentLanguage.of("de, en;q=0.5, ru;q=0.4")).isEqualTo(ContentLanguage.EN)
        assertThat(ContentLanguage.of("de, fr;q=0.5")).isEqualTo(ContentLanguage.RU)
        assertThat(ContentLanguage.of("*")).isEqualTo(ContentLanguage.RU)
        assertThat(ContentLanguage.of("not a header;;")).isEqualTo(ContentLanguage.RU)
    }

    @Test
    fun `a version imported with one text shows it in both languages`() {
        fun version(titleEn: String?, statementEn: String?) = ProblemVersion(
            id = UUID.randomUUID(), problemId = "sum", code = "1", title = "Сумма", difficulty = "easy", tags = emptyList(), authors = emptyList(),
            draft = false, statementMarkdown = "Сложите числа.", processors = listOf("gpu-cuda"), starters = emptyMap(), judgeSpec = "{}",
            contentHash = "content", judgeHash = "judge", revision = null, titleEn = titleEn, statementMarkdownEn = statementEn,
        )

        val bilingual = version("Sum", "Add the numbers.")
        assertThat(bilingual.title(ContentLanguage.RU)).isEqualTo("Сумма")
        assertThat(bilingual.title(ContentLanguage.EN)).isEqualTo("Sum")
        assertThat(bilingual.statement(ContentLanguage.EN)).isEqualTo("Add the numbers.")
        val old = version(null, null)
        assertThat(old.title(ContentLanguage.EN)).isEqualTo("Сумма")
        assertThat(old.statement(ContentLanguage.EN)).isEqualTo("Сложите числа.")
    }
}

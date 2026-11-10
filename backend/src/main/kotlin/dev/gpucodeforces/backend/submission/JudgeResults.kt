package dev.gpucodeforces.backend.submission

import dev.gpucodeforces.judge.api.JudgeResult
import dev.gpucodeforces.judge.api.Verdict

/** Final status of a judged submission in terms of the frontend contract. */
fun Verdict.toStatus(): SubmissionStatus = when (this) {
    Verdict.ACCEPTED -> SubmissionStatus.ACCEPTED
    Verdict.WRONG_ANSWER -> SubmissionStatus.WRONG_ANSWER
    Verdict.TIME_LIMIT_EXCEEDED -> SubmissionStatus.TIME_LIMIT
    Verdict.MEMORY_LIMIT_EXCEEDED -> SubmissionStatus.MEMORY_LIMIT
    Verdict.RUNTIME_ERROR -> SubmissionStatus.RUNTIME_ERROR
    // Forbidden headers, asm or instructions are found while compiling; the diagnostic says which.
    Verdict.COMPILATION_ERROR, Verdict.POLICY_VIOLATION -> SubmissionStatus.COMPILE_ERROR
    Verdict.INTERNAL_ERROR, Verdict.CANCELLED -> SubmissionStatus.SYSTEM_ERROR
}

/** What the author sees: the judge's own message, compiler output and the first failed test. Safe to show by contract. */
fun JudgeResult.diagnostic(): String = buildList {
    if (verdict == Verdict.POLICY_VIOLATION) add("Нарушены ограничения задачи.")
    message?.takeIf { it.isNotBlank() }?.let(::add)
    compileLog?.takeIf { it.isNotBlank() }?.let(::add)
    tests.firstOrNull { it.verdict != Verdict.ACCEPTED }?.let { test ->
        val name = if (test.sample) "пример" else "тест ${test.index}"
        add(listOfNotNull("${name.replaceFirstChar(Char::uppercase)}: ${test.verdict.label()}", test.message, test.debug).joinToString("\n"))
    }
}.joinToString("\n\n").take(MAX_DIAGNOSTIC_LENGTH)

private fun Verdict.label(): String = when (this) {
    Verdict.ACCEPTED -> "пройден"
    Verdict.WRONG_ANSWER -> "неверный ответ"
    Verdict.TIME_LIMIT_EXCEEDED -> "превышен лимит времени"
    Verdict.MEMORY_LIMIT_EXCEEDED -> "превышен лимит памяти"
    Verdict.RUNTIME_ERROR -> "ошибка выполнения"
    Verdict.COMPILATION_ERROR -> "ошибка компиляции"
    Verdict.POLICY_VIOLATION -> "нарушены ограничения задачи"
    Verdict.INTERNAL_ERROR -> "сбой проверки"
    Verdict.CANCELLED -> "проверка отменена"
}

private const val MAX_DIAGNOSTIC_LENGTH = 16 * 1024

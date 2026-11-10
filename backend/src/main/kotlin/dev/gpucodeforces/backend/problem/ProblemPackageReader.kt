package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.judge.api.CudaSignature
import dev.gpucodeforces.judge.api.GeneratorSpec
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.SpecValidator
import org.springframework.stereotype.Component
import tools.jackson.core.JacksonException
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.dataformat.yaml.YAMLMapper
import tools.jackson.module.kotlin.KotlinModule
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import java.math.BigDecimal

/** A problem package that failed validation; the message is shown in the sync log. */
class ProblemPackageException(message: String) : RuntimeException(message)

/** YAML front matter of `statement.md`. */
data class StatementFrontMatter(
    val code: String,
    val title: String,
    val difficulty: String,
    val tags: List<String> = emptyList(),
    val authors: List<String> = emptyList(),
    val draft: Boolean = false,
)

class ProblemPackage(
    val id: String,
    val frontMatter: StatementFrontMatter,
    val statementMarkdown: String,
    val starterCode: String,
    /** Complete spec as the judge receives it, generator included. */
    val spec: ProblemSpec,
    val contentHash: String,
    val judgeHash: String,
    val format: ProblemFormatData = ProblemFormatData(),
)

/**
 * Reads a problem package directory: `statement.md` (front matter + statement), `problem.json` (judge
 * ProblemSpec without the generator), `problem.py` (generator) and optional `starter.cu`.
 * See docs/backend/problem-package.md.
 */
@Component
class ProblemPackageReader(jsonMapper: JsonMapper) {
    private val specMapper = jsonMapper.rebuild().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build()
    private val yamlMapper = YAMLMapper.builder()
        .addModule(KotlinModule.Builder().build())
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
        .build()

    fun read(directory: Path): ProblemPackage {
        val id = directory.fileName.toString()
        if (!SpecValidator.isValidId(id)) fail("имя каталога должно состоять из латинских букв, цифр, '.', '_' и '-'")
        val statementFile = readFile(directory, STATEMENT_FILE)
        val specFile = readFile(directory, SPEC_FILE)
        val generator = readFile(directory, GENERATOR_FILE)
        val starterFile = directory.resolve(STARTER_FILE).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
        val formatFile = directory.resolve(FORMAT_FILE).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
        val format = readFormat(directory, formatFile, starterFile)

        val (frontMatter, statement) = parseStatement(statementFile)
        val spec = parseSpec(id, specFile, generator)
        val starter = starterFile ?: defaultStarter(spec)
        return ProblemPackage(
            id = id,
            frontMatter = frontMatter,
            statementMarkdown = statement,
            starterCode = starter,
            spec = spec.copy(version = sha256(specFile, generator).take(VERSION_LENGTH)),
            // The generated starter counts too: a better template has to reach problems already in the bank.
            contentHash = if (formatFile == null) sha256(statementFile, specFile, generator, starter)
                else sha256(statementFile, specFile, generator, starter, formatFile, format.firstCode.orEmpty(), format.secondCode.orEmpty()),
            judgeHash = when {
                format.format == ProblemFormatData.GUESS -> sha256(specFile, generator, formatFile!!, format.firstCode!!, format.secondCode!!)
                format.format == ProblemFormatData.OPTIMIZATION && format.benchmark != null ->
                    sha256(specFile, generator, specMapper.writeValueAsString(format.benchmark))
                else -> sha256(specFile, generator)
            },
            format = format,
        )
    }

    private fun readFormat(directory: Path, source: String?, starter: String?): ProblemFormatData {
        if (source == null) return ProblemFormatData()
        val format = try {
            specMapper.readValue(source, ProblemFormatData::class.java)
        } catch (exception: JacksonException) {
            fail("неверный $FORMAT_FILE: ${exception.originalMessage}")
        }
        if (format.format !in ProblemFormatData.FORMATS) fail("неизвестный format в $FORMAT_FILE")
        if (format.firstCode != null || format.secondCode != null) fail("варианты кода задаются файлами first.cu и second.cu")
        if (format.benchmarkSynthetic != null) fail("источник измерений задаёт платформа")
        format.benchmark?.let { benchmark ->
            if (benchmark.inputDescription.isBlank() || benchmark.inputDescription.length > 4000) fail("benchmark.inputDescription должен быть непустым, до 4000 символов")
            if (benchmark.aggregation !in BenchmarkDefinition.AGGREGATIONS) fail("неизвестный benchmark.aggregation")
            if (format.format == ProblemFormatData.STANDARD && benchmark.aggregation != BenchmarkDefinition.MAX) {
                fail("обычные задачи используют benchmark.aggregation=max")
            }
        }
        return when (format.format) {
            ProblemFormatData.OPTIMIZATION -> {
                if (format.baselineTimeMs == null || format.baselineTimeMs <= BigDecimal.ZERO) fail("baselineTimeMs должен быть больше нуля")
                if (starter.isNullOrBlank()) fail("для оптимизации нужен непустой $STARTER_FILE")
                val limit = SpecValidator.Limits().maxSourceBytes
                if (starter.toByteArray().size > limit || starter.contains('\u0000')) fail("$STARTER_FILE содержит нулевые байты или больше $limit байт")
                if (format.firstTimeMs != null || format.secondTimeMs != null) fail("времена вариантов доступны только для guess")
                format
            }
            ProblemFormatData.GUESS -> {
                if (format.explanation.isBlank() || format.explanation.length > 4000) fail("explanation должен быть непустым, до 4000 символов")
                if (format.firstTimeMs == null || format.firstTimeMs <= BigDecimal.ZERO ||
                    format.secondTimeMs == null || format.secondTimeMs <= BigDecimal.ZERO) {
                    fail("firstTimeMs и secondTimeMs должны быть больше нуля")
                }
                if (format.baselineTimeMs != null) fail("baselineTimeMs доступен только для optimization")
                val first = readFile(directory, "first.cu")
                val second = readFile(directory, "second.cu")
                val limit = SpecValidator.Limits().maxSourceBytes
                if (listOf(first, second).any { it.isBlank() || it.toByteArray().size > limit || it.contains('\u0000') }) {
                    fail("варианты кода должны быть непустыми, без нулевых байт, не больше $limit байт")
                }
                format.copy(firstCode = first, secondCode = second)
            }
            else -> {
                if (format.baselineTimeMs != null || format.firstTimeMs != null || format.secondTimeMs != null) {
                    fail("standard не содержит измерений формата")
                }
                format
            }
        }
    }

    private fun parseStatement(content: String): Pair<StatementFrontMatter, String> {
        val match = FRONT_MATTER.find(content.replace("\r\n", "\n"))
            ?: fail("$STATEMENT_FILE должен начинаться с YAML-блока между строками ---")
        val frontMatter = try {
            yamlMapper.readValue<StatementFrontMatter>(match.groupValues[1])
        } catch (exception: JacksonException) {
            fail("неверный YAML-блок в $STATEMENT_FILE: ${exception.originalMessage}")
        }
        with(frontMatter) {
            if (code.isBlank() || code.length > MAX_CODE_LENGTH) fail("code должен быть непустым, до $MAX_CODE_LENGTH символов")
            if (title.isBlank() || title.length > MAX_TITLE_LENGTH) fail("title должен быть непустым, до $MAX_TITLE_LENGTH символов")
            if (difficulty !in DIFFICULTIES) fail("difficulty должен быть одним из $DIFFICULTIES")
            if (tags.any { it.isBlank() }) fail("tags не должны быть пустыми")
            if (authors.any { it.isBlank() }) fail("authors должны быть никами пользователей")
        }
        val statement = match.groupValues[2].trim()
        if (statement.isEmpty()) fail("условие в $STATEMENT_FILE пустое")
        return frontMatter to statement
    }

    private fun parseSpec(id: String, specFile: String, generator: String): ProblemSpec {
        val tree = try {
            specMapper.readTree(specFile) as? ObjectNode ?: fail("$SPEC_FILE должен содержать объект")
        } catch (exception: JacksonException) {
            fail("неверный JSON в $SPEC_FILE: ${exception.originalMessage}")
        }
        if (tree.has("generator")) fail("генератор задаётся файлом $GENERATOR_FILE, а не полем generator")
        // judge_cli.py needs an id in problem.json; the backend always uses the directory name.
        tree.remove("id")
        tree.put("id", id)
        tree.putPOJO("generator", GeneratorSpec(source = generator))
        val spec = try {
            specMapper.treeToValue(tree, ProblemSpec::class.java)
        } catch (exception: JacksonException) {
            fail("неверный $SPEC_FILE: ${exception.originalMessage}")
        }
        val errors = SpecValidator.validateProblem(spec)
        if (errors.isNotEmpty()) fail("задача не проходит проверку судьи: ${errors.joinToString("; ")}")
        return spec
    }

    private fun defaultStarter(spec: ProblemSpec): String {
        val includes = (listOf("cuda_runtime.h") + CudaSignature.typeIncludes(spec.signature))
            .joinToString("\n") { "#include <$it>" }
        val pointers = spec.signature.arrays.joinToString(", ") {
            "${it.name} — ${if (it.memory == MemorySpace.HOST) "память CPU" else "память GPU"}"
        }
        // Contestants used blockIdx/threadIdx right in solve; the template says where the kernel goes.
        return """
            |$includes
            |
            |__global__ void kernel(/* параметры */) {
            |    // Вычисления на GPU: blockIdx, blockDim и threadIdx доступны только здесь.
            |}
            |
            |// solve выполняется на CPU: запустите из неё ядро, kernel<<<блоки, потоки>>>(...).
            |// Массивы: $pointers.
            |${CudaSignature.declaration(spec.signature)} {
            |}
            |
        """.trimMargin()
    }

    private fun readFile(directory: Path, name: String): String {
        val file = directory.resolve(name)
        if (!Files.isRegularFile(file)) fail("нет файла $name")
        return Files.readString(file)
    }

    private fun fail(message: String): Nothing = throw ProblemPackageException(message)

    private fun sha256(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        parts.forEach {
            digest.update(it.toByteArray())
            digest.update(0)
        }
        return HexFormat.of().formatHex(digest.digest())
    }

    companion object {
        const val STATEMENT_FILE = "statement.md"
        const val SPEC_FILE = "problem.json"
        const val GENERATOR_FILE = "problem.py"
        const val STARTER_FILE = "starter.cu"
        const val FORMAT_FILE = "format.json"
        val DIFFICULTIES = listOf("easy", "medium", "hard")
        private const val MAX_CODE_LENGTH = 16
        private const val MAX_TITLE_LENGTH = 200
        private const val VERSION_LENGTH = 12
        private val FRONT_MATTER = Regex("\\A---\\n(.*?)\\n---\\n?(.*)\\z", RegexOption.DOT_MATCHES_ALL)
    }
}

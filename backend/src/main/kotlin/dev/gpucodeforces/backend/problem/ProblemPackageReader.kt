package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.judge.api.CSignature
import dev.gpucodeforces.judge.api.CudaSignature
import dev.gpucodeforces.judge.api.GeneratorSpec
import dev.gpucodeforces.judge.api.Languages
import dev.gpucodeforces.judge.api.MemorySpace
import dev.gpucodeforces.judge.api.ProblemSpec
import dev.gpucodeforces.judge.api.ProcessorSpec
import dev.gpucodeforces.judge.api.Processors
import dev.gpucodeforces.judge.api.RtlSettings
import dev.gpucodeforces.judge.api.RtlSignature
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
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

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
    /** Processor -> language -> starter code, for every language of every processor the problem declares. */
    val starters: Map<String, Map<String, String>>,
    /** Complete spec as the judge receives it, generator included. */
    val spec: ProblemSpec,
    val contentHash: String,
    val judgeHash: String,
    val format: ProblemFormatData = ProblemFormatData(),
)

/**
 * Reads a problem package directory: `statement.md` (front matter + statement), `problem.json` (judge
 * ProblemSpec without the generator), `problem.py` (generator) and optional starters `starter.<processor><ext>`
 * (`starter.rtl.sv`), where the older `starter.cu` stands for the CUDA processors. See docs/backend/problem-package.md.
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
        val formatFile = directory.resolve(FORMAT_FILE).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }

        val (frontMatter, statement) = parseStatement(statementFile)
        val spec = parseSpec(id, specFile, generator)
        val format = readFormat(directory, formatFile, spec)
        val starters = readStarters(directory, spec)
        if (format.format == ProblemFormatData.OPTIMIZATION) checkBaselines(directory, spec, starters)
        return ProblemPackage(
            id = id,
            frontMatter = frontMatter,
            statementMarkdown = statement,
            starters = starters,
            spec = spec.copy(version = sha256(specFile, generator).take(VERSION_LENGTH)),
            // The generated starters count too: a better template has to reach problems already in the bank.
            contentHash = if (formatFile == null) {
                sha256(statementFile, specFile, generator, *starterHashParts(starters).toTypedArray())
            } else {
                sha256(
                    statementFile, specFile, generator, *starterHashParts(starters).toTypedArray(), formatFile, format.firstCode.orEmpty(),
                    format.secondCode.orEmpty(),
                )
            },
            judgeHash = when {
                format.format == ProblemFormatData.GUESS -> sha256(specFile, generator, formatFile!!, format.firstCode!!, format.secondCode!!)
                format.format == ProblemFormatData.OPTIMIZATION -> sha256(
                    specFile, generator, *baselines(spec, starters).toTypedArray(), format.benchmark?.let(specMapper::writeValueAsString).orEmpty(),
                )
                else -> sha256(specFile, generator)
            },
            format = format,
        )
    }

    private fun readFormat(directory: Path, source: String?, spec: ProblemSpec): ProblemFormatData {
        if (source == null) return ProblemFormatData()
        val format = try {
            val tree = specMapper.readTree(source) as? ObjectNode ?: fail("$FORMAT_FILE должен содержать объект")
            listOf("baselineTimeMs", "firstTimeMs", "secondTimeMs").firstOrNull(tree::has)?.let {
                fail("$it нельзя задавать в $FORMAT_FILE: время измеряет платформа")
            }
            if (tree.has("benchmarkSynthetic")) fail("источник измерений задаёт платформа")
            specMapper.treeToValue(tree, ProblemFormatData::class.java)
        } catch (exception: JacksonException) {
            fail("неверный $FORMAT_FILE: ${exception.originalMessage}")
        }
        if (format.format !in ProblemFormatData.FORMATS) fail("неизвестный format в $FORMAT_FILE")
        if (format.firstCode != null || format.secondCode != null) fail("варианты кода задаются файлами first и second с расширением языка")
        format.benchmark?.let { benchmark ->
            if (benchmark.inputDescription.isBlank() || benchmark.inputDescription.length > 4000) fail("benchmark.inputDescription должен быть непустым, до 4000 символов")
            if (benchmark.aggregation !in BenchmarkDefinition.AGGREGATIONS) fail("неизвестный benchmark.aggregation")
            if (format.format == ProblemFormatData.STANDARD && benchmark.aggregation != BenchmarkDefinition.MAX) {
                fail("обычные задачи используют benchmark.aggregation=max")
            }
        }
        return when (format.format) {
            ProblemFormatData.GUESS -> {
                if (format.explanation.isBlank() || format.explanation.length > 4000) fail("explanation должен быть непустым, до 4000 символов")
                // both variants run on the one processor and language of the problem
                val processor = spec.declaredProcessors().singleOrNull()?.takeIf { it.languages.size == 1 }
                    ?: fail("угадывание сравнивает варианты на одном процессоре и одном языке: в problem.json нужен ровно один процессор с одним языком")
                val extension = Processors.get(processor.id).language(processor.languages.single())!!.extension
                val first = readFile(directory, "first$extension")
                val second = readFile(directory, "second$extension")
                val limit = SpecValidator.Limits().maxSourceBytes
                if (listOf(first, second).any { it.isBlank() || it.toByteArray().size > limit || it.contains('\u0000') }) {
                    fail("варианты кода должны быть непустыми, без нулевых байт, не больше $limit байт")
                }
                format.copy(firstCode = first, secondCode = second)
            }
            else -> format
        }
    }

    /**
     * An optimization measures its baseline on every processor: the starter of the processor's first language, which
     * the package must provide itself (its own file, or `starter.cu` for CUDA), not the empty one built from the signature.
     */
    private fun checkBaselines(directory: Path, spec: ProblemSpec, starters: Map<String, Map<String, String>>) {
        val limit = SpecValidator.Limits().maxSourceBytes
        for (processor in spec.declaredProcessors()) {
            val language = processor.languages.first()
            val name = starterFile(processor.id, Processors.get(processor.id).language(language)!!.extension)
            val own = Files.isRegularFile(directory.resolve(name)) || language == Languages.CUDA && Files.isRegularFile(directory.resolve(LEGACY_STARTER_FILE))
            val expected = if (language == Languages.CUDA) "$LEGACY_STARTER_FILE или $name" else name
            if (!own) fail("для оптимизации нужен рабочий шаблон $expected: с него измеряется исходный результат на процессоре ${processor.id}")
            val code = starters.getValue(processor.id).getValue(language)
            if (code.isBlank() || code.toByteArray().size > limit || code.contains('\u0000')) {
                fail("шаблон ${if (language == Languages.CUDA) LEGACY_STARTER_FILE else name} пустой, содержит нулевые байты или больше $limit байт")
            }
        }
    }

    /**
     * The baseline sources of an optimization, in the order of the processors. A package on the real GPU alone hashes
     * as before processors existed, so it gets no new version for them.
     */
    private fun baselines(spec: ProblemSpec, starters: Map<String, Map<String, String>>): List<String> {
        val processors = spec.declaredProcessors()
        val single = processors.singleOrNull()?.takeIf { it.id == Processors.GPU_CUDA }
        if (single != null) return listOf(starters.getValue(single.id).getValue(single.languages.first()))
        return processors.flatMap { listOf(it.id, starters.getValue(it.id).getValue(it.languages.first())) }
    }

    /**
     * A starter for every language of every declared processor: its own file, else `starter.cu` for CUDA, else one
     * built from the signature. A starter file no processor of the problem uses is an error, not silently ignored.
     */
    private fun readStarters(directory: Path, spec: ProblemSpec): Map<String, Map<String, String>> {
        val legacy = directory.resolve(LEGACY_STARTER_FILE).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
        val expected = mutableSetOf<String>()
        val starters = spec.declaredProcessors().associate { processor ->
            val info = Processors.get(processor.id)
            processor.id to processor.languages.associateWith { language ->
                val name = starterFile(processor.id, info.language(language)!!.extension)
                expected += name
                directory.resolve(name).takeIf { Files.isRegularFile(it) }?.let { Files.readString(it) }
                    ?: legacy.takeIf { language == Languages.CUDA }
                    ?: defaultStarter(spec, processor, language)
            }
        }
        val strays = directory.listDirectoryEntries("starter.*").map { it.name }.filter { it != LEGACY_STARTER_FILE && it !in expected }
        if (strays.isNotEmpty()) {
            fail("файлы ${strays.sorted().joinToString()} не относятся ни к одному процессору и языку задачи; ожидаются ${expected.sorted().joinToString()}")
        }
        return starters
    }

    /** A package with only the CUDA starter hashes as before processors existed, so it gets no new version for them. */
    private fun starterHashParts(starters: Map<String, Map<String, String>>): List<String> {
        val single = starters[Processors.GPU_CUDA]?.get(Languages.CUDA)
        if (starters.size == 1 && starters.values.single().size == 1 && single != null) return listOf(single)
        return starters.toSortedMap().flatMap { (processor, byLanguage) ->
            byLanguage.toSortedMap().flatMap { (language, code) -> listOf("$processor/$language", code) }
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

    private fun defaultStarter(spec: ProblemSpec, processor: ProcessorSpec, language: String): String = when (language) {
        Languages.CUDA -> cudaStarter(spec)
        Languages.SYSTEMVERILOG -> RtlSignature.template(spec.signature, processor.rtl ?: RtlSettings())
        Languages.C, Languages.CPP -> {
            val includes = CSignature.includes(processor.id, cpp = language == Languages.CPP).joinToString("\n") { "#include <$it>" }
            "$includes\n\n// Массивы лежат в памяти процессора.\n${CSignature.declaration(spec.signature)} {\n}\n"
        }
        Languages.ASM -> """
            |# Решение на ассемблере RISC-V. Функция на C:
            |#   ${CSignature.declaration(spec.signature)}
            |# Аргументы приходят по соглашению о вызовах RISC-V: a0, a1, ... по порядку.
            |    .text
            |    .globl ${spec.signature.function}
            |${spec.signature.function}:
            |    ret
            |
        """.trimMargin()
        // the validator admits only the languages of the catalog, all of them handled above
        else -> error("no default starter for language $language")
    }

    private fun cudaStarter(spec: ProblemSpec): String {
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
        /**
         * The starter of CUDA before processors existed: still read for `gpu-cuda` and `cuda-sim`, and the code an
         * optimization starts from.
         */
        const val LEGACY_STARTER_FILE = "starter.cu"
        const val FORMAT_FILE = "format.json"

        fun starterFile(processor: String, extension: String) = "starter.$processor$extension"
        val DIFFICULTIES = listOf("easy", "medium", "hard")
        private const val MAX_CODE_LENGTH = 16
        private const val MAX_TITLE_LENGTH = 200
        private const val VERSION_LENGTH = 12
        private val FRONT_MATTER = Regex("\\A---\\n(.*?)\\n---\\n?(.*)\\z", RegexOption.DOT_MATCHES_ALL)
    }
}

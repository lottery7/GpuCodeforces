package dev.gpucodeforces.backend.problem

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.judge.api.ProblemSpec
import org.springframework.stereotype.Service
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/** Read access to the bank shared by the problem, contest and submission modules. */
@Service
class ProblemCatalog(
    private val versionRepository: ProblemVersionRepository,
    private val problemRepository: ProblemRepository,
    private val jsonMapper: JsonMapper,
) {
    fun currentVersion(problemId: String): ProblemVersion? =
        problemRepository.findById(problemId).orElse(null)?.currentVersionId?.let { versionRepository.findById(it).orElse(null) }

    fun versions(ids: Collection<UUID>): Map<UUID, ProblemVersion> = versionRepository.findAllById(ids).associateBy { it.id }

    fun spec(version: ProblemVersion): ProblemSpec = jsonMapper.readValue(version.judgeSpec, ProblemSpec::class.java)

    /** Contests take only published problems from the bank; returns the versions in the given order. */
    fun publishedVersions(problemIds: Collection<String>): List<ProblemVersion> = problemIds.map { id ->
        currentVersion(id)?.takeUnless { it.draft } ?: throw BadRequestException("Задача $id не найдена в банке")
    }
}

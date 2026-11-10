package com.example.gpuplatform.integration

import com.example.gpuplatform.config.ProblemsProperties
import com.example.gpuplatform.problem.repository.ProblemRepository
import com.example.gpuplatform.sync.domain.SyncStatus
import com.example.gpuplatform.sync.repository.SyncStateRepository
import com.example.gpuplatform.sync.service.GitRepositoryService
import com.example.gpuplatform.sync.service.ProblemImportService
import com.example.gpuplatform.sync.service.ProblemSyncService
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.jgit.api.Git
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import java.nio.file.Files
import java.nio.file.Path

class ProblemSyncServiceIntegrationTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var syncStateRepository: SyncStateRepository

    @Autowired
    lateinit var importService: ProblemImportService

    @Autowired
    lateinit var problemRepository: ProblemRepository

    private fun problemMd(title: String): String = """
        ---
        title: $title
        tags:
          - EASY
        author: platform
        ---

        # $title

        Body text.
    """.trimIndent()

    private fun buildService(localDir: Path, repositoryUrl: String): ProblemSyncService {
        val props = ProblemsProperties(
            git = ProblemsProperties.Git(
                repositoryUrl = repositoryUrl,
                branch = "main",
                localDirectory = localDir.toString(),
            )
        )
        return ProblemSyncService(
            gitRepositoryService = GitRepositoryService(props),
            problemImportService = importService,
            syncStateRepository = syncStateRepository,
        )
    }

    private fun writeAndCommit(root: Path, title: String, message: String) {
        val file = root.resolve("problems/vector-addition/problem.md")
        Files.createDirectories(file.parent)
        Files.writeString(file, problemMd(title))

        val alreadyRepo = root.resolve(".git").toFile().exists()
        val git: Git =
            if (alreadyRepo) {
                Git.open(root.toFile())
            } else {
                Git.init().setInitialBranch("main").setDirectory(root.toFile()).call()
            }
        git.use {
            it.repository.config.setString("user", null, "name", "test")
            it.repository.config.setString("user", null, "email", "test@example.com")
            it.add().addFilepattern(".").call()
            it.commit().setMessage(message).call()
        }
    }

    @Test
    fun `clones repo imports problems and tracks status`() {
        val sourceRoot = Files.createTempDirectory("git-source")
        writeAndCommit(sourceRoot, "Vector Addition", "initial")

        val localDir = Files.createTempDirectory("git-target")
        val service = buildService(localDir, sourceRoot.toUri().toString())

        val result = service.triggerSync()
        assertThat(result.status).isEqualTo(SyncStatus.SUCCESS)
        assertThat(result.imported).isEqualTo(1)

        val problem = problemRepository.findBySlug("vector-addition")
        assertThat(problem).isNotNull()
        assertThat(problem!!.title).isEqualTo("Vector Addition")
        assertThat(problem.gitCommitSha).isEqualTo(result.lastImportedSha)

        val state = service.currentState()
        assertThat(state.status).isEqualTo(SyncStatus.SUCCESS)
        assertThat(state.lastImportedSha).isNotNull()

        // Unchanged -> skipped, no DB write.
        val second = service.triggerSync()
        assertThat(second.unchanged).isEqualTo(1)
        assertThat(problemRepository.findBySlug("vector-addition")!!.title).isEqualTo("Vector Addition")

        // Change + commit -> updated.
        writeAndCommit(sourceRoot, "Vector Addition v2", "update")
        val third = service.triggerSync()
        assertThat(third.updated).isEqualTo(1)
        assertThat(problemRepository.findBySlug("vector-addition")!!.title).isEqualTo("Vector Addition v2")
    }

    @Test
    fun `sync failure records failed status`() {
        val localDir = Files.createTempDirectory("git-target")
        val service = buildService(localDir, "file:///nonexistent/repo")

        val result = service.triggerSync()
        assertThat(result.status).isEqualTo(SyncStatus.FAILED)
        assertThat(result.errorMessage).isNotBlank()
        assertThat(service.currentState().status).isEqualTo(SyncStatus.FAILED)
    }
}
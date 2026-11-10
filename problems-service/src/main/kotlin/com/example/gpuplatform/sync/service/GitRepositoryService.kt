package com.example.gpuplatform.sync.service

import com.example.gpuplatform.config.ProblemsProperties
import com.example.gpuplatform.sync.domain.ProblemSource
import com.example.gpuplatform.sync.domain.RepositorySnapshot
import com.example.gpuplatform.sync.domain.Slugs
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.revwalk.RevWalk
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

/**
 * Clones / fetches the configured problems Git repository and scans it for
 * files matching `problems/<slug>/problem.md`. The repository URL and branch
 * always come from server configuration — never from an incoming request.
 */
@Service
class GitRepositoryService(private val props: ProblemsProperties) {

    private val log = LoggerFactory.getLogger(GitRepositoryService::class.java)

    /**
     * Ensures the local repository exists and is updated to the configured
     * branch, then returns a [RepositorySnapshot] with the current commit SHA
     * and the discovered problem sources.
     */
    fun obtainSnapshot(): RepositorySnapshot {
        val localDir = File(props.git.localDirectory).toPath().toAbsolutePath().normalize()
        val commitSha =
            if (Files.exists(localDir.resolve(".git"))) {
                log.info("Repository exists at '{}': fetching latest from '{}' (branch '{}')",
                    localDir, props.git.repositoryUrl, props.git.branch)
                updateExistingRepository(localDir)
            } else {
                log.info("Cloning repository '{}' branch '{}' into '{}' (this is the slow step)",
                    props.git.repositoryUrl, props.git.branch, localDir)
                cloneRepository(localDir)
            }
        log.info("Repository is at commit '{}'", commitSha)
        val problemsDir = localDir.resolve(props.git.problemsDirectory)
        val sources = discoverProblems(problemsDir)
        log.info("Scanning '{}': discovered {} problem file(s)", problemsDir, sources.size)
        return RepositorySnapshot(commitSha = commitSha, problemSources = sources)
    }

    private fun cloneRepository(localDir: Path): String {
        Files.createDirectories(localDir)
        Git.cloneRepository()
            .setURI(props.git.repositoryUrl)
            .setDirectory(localDir.toFile())
            .setBranch(props.git.branch)
            .setRemote("origin")
            .call()
            .use {
                return headCommitSha(it)
            }
    }

    private fun updateExistingRepository(localDir: Path): String {
        Git.open(localDir.toFile()).use { git ->
            git.fetch().setRemote("origin").setForceUpdate(true).call()

            val remoteBranch = "origin/${props.git.branch}"
            val ref = git.repository.findRef("refs/remotes/$remoteBranch")
                ?: throw IllegalStateException(
                    "Remote branch '$remoteBranch' not found in repository",
                )

            // Detached checkout of the fetched remote branch commit; avoids
            // local branch-name collisions and dirty working-tree issues.
            git.checkout().setName("refs/remotes/$remoteBranch").call()
            return headCommitSha(git)
        }
    }

    private fun discoverProblems(problemsDir: Path): List<ProblemSource> {
        val base = problemsDir.toFile()
        if (!base.isDirectory) {
            return emptyList()
        }
        return base.listFiles { f -> f.isDirectory }
            ?.sortedBy { it.name }
            ?.filter { dir ->
                // Only directories that directly contain a problem.md file.
                dir.listFiles { f -> f.isFile && f.name == "problem.md" }?.isNotEmpty() == true
            }
            ?.map { dir ->
                ProblemSource(
                    slug = Slugs.deriveSlug(dir.name),
                    absolutePath = File(dir, "problem.md").absolutePath,
                )
            }
            .orEmpty()
    }

    private fun headCommitSha(git: Git): String {
        val head = git.repository.resolve("HEAD") ?: throw IllegalStateException("No HEAD commit")
        return RevWalk(git.repository).use { rw ->
            rw.parseCommit(head).name
        }
    }
}
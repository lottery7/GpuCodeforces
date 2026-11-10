package com.example.gpuplatform.sync.service

import com.example.gpuplatform.config.ProblemsProperties
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Runs an initial synchronization on startup when configured via
 * `PROBLEMS_SYNC_ON_STARTUP=true` and a repository URL is set.
 */
@Component
class StartupSyncRunner(
    private val props: ProblemsProperties,
    private val syncService: ProblemSyncService,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(StartupSyncRunner::class.java)

    override fun run(args: ApplicationArguments) {
        if (props.git.syncOnStartup && props.git.repositoryUrl.isNotBlank()) {
            log.info("Running startup synchronization")
            syncService.triggerSync()
        }
    }
}
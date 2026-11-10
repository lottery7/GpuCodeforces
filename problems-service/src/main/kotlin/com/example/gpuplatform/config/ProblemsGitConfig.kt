package com.example.gpuplatform.config

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "app.problems")
data class ProblemsProperties(
    val git: Git = Git(),
    val internalApiKey: String = "",
    val githubWebhookSecret: String = "",
) {
    data class Git(
        val repositoryUrl: String = "",
        val branch: String = "main",
        val localDirectory: String = "./data/problems-repo",
        val problemsDirectory: String = "problems",
        val syncOnStartup: Boolean = false,
    )
}
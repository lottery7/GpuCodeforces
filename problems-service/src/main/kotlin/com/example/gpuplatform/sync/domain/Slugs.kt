package com.example.gpuplatform.sync.domain

/**
 * Slug derivation rules for problem directories.
 *
 * The slug is the problem directory name (e.g. `vector-addition`). `deriveSlug`
 * validates the name so an invalid directory is rejected early.
 */
object Slugs {

    private val slugPattern = Regex("^[a-z0-9]+(?:-[a-z0-9]+)*$")

    fun deriveSlug(directoryName: String): String {
        val name = directoryName.trim()
        require(name.isNotBlank()) { "Problem directory name must not be blank" }
        require(slugPattern.matches(name)) {
            "Invalid problem slug '$name': must be lowercase letters, digits and single hyphens"
        }
        return name
    }
}
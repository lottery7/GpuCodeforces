package com.example.gpuplatform.integration

import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer

/**
 * Base class for integration tests. A single PostgreSQL container is shared by
 * all integration tests in this JVM to keep resource usage low and avoid
 * exhausting Docker resources when several test classes run together.
 */
@SpringBootTest
abstract class AbstractIntegrationTest {

    companion object {
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("gpuproblems")
                .withUsername("gpu")
                .withPassword("gpu")
                .also { it.start() }

        @JvmStatic
        @DynamicPropertySource
        fun databaseProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("app.problems.git.sync-on-startup") { false }
            registry.add("spring.datasource.hikari.connection-timeout") { 60000 }
        }
    }
}
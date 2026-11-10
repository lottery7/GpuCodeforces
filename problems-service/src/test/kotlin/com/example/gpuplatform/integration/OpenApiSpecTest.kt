package com.example.gpuplatform.integration

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.nio.file.Path

/**
 * Generates the OpenAPI document WITHOUT starting the embedded HTTP server.
 *
 * springdoc exposes the spec at `/v3/api-docs`. Imbue the endpoint through
 * Spring's in-memory MockMvc (the full context boots, but no server listens)
 * and writes the JSON to `build/openapi/openapi.json`.
 *
 * Run:  ./gradlew test --tests "*OpenApiSpecTest*"
 */
@AutoConfigureMockMvc
class OpenApiSpecTest : AbstractIntegrationTest() {

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `writes openapi json without starting the server`() {
        val json = mockMvc.perform(get("/v3/api-docs"))
            .andExpect(status().isOk)
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
            .andReturn()
            .response
            .contentAsString

        val out = Path.of("build", "openapi", "openapi.json")
        Files.createDirectories(out.parent)
        Files.writeString(out, json)

        println("OpenAPI spec written to: $out")
    }
}
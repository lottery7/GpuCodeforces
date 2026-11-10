package com.example.gpuplatform.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityRequirement
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.Components
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun gpuPlatformOpenApi(): OpenAPI {
        val apiKeyScheme =
            SecurityScheme()
                .type(SecurityScheme.Type.APIKEY)
                .name("X-Internal-Key")
                .`in`(SecurityScheme.In.HEADER)
                .description("Optional internal API key for admin endpoints")

        return OpenAPI()
            .info(
                Info()
                    .title("GPU Programming Challenge Platform API")
                    .description(
                        "Backend for a Codeforces-like platform hosting GPU " +
                            "programming problems. Provides the problem catalog and " +
                            "Git-based content synchronization. Submission, compilation " +
                            "and judging are out of scope for now."
                    )
                    .version("v1")
            )
            .components(Components().addSecuritySchemes("internalApiKey", apiKeyScheme))
            .addSecurityItem(SecurityRequirement().addList("internalApiKey"))
    }
}
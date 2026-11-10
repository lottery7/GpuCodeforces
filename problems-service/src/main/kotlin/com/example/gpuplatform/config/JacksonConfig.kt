package com.example.gpuplatform.config

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

@Configuration
class JacksonConfig {

    /**
     * Primary JSON mapper used for the REST API. Defined explicitly because the
     * presence of another ObjectMapper bean (the YAML one below) would otherwise
     * suppress Spring Boot's auto-configured JSON mapper.
     */
    @Bean
    @Primary
    fun jsonObjectMapper(): ObjectMapper =
        ObjectMapper()
            .registerKotlinModule()
            .registerModule(JavaTimeModule())
            .setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /**
     * Dedicated YAML mapper used to parse problem front matter. It maps YAML
     * snake_case keys onto camelCase Kotlin properties and tolerates unknown
     * keys so metadata stays extensible. No polymorphic default typing is
     * enabled, which avoids unsafe YAML object deserialization.
     */
    @Bean("problemYamlMapper")
    fun problemYamlMapper(): ObjectMapper =
        ObjectMapper(YAMLFactory())
            .registerKotlinModule()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
}
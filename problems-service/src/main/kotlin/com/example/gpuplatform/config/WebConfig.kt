package com.example.gpuplatform.config

import com.example.gpuplatform.sync.api.InternalApiInterceptor
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

@Configuration
class WebConfig(
    private val internalApiInterceptor: InternalApiInterceptor,
) : WebMvcConfigurer {

    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(internalApiInterceptor)
            .addPathPatterns("/api/internal/**")
    }
}
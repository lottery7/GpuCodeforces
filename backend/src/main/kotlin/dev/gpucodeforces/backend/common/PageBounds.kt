package dev.gpucodeforces.backend.common

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Bounds of a page as the contract gives them to every listing (`Offset` and `Limit` of openapi.yaml). The generated
 * interfaces carry them as Bean Validation constraints, but those are not applied here: a Kotlin controller takes
 * `Int`, and Bean Validation does not treat that method as an override of the interface's one with `Integer`.
 * Unchecked, `limit` is whatever the client asks for - the whole table of a public listing included.
 */
class PageBoundsInterceptor : HandlerInterceptor {
    override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
        // a value that is not a number is refused later, when the parameter is converted
        val offset = request.getParameter("offset")?.toIntOrNull()
        val limit = request.getParameter("limit")?.toIntOrNull()
        if (offset != null && offset < 0 || limit != null && limit !in 1..MAX_LIMIT) throw BadRequestException("Неверный запрос")
        return true
    }

    private companion object {
        const val MAX_LIMIT = 100
    }
}

@Configuration
class PageBoundsConfiguration : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(PageBoundsInterceptor()).addPathPatterns("/api/**")
    }
}

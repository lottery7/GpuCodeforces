package dev.gpucodeforces.backend.common

import org.springframework.http.HttpHeaders
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.util.Locale

/** Language of the texts a problem author writes in two languages: the title, the statement, the texts of a format. */
enum class ContentLanguage {
    RU,
    EN,
    ;

    companion object {
        private val OFFERED = listOf(Locale.of("ru"), Locale.of("en"))

        /** The language the current request asks for in `Accept-Language`; Russian without the header or outside a request. */
        fun current(): ContentLanguage {
            val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request ?: return RU
            return of(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE))
        }

        /** The offered language [header] prefers: `en-GB, ru;q=0.5` is English. Anything else is Russian. */
        fun of(header: String?): ContentLanguage {
            if (header.isNullOrBlank()) return RU
            val ranges = try {
                Locale.LanguageRange.parse(header)
            } catch (_: IllegalArgumentException) {
                // HTTP tells a server to carry on without a header it cannot read
                return RU
            }
            return if (Locale.lookup(ranges, OFFERED)?.language == "en") EN else RU
        }
    }
}

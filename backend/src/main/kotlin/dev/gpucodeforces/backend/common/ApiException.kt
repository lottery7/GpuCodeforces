package dev.gpucodeforces.backend.common

import org.springframework.http.HttpStatus

/** Maps to the `{message}` error body shared by every endpoint in the frontend contract. */
sealed class ApiException(message: String, val status: HttpStatus) : RuntimeException(message)

class BadRequestException(message: String) : ApiException(message, HttpStatus.BAD_REQUEST)

class UnauthorizedException(message: String) : ApiException(message, HttpStatus.UNAUTHORIZED)

class ForbiddenException(message: String) : ApiException(message, HttpStatus.FORBIDDEN)

class NotFoundApiException(message: String) : ApiException(message, HttpStatus.NOT_FOUND)

package dev.gpucodeforces.backend.common

import java.util.UUID

/** Path/resource ids that don't parse are "not found", not "bad request": the URL just doesn't match anything. */
fun String.toUuidOrNotFound(message: String): UUID =
    runCatching { UUID.fromString(this) }.getOrNull() ?: throw NotFoundApiException(message)

/** Ids referenced inside a request body that don't parse are a validation problem with that body. */
fun String.toUuidOrBadRequest(message: String): UUID =
    runCatching { UUID.fromString(this) }.getOrNull() ?: throw BadRequestException(message)

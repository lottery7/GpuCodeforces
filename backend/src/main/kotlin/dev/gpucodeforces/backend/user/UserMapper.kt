package dev.gpucodeforces.backend.user

import dev.gpucodeforces.backend.api.model.User as UserApiModel

fun User.toApiModel(): UserApiModel = UserApiModel(id.toString(), handle, rating, rank, ratingDelta, ratingContestId?.toString())

package dev.gpucodeforces.backend.user

import dev.gpucodeforces.backend.api.model.User as UserApiModel
import dev.gpucodeforces.backend.api.model.UserRating as UserRatingApiModel

fun User.toApiModel(): UserApiModel = UserApiModel(
    id.toString(),
    handle,
    ratings.mapValues { (_, it) -> UserRatingApiModel(it.rating, it.rank, it.ratingDelta, it.ratingContestId?.toString()) },
)

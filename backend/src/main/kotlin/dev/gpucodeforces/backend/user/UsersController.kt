package dev.gpucodeforces.backend.user

import dev.gpucodeforces.backend.api.UsersApi
import dev.gpucodeforces.backend.api.model.GetUserProfile200Response
import dev.gpucodeforces.backend.api.model.ListUsers200Response
import dev.gpucodeforces.backend.api.model.RatingHistoryPoint
import dev.gpucodeforces.backend.api.model.RegisterUserRequest
import dev.gpucodeforces.backend.auth.SessionIssuer
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import dev.gpucodeforces.backend.api.model.User as UserApiModel

@RestController
class UsersController(
    private val userService: UserService,
    private val profileService: ProfileService,
    private val sessionIssuer: SessionIssuer,
) : UsersApi {
    override fun registerUser(registerUserRequest: RegisterUserRequest): ResponseEntity<UserApiModel> {
        val user = userService.register(
            registerUserRequest.handle,
            registerUserRequest.email,
            registerUserRequest.password,
        )
        sessionIssuer.issue(user)
        return ResponseEntity.status(HttpStatus.CREATED).body(user.toApiModel())
    }

    override fun listUsers(q: String?, sort: String, offset: Int, limit: Int): ResponseEntity<ListUsers200Response> {
        val page = userService.search(q, sortByRating = sort == "rating", offset = offset, limit = limit)
        return ResponseEntity.ok(ListUsers200Response(page.items.map { it.toApiModel() }, page.total))
    }

    override fun getUserProfile(id: String): ResponseEntity<GetUserProfile200Response> =
        ResponseEntity.ok(profileService.buildProfile(id))

    override fun getUserRatingHistory(id: String, after: OffsetDateTime?, before: OffsetDateTime?): ResponseEntity<List<RatingHistoryPoint>> {
        val user = userService.getById(id)
        return ResponseEntity.ok(profileService.ratingHistory(user.id, after?.toInstant(), before?.toInstant()))
    }
}

package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.api.ContestsApi
import dev.gpucodeforces.backend.api.model.ContestInput
import dev.gpucodeforces.backend.api.model.GetContestStandings200ResponseInner
import dev.gpucodeforces.backend.auth.CurrentUser
import dev.gpucodeforces.backend.duel.DuelService
import dev.gpucodeforces.backend.user.User
import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.RestController
import dev.gpucodeforces.backend.api.model.Contest as ContestApiModel

@RestController
class ContestsController(private val contestService: ContestService, private val duelService: DuelService) : ContestsApi {
    override fun listContests(q: String?, phase: String?, mine: Boolean, processor: String?): ResponseEntity<List<ContestApiModel>> {
        val viewer = CurrentUser.resolve()
        val now = Instant.now()
        val contests = contestService.list(q, phase, mine, processor, viewer).map { it.toSummary(viewer, now) }
        return ResponseEntity.ok(contests)
    }

    // SecurityConfig requires authentication on this route, so a current user is always present here.
    override fun createContest(contestInput: ContestInput): ResponseEntity<ContestApiModel> {
        val creator = CurrentUser.resolve()!!
        val contest = contestService.create(contestInput, creator)
        return ResponseEntity.status(HttpStatus.CREATED).body(contest.toSummary(creator, Instant.now()))
    }

    override fun getContest(id: String): ResponseEntity<ContestApiModel> {
        val viewer = CurrentUser.resolve()
        val contest = contestService.get(id)
        val problems = contestService.problemsOf(contest.id)
        val rulesMarkdown = contestService.buildRulesMarkdown(contest, problems, viewer)
        val registered = viewer != null && contestService.isRegistered(contest.id, viewer.id)
        val now = Instant.now()
        return ResponseEntity.ok(contest.toApiModel(problems, viewer, registered, now, rulesMarkdown, contestService.resultsPending(contest, now)))
    }

    override fun updateContest(id: String, contestInput: ContestInput): ResponseEntity<ContestApiModel> {
        val currentUser = CurrentUser.resolve()!!
        val contest = contestService.update(id, contestInput, currentUser)
        return ResponseEntity.ok(contest.toSummary(currentUser, Instant.now()))
    }

    override fun registerForContest(id: String): ResponseEntity<Void> {
        contestService.register(id, CurrentUser.resolve()!!)
        return ResponseEntity.noContent().build()
    }

    override fun getContestStandings(id: String): ResponseEntity<List<GetContestStandings200ResponseInner>> =
        ResponseEntity.ok(contestService.standings(id))

    override fun surrenderContest(id: String): ResponseEntity<Void> {
        duelService.surrender(id, CurrentUser.resolve()!!)
        return ResponseEntity.noContent().build()
    }

    private fun Contest.toSummary(viewer: User?, now: Instant): ContestApiModel {
        val registered = viewer != null && contestService.isRegistered(id, viewer.id)
        return toApiModel(contestService.problemsOf(id), viewer, registered, now, resultsPending = contestService.resultsPending(this, now))
    }
}

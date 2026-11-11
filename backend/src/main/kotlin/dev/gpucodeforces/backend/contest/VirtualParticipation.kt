package dev.gpucodeforces.backend.contest

import dev.gpucodeforces.backend.common.BadRequestException
import dev.gpucodeforces.backend.common.ForbiddenException
import dev.gpucodeforces.backend.common.NotFoundApiException
import dev.gpucodeforces.backend.user.User
import dev.gpucodeforces.backend.user.UserRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Duration
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "virtual_participations", schema = "gpucodeforces")
class VirtualParticipation(
    @Id val id: UUID,
    @Column(name = "contest_id") val contestId: UUID,
    @Column(name = "user_id") val userId: UUID,
    @Column(name = "started_at") val startedAt: Instant,
    @Column(name = "ends_at") val endsAt: Instant,
) {
    /** Context of the attempt's submissions, see [dev.gpucodeforces.backend.problem.ProblemContext]. */
    val contextKey: String get() = "$CONTEXT_PREFIX$id"

    fun activeAt(now: Instant) = !now.isBefore(startedAt) && now.isBefore(endsAt)

    companion object {
        const val CONTEXT_PREFIX = "virtual:"
    }
}

interface VirtualParticipationRepository : JpaRepository<VirtualParticipation, UUID> {
    fun findByUserIdOrderByStartedAtDesc(userId: UUID): List<VirtualParticipation>
    fun existsByUserIdAndStartedAtLessThanEqualAndEndsAtGreaterThan(userId: UUID, startedBefore: Instant, endsAfter: Instant): Boolean
}

@Service
class VirtualParticipationService(
    private val repository: VirtualParticipationRepository,
    private val contests: ContestService,
    private val users: UserRepository,
) {
    @Transactional
    fun start(contestId: String, user: User): VirtualParticipation {
        // Serialize starts for this user, including requests handled by different backend instances.
        checkNotNull(users.findForUpdate(user.id)) { "User ${user.id} disappeared" }
        val contest = contests.get(contestId)
        if (contest.format == "duel") throw BadRequestException("Дуэль нельзя пройти виртуально")
        if (contest.finalizedAt == null) throw BadRequestException("Дождитесь окончательных результатов контеста")
        val duration = Duration.between(contest.startAt, contest.endAt)
        if (duration.isNegative || duration.isZero) throw BadRequestException("У контеста неверная длительность")
        val now = Instant.now()
        val active = repository.findByUserIdOrderByStartedAtDesc(user.id).firstOrNull { it.activeAt(now) }
        if (active != null) {
            if (active.contestId == contest.id) return active
            throw BadRequestException("Сначала завершите текущий виртуальный контест")
        }
        return repository.save(VirtualParticipation(UUID.randomUUID(), contest.id, user.id, now, now.plus(duration)))
    }

    fun get(id: UUID, viewer: User?): VirtualParticipation {
        val participation = repository.findById(id).orElseThrow { NotFoundApiException("Виртуальный контест не найден") }
        if (participation.userId != viewer?.id) throw ForbiddenException("Это виртуальное участие другого пользователя")
        return participation
    }

    fun list(user: User): List<VirtualParticipation> = repository.findByUserIdOrderByStartedAtDesc(user.id)
}

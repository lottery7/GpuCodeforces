package dev.gpucodeforces.backend.contest

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContestRegistrationRepository : JpaRepository<ContestRegistration, ContestRegistrationId> {
    fun existsByIdContestIdAndIdUserId(contestId: UUID, userId: UUID): Boolean

    fun findByIdContestId(contestId: UUID): List<ContestRegistration>
}

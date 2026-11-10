package dev.gpucodeforces.backend.contest

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ContestProblemRepository : JpaRepository<ContestProblem, ContestProblemId> {
    fun findByIdContestIdOrderByPosition(contestId: UUID): List<ContestProblem>

    fun deleteByIdContestId(contestId: UUID)
}

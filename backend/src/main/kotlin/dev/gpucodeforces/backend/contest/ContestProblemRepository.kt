package dev.gpucodeforces.backend.contest

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ContestProblemRepository : JpaRepository<ContestProblem, ContestProblemId> {
    fun findByIdContestIdOrderByPosition(contestId: UUID): List<ContestProblem>

    fun deleteByIdContestId(contestId: UUID)

    /** Until a contest starts its problems follow the bank; afterwards their versions are fixed. */
    @Modifying
    @Query(
        """
        UPDATE gpucodeforces.contest_problems cp SET problem_version_id = :versionId
        FROM gpucodeforces.contests c
        WHERE cp.contest_id = c.id AND cp.problem_id = :problemId AND c.start_at > now()
        """,
        nativeQuery = true,
    )
    fun repinUpcoming(problemId: String, versionId: UUID): Int
}

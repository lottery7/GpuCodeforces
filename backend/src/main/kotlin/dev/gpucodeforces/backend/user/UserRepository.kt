package dev.gpucodeforces.backend.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    fun findForUpdate(id: UUID): User?

    fun findByHandleIgnoreCaseOrEmailIgnoreCase(handle: String, email: String): User?

    fun existsByHandleIgnoreCase(handle: String): Boolean

    fun existsByEmailIgnoreCase(email: String): Boolean

    @Query(
        value = "SELECT * FROM gpucodeforces.users WHERE lower(handle) LIKE lower(concat('%', :query, '%')) " +
            "ORDER BY handle ASC LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun searchByHandle(query: String, limit: Int, offset: Int): List<User>

    @Query(
        value = "SELECT count(*) FROM gpucodeforces.users WHERE lower(handle) LIKE lower(concat('%', :query, '%'))",
        nativeQuery = true,
    )
    fun countByHandle(query: String): Long

    /** rank is precomputed (see rating finalization), so filtering here never changes a user's place. */
    @Query(
        value = "SELECT * FROM gpucodeforces.users WHERE rating IS NOT NULL " +
            "AND lower(handle) LIKE lower(concat('%', :query, '%')) ORDER BY rank ASC LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun searchRated(query: String, limit: Int, offset: Int): List<User>

    @Query(
        value = "SELECT count(*) FROM gpucodeforces.users WHERE rating IS NOT NULL " +
            "AND lower(handle) LIKE lower(concat('%', :query, '%'))",
        nativeQuery = true,
    )
    fun countRated(query: String): Long

    /**
     * Recomputes the denormalized `rank` column for every rated user after a rating change.
     * flushAutomatically/clearAutomatically: entity-managed rating updates in the same transaction
     * must hit the database before this runs, and the persistence context must drop its now-stale cache after.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE gpucodeforces.users u SET rank = ranked.rnk FROM " +
            "(SELECT id, RANK() OVER (ORDER BY rating DESC) AS rnk FROM gpucodeforces.users WHERE rating IS NOT NULL) ranked " +
            "WHERE u.id = ranked.id",
        nativeQuery = true,
    )
    fun recomputeRanks()
}

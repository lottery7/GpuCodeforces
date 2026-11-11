package dev.gpucodeforces.backend.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    fun findByHandleIgnoreCase(handle: String): User?

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
        value = "SELECT u.* FROM gpucodeforces.users u JOIN gpucodeforces.user_ratings r ON r.user_id = u.id AND r.processor = :processor " +
            "WHERE lower(u.handle) LIKE lower(concat('%', :query, '%')) ORDER BY r.rank ASC, lower(u.handle) ASC LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun searchRated(processor: String, query: String, limit: Int, offset: Int): List<User>

    @Query(
        value = "SELECT count(*) FROM gpucodeforces.users u JOIN gpucodeforces.user_ratings r ON r.user_id = u.id AND r.processor = :processor " +
            "WHERE lower(u.handle) LIKE lower(concat('%', :query, '%'))",
        nativeQuery = true,
    )
    fun countRated(processor: String, query: String): Long

    /**
     * Recomputes the denormalized `rank` of every rating, separately for each processor, after a rating change.
     * flushAutomatically/clearAutomatically: entity-managed rating updates in the same transaction
     * must hit the database before this runs, and the persistence context must drop its now-stale cache after.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE gpucodeforces.user_ratings r SET rank = ranked.rnk FROM " +
            "(SELECT user_id, processor, RANK() OVER (PARTITION BY processor ORDER BY rating DESC) AS rnk FROM gpucodeforces.user_ratings) ranked " +
            "WHERE r.user_id = ranked.user_id AND r.processor = ranked.processor",
        nativeQuery = true,
    )
    fun recomputeRanks()
}

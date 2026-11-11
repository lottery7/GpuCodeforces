package dev.gpucodeforces.backend.user

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    fun findByHandleIgnoreCase(handle: String): User?

    /** Also serializes what a user may do once at a time: an answer to a guess, a submission, a virtual start. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM User u WHERE u.id = :id")
    fun findForUpdate(id: UUID): User?

    fun findByHandleIgnoreCaseOrEmailIgnoreCase(handle: String, email: String): User?

    fun existsByHandleIgnoreCase(handle: String): Boolean

    fun existsByEmailIgnoreCase(email: String): Boolean

    @Query(value = "SELECT * FROM gpucodeforces.users ORDER BY handle, id LIMIT :limit OFFSET :offset", nativeQuery = true)
    fun pageByHandle(limit: Int, offset: Int): List<User>

    /** The rated users of a processor by their place there. */
    @Query(
        value = "SELECT u.* FROM gpucodeforces.user_ratings r JOIN gpucodeforces.users u ON u.id = r.user_id " +
            "WHERE r.processor = :processor ORDER BY r.rank, u.handle, u.id LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun pageRated(processor: String, limit: Int, offset: Int): List<User>

    @Query(value = "SELECT count(*) FROM gpucodeforces.user_ratings WHERE processor = :processor", nativeQuery = true)
    fun countRated(processor: String): Long

    @Query(value = "SELECT * FROM gpucodeforces.users WHERE strpos(lower(handle), lower(:query)) > 0 ORDER BY handle, id LIMIT :limit OFFSET :offset", nativeQuery = true)
    fun searchShortByHandle(query: String, limit: Int, offset: Int): List<User>

    @Query(
        value = "SELECT u.* FROM gpucodeforces.user_ratings r JOIN gpucodeforces.users u ON u.id = r.user_id " +
            "WHERE r.processor = :processor AND strpos(lower(u.handle), lower(:query)) > 0 ORDER BY r.rank, u.handle, u.id LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun searchShortRated(processor: String, query: String, limit: Int, offset: Int): List<User>

    @Query(value = "SELECT count(*) FROM gpucodeforces.users WHERE strpos(lower(handle), lower(:query)) > 0", nativeQuery = true)
    fun countShortByHandle(query: String): Long

    @Query(
        value = "SELECT count(*) FROM gpucodeforces.user_ratings r JOIN gpucodeforces.users u ON u.id = r.user_id " +
            "WHERE r.processor = :processor AND strpos(lower(u.handle), lower(:query)) > 0",
        nativeQuery = true,
    )
    fun countShortRated(processor: String, query: String): Long

    @Query(
        value = "WITH matches AS MATERIALIZED (SELECT * FROM gpucodeforces.users WHERE lower(handle) LIKE :pattern) " +
            "SELECT * FROM matches ORDER BY handle, id LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun searchByHandle(pattern: String, limit: Int, offset: Int): List<User>

    @Query(
        value = "SELECT count(*) FROM gpucodeforces.users WHERE lower(handle) LIKE :pattern",
        nativeQuery = true,
    )
    fun countByHandle(pattern: String): Long

    /** rank is precomputed (see rating finalization), so filtering here never changes a user's place. */
    @Query(
        value = "WITH matches AS MATERIALIZED (SELECT * FROM gpucodeforces.users WHERE lower(handle) LIKE :pattern) " +
            "SELECT m.* FROM matches m JOIN gpucodeforces.user_ratings r ON r.user_id = m.id AND r.processor = :processor " +
            "ORDER BY r.rank, m.handle, m.id LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun searchRated(processor: String, pattern: String, limit: Int, offset: Int): List<User>

    @Query(
        value = "SELECT count(*) FROM gpucodeforces.users u JOIN gpucodeforces.user_ratings r ON r.user_id = u.id AND r.processor = :processor " +
            "WHERE lower(u.handle) LIKE :pattern",
        nativeQuery = true,
    )
    fun countRated(processor: String, pattern: String): Long

    /**
     * Recomputes the denormalized `rank` of every rating, separately for each processor, after a rating change.
     * flushAutomatically/clearAutomatically: entity-managed rating updates in the same transaction
     * must hit the database before this runs, and the persistence context must drop its now-stale cache after.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE gpucodeforces.user_ratings r SET rank = ranked.rnk FROM " +
            "(SELECT user_id, processor, RANK() OVER (PARTITION BY processor ORDER BY rating DESC) AS rnk FROM gpucodeforces.user_ratings) ranked " +
            "WHERE r.user_id = ranked.user_id AND r.processor = ranked.processor AND r.rank IS DISTINCT FROM ranked.rnk",
        nativeQuery = true,
    )
    fun recomputeRanks(): Int
}

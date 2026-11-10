package dev.gpucodeforces.backend.user

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface UserRepository : JpaRepository<User, UUID> {
    fun findByHandleIgnoreCaseOrEmailIgnoreCase(handle: String, email: String): User?

    fun existsByHandleIgnoreCase(handle: String): Boolean

    fun existsByEmailIgnoreCase(email: String): Boolean

    @Query(value = "SELECT * FROM gpucodeforces.users ORDER BY handle, id LIMIT :limit OFFSET :offset", nativeQuery = true)
    fun pageByHandle(limit: Int, offset: Int): List<User>

    @Query(value = "SELECT * FROM gpucodeforces.users WHERE rating IS NOT NULL ORDER BY rank, handle, id LIMIT :limit OFFSET :offset", nativeQuery = true)
    fun pageRated(limit: Int, offset: Int): List<User>

    fun countByRatingIsNotNull(): Long

    @Query(value = "SELECT * FROM gpucodeforces.users WHERE strpos(lower(handle), lower(:query)) > 0 ORDER BY handle, id LIMIT :limit OFFSET :offset", nativeQuery = true)
    fun searchShortByHandle(query: String, limit: Int, offset: Int): List<User>

    @Query(value = "SELECT * FROM gpucodeforces.users WHERE rating IS NOT NULL AND strpos(lower(handle), lower(:query)) > 0 ORDER BY rank, handle, id LIMIT :limit OFFSET :offset", nativeQuery = true)
    fun searchShortRated(query: String, limit: Int, offset: Int): List<User>

    @Query(value = "SELECT count(*) FROM gpucodeforces.users WHERE strpos(lower(handle), lower(:query)) > 0", nativeQuery = true)
    fun countShortByHandle(query: String): Long

    @Query(value = "SELECT count(*) FROM gpucodeforces.users WHERE rating IS NOT NULL AND strpos(lower(handle), lower(:query)) > 0", nativeQuery = true)
    fun countShortRated(query: String): Long

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
        value = "WITH matches AS MATERIALIZED (SELECT * FROM gpucodeforces.users WHERE rating IS NOT NULL " +
            "AND lower(handle) LIKE :pattern) SELECT * FROM matches ORDER BY rank, handle, id LIMIT :limit OFFSET :offset",
        nativeQuery = true,
    )
    fun searchRated(pattern: String, limit: Int, offset: Int): List<User>

    @Query(
        value = "SELECT count(*) FROM gpucodeforces.users WHERE rating IS NOT NULL " +
            "AND lower(handle) LIKE :pattern",
        nativeQuery = true,
    )
    fun countRated(pattern: String): Long

    /**
     * Recomputes the denormalized `rank` column for every rated user after a rating change.
     * flushAutomatically/clearAutomatically: entity-managed rating updates in the same transaction
     * must hit the database before this runs, and the persistence context must drop its now-stale cache after.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
        value = "UPDATE gpucodeforces.users u SET rank = ranked.rnk FROM " +
            "(SELECT id, RANK() OVER (ORDER BY rating DESC) AS rnk FROM gpucodeforces.users WHERE rating IS NOT NULL) ranked " +
            "WHERE u.id = ranked.id AND u.rank IS DISTINCT FROM ranked.rnk",
        nativeQuery = true,
    )
    fun recomputeRanks(): Int
}

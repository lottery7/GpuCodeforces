package dev.gpucodeforces.backend.submission

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.intellij.lang.annotations.Language
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/** Content-addressed immutable suite; submissions and historical checks keep its identifier. */
@Entity
@Table(name = "test_suites", schema = "gpucodeforces")
class TestSuite(@Id val id: String, @Column(name = "tests_json") val testsJson: String)

interface TestSuiteRepository : JpaRepository<TestSuite, String> {
    @Modifying
    @Transactional
    @Query(value = INSERT_SUITE, nativeQuery = true)
    fun store(id: String, tests: String): Int
}

@Language("SQL")
private const val INSERT_SUITE = "INSERT INTO gpucodeforces.test_suites (id, tests_json) VALUES (:id, :tests) ON CONFLICT (id) DO NOTHING"

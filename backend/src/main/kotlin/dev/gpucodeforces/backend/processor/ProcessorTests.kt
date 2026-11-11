package dev.gpucodeforces.backend.processor

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.io.Serializable
import java.time.Instant

@Embeddable
data class ProcessorTestsId(
    @Column(name = "judge_hash") val judgeHash: String,
    @Column(name = "processor") val processor: String,
) : Serializable

/** One test of a processor as the judge reported it. */
data class KnownTest(val name: String, val sample: Boolean, val measured: Boolean)

/**
 * The tests a processor runs for a judge hash. The backend cannot run a problem's generator, so the list is the one
 * the judge reported for the last submission that ran every test (an accepted one).
 */
@Entity
@Table(name = "processor_tests", schema = "gpucodeforces")
class ProcessorTests(
    @EmbeddedId
    val id: ProcessorTestsId,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    var tests: List<KnownTest>,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    override fun equals(other: Any?) = this === other || (other is ProcessorTests && id == other.id)

    override fun hashCode() = id.hashCode()
}

interface ProcessorTestsRepository : JpaRepository<ProcessorTests, ProcessorTestsId>

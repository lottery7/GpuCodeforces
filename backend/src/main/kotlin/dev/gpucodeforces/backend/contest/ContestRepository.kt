package dev.gpucodeforces.backend.contest

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface ContestRepository : JpaRepository<Contest, UUID>, JpaSpecificationExecutor<Contest>

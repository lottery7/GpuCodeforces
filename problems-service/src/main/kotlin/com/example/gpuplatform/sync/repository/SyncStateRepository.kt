package com.example.gpuplatform.sync.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface SyncStateRepository : JpaRepository<SyncStateEntity, Int> {

    @Query("select s from SyncStateEntity s where s.id = 1")
    fun findSingle(): SyncStateEntity?
}
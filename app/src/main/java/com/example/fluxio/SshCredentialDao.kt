package com.example.fluxio

import androidx.room.*

@Dao
interface SshCredentialDao {
    @Query("SELECT * FROM saved_ssh_credentials WHERE userId = :userId")
    suspend fun getCredentialsForUser(userId: String): List<SavedSshCredential>

    @Query("SELECT * FROM saved_ssh_credentials WHERE id = :id LIMIT 1")
    suspend fun getCredentialById(id: String): SavedSshCredential?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCredential(credential: SavedSshCredential)

    @Delete
    suspend fun deleteCredential(credential: SavedSshCredential)

    @Query("DELETE FROM saved_ssh_credentials WHERE id = :id")
    suspend fun deleteById(id: String)
}

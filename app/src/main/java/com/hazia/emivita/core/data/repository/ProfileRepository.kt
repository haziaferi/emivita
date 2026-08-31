package com.hazia.emivita.core.data.repository

import com.hazia.emivita.core.data.dao.ProfileDao
import com.hazia.emivita.core.data.entity.Profile
import kotlinx.coroutines.flow.Flow

class ProfileRepository(private val profileDao: ProfileDao) {
    fun observeAll(): Flow<List<Profile>> = profileDao.observeAll()
    suspend fun getById(id: Long): Profile? = profileDao.getById(id)

    suspend fun create(name: String, color: String? = null): Long =
        profileDao.insert(
            Profile(name = name, color = color, createdAtEpochMillis = System.currentTimeMillis())
        )

    suspend fun rename(profile: Profile, newName: String) =
        profileDao.update(profile.copy(name = newName))

    suspend fun delete(profile: Profile) = profileDao.delete(profile)
}

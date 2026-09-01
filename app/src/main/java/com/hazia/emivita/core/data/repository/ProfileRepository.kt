package com.hazia.emivita.core.data.repository

import com.hazia.emivita.core.data.dao.ProfileDao
import com.hazia.emivita.core.data.entity.Profile
import kotlinx.coroutines.flow.Flow

/** Deliberately just the two operations the app actually performs: EmivitaApp
 *  observes the profile list and silently creates the one profile on first
 *  launch. There is no multi-profile UI (see LocalCurrentProfileId), so
 *  getById/rename/delete were unreferenced and are gone; ProfileDao still
 *  exposes them for whenever that UI is built. Note that a real delete path
 *  will need to cancel each cascaded reminder's pending AlarmManager alarm the
 *  way MedicineRepository.delete does -- Room's cascade can't reach OS state. */
class ProfileRepository(private val profileDao: ProfileDao) {
    fun observeAll(): Flow<List<Profile>> = profileDao.observeAll()

    suspend fun create(name: String, color: String? = null): Long =
        profileDao.insert(
            Profile(name = name, color = color, createdAtEpochMillis = System.currentTimeMillis())
        )
}

package com.hazia.emivita.core.data.repository

import com.hazia.emivita.core.data.dao.ProfileDao
import com.hazia.emivita.core.data.entity.Profile
import kotlinx.coroutines.flow.Flow

/** Deliberately only the operations the app actually performs: EmivitaApp
 *  observes the profile list and silently creates the one profile on first
 *  launch, and the medical-ID screen reads and writes that row (§7 R48).
 *  There is still no multi-profile UI (see LocalCurrentProfileId), so `delete`
 *  remains unreferenced and stays on ProfileDao only. Note that a real delete
 *  path will need to cancel each cascaded reminder's pending AlarmManager
 *  alarm the way MedicineRepository.delete does -- Room's cascade can't reach
 *  OS state. */
class ProfileRepository(private val profileDao: ProfileDao) {
    fun observeAll(): Flow<List<Profile>> = profileDao.observeAll()

    /** Live, unlike [MedicineRepository]'s one-shot tag snapshot: the medical
     *  ID screen edits fields nothing else writes, so there is no diff for a
     *  moving baseline to corrupt, and the emergency card must never render
     *  from a stale read. */
    fun observeById(id: Long): Flow<Profile?> = profileDao.observeById(id)

    suspend fun getById(id: Long): Profile? = profileDao.getById(id)

    suspend fun update(profile: Profile) = profileDao.update(profile)

    suspend fun create(name: String, color: String? = null): Long =
        profileDao.insert(
            Profile(name = name, color = color, createdAtEpochMillis = System.currentTimeMillis())
        )
}

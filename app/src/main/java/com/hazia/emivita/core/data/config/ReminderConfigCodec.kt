package com.hazia.emivita.core.data.config

import kotlinx.serialization.json.Json

/**
 * Standalone so the scheduling engine can decode a Reminder's config without
 * depending on ReminderRepository (which itself depends on
 * SchedulingCoordinator to notify it of changes -- routing the engine
 * through the repository would create a circular dependency between the
 * two). ReminderRepository.decodeConfig delegates here rather than keeping
 * its own private Json instance, so there's exactly one place this is done.
 */
private val reminderConfigJson = Json { ignoreUnknownKeys = true }

fun encodeReminderConfig(config: ReminderConfig): String =
    reminderConfigJson.encodeToString(ReminderConfig.serializer(), config)

fun decodeReminderConfig(configJson: String): ReminderConfig =
    reminderConfigJson.decodeFromString(ReminderConfig.serializer(), configJson)

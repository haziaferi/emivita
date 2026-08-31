package com.hazia.emivita.core.data.db

import androidx.room.TypeConverter
import com.hazia.emivita.core.data.config.ReminderType
import com.hazia.emivita.core.data.entity.DoseStatus
import com.hazia.emivita.core.data.entity.NotificationPriority

class Converters {
    @TypeConverter fun fromPriority(value: NotificationPriority): String = value.name
    @TypeConverter fun toPriority(value: String): NotificationPriority = NotificationPriority.valueOf(value)

    @TypeConverter fun fromDoseStatus(value: DoseStatus): String = value.name
    @TypeConverter fun toDoseStatus(value: String): DoseStatus = DoseStatus.valueOf(value)

    @TypeConverter fun fromReminderType(value: ReminderType): String = value.name
    @TypeConverter fun toReminderType(value: String): ReminderType = ReminderType.valueOf(value)
}

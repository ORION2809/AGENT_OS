package com.mobileai.core.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "contact_id") val contactId: Long = 0,
    val name: String,
    @ColumnInfo(name = "last_interaction_epoch_ms") val lastInteractionEpochMs: Long,
    @ColumnInfo(name = "interaction_count_90d") val interactionCount90d: Int,
)

@Entity(tableName = "events")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "event_id") val eventId: Long = 0,
    val title: String,
    @ColumnInfo(name = "participant_contact_id") val participantContactId: Long?,
    @ColumnInfo(name = "event_time_epoch_ms") val eventTimeEpochMs: Long,
)

package com.echoflow.app.domain.model

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Lightweight context provided to the AI engine to help resolve
 * relative date/time references and contact disambiguation.
 *
 * Captures requestCreatedAt at the exact millisecond the user creates the request.
 */
data class UserContext(
    val requestCreatedAt: Instant = Instant.now(),
    val timeZone: ZoneId = ZoneId.systemDefault(),
    val currentDateTime: LocalDateTime = LocalDateTime.ofInstant(requestCreatedAt, timeZone),
    val knownContacts: List<ContactInfo> = emptyList()
) {
    val timeZoneId: String get() = timeZone.id
}

/**
 * Minimal contact info for recipient resolution.
 */
data class ContactInfo(
    val name: String,
    val phoneNumber: String? = null,
    val email: String? = null
)

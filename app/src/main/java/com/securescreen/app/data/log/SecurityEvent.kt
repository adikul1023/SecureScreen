package com.securescreen.app.data.log

enum class SecurityEventSeverity {
    INFO,
    WARNING,
    ERROR
}

enum class SecurityEventType {
    PROTECTION_ENABLED,
    PROTECTION_DISABLED,
    APP_ENTERED,
    APP_EXITED,
    PERMISSION_REVOKED,
    PERMISSION_RESTORED,
    SERVICE_RESTARTED,
    PROTECTION_UNAVAILABLE,
    PROTECTION_RESTORED
}

data class SecurityEvent(
    val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val eventType: SecurityEventType,
    val severity: SecurityEventSeverity,
    val title: String,
    val details: String? = null,
    val packageName: String? = null
)

package com.securescreen.app.data.log

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SecurityLogDatabaseHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_LOGS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_TIMESTAMP INTEGER NOT NULL,
                $COL_EVENT_TYPE TEXT NOT NULL,
                $COL_SEVERITY TEXT NOT NULL,
                $COL_TITLE TEXT NOT NULL,
                $COL_DETAILS TEXT,
                $COL_PACKAGE_NAME TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_logs_timestamp ON $TABLE_LOGS ($COL_TIMESTAMP DESC)")
        db.execSQL("CREATE INDEX idx_logs_severity ON $TABLE_LOGS ($COL_SEVERITY)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_LOGS")
        onCreate(db)
    }

    @Synchronized
    fun insertEvent(event: SecurityEvent): Long {
        val db = writableDatabase
        val values = ContentValues().apply {
            put(COL_TIMESTAMP, event.timestamp)
            put(COL_EVENT_TYPE, event.eventType.name)
            put(COL_SEVERITY, event.severity.name)
            put(COL_TITLE, event.title)
            put(COL_DETAILS, event.details)
            put(COL_PACKAGE_NAME, event.packageName)
        }
        val insertedId = db.insert(TABLE_LOGS, null, values)

        // Prune logs if exceeding max retention count
        pruneOldLogs(db)
        return insertedId
    }

    private fun pruneOldLogs(db: SQLiteDatabase) {
        runCatching {
            db.execSQL(
                """
                DELETE FROM $TABLE_LOGS 
                WHERE $COL_ID NOT IN (
                    SELECT $COL_ID FROM $TABLE_LOGS 
                    ORDER BY $COL_TIMESTAMP DESC 
                    LIMIT $MAX_RETENTION_COUNT
                )
                """.trimIndent()
            )
        }
    }

    @Synchronized
    fun getEvents(
        limit: Int = 500,
        severityFilter: SecurityEventSeverity? = null,
        query: String? = null
    ): List<SecurityEvent> {
        val db = readableDatabase
        val conditions = mutableListOf<String>()
        val args = mutableListOf<String>()

        if (severityFilter != null) {
            conditions.add("$COL_SEVERITY = ?")
            args.add(severityFilter.name)
        }

        if (!query.isNullOrBlank()) {
            val trimmed = "%${query.trim()}%"
            conditions.add("($COL_TITLE LIKE ? OR $COL_DETAILS LIKE ? OR $COL_PACKAGE_NAME LIKE ?)")
            args.add(trimmed)
            args.add(trimmed)
            args.add(trimmed)
        }

        val selection = if (conditions.isEmpty()) null else conditions.joinToString(" AND ")
        val selectionArgs = if (args.isEmpty()) null else args.toTypedArray()

        val cursor = db.query(
            TABLE_LOGS,
            null,
            selection,
            selectionArgs,
            null,
            null,
            "$COL_TIMESTAMP DESC",
            limit.toString()
        )

        val results = mutableListOf<SecurityEvent>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(cursorToEvent(it))
            }
        }
        return results
    }

    @Synchronized
    fun getEventCount(): Int {
        val db = readableDatabase
        val cursor = db.rawQuery("SELECT COUNT(*) FROM $TABLE_LOGS", null)
        return cursor.use {
            if (it.moveToFirst()) it.getInt(0) else 0
        }
    }

    @Synchronized
    fun getCountsBySeverity(): Map<SecurityEventSeverity, Int> {
        val db = readableDatabase
        val map = mutableMapOf(
            SecurityEventSeverity.INFO to 0,
            SecurityEventSeverity.WARNING to 0,
            SecurityEventSeverity.ERROR to 0
        )
        val cursor = db.rawQuery(
            "SELECT $COL_SEVERITY, COUNT(*) FROM $TABLE_LOGS GROUP BY $COL_SEVERITY",
            null
        )
        cursor.use {
            while (it.moveToNext()) {
                val sevName = it.getString(0)
                val count = it.getInt(1)
                val severity = runCatching { SecurityEventSeverity.valueOf(sevName) }.getOrNull()
                if (severity != null) {
                    map[severity] = count
                }
            }
        }
        return map
    }

    @Synchronized
    fun clearLogs(): Int {
        val db = writableDatabase
        return db.delete(TABLE_LOGS, null, null)
    }

    private fun cursorToEvent(cursor: Cursor): SecurityEvent {
        val id = cursor.getLong(cursor.getColumnIndexOrThrow(COL_ID))
        val timestamp = cursor.getLong(cursor.getColumnIndexOrThrow(COL_TIMESTAMP))
        val eventTypeName = cursor.getString(cursor.getColumnIndexOrThrow(COL_EVENT_TYPE))
        val severityName = cursor.getString(cursor.getColumnIndexOrThrow(COL_SEVERITY))
        val title = cursor.getString(cursor.getColumnIndexOrThrow(COL_TITLE))
        val details = cursor.getString(cursor.getColumnIndexOrThrow(COL_DETAILS))
        val packageName = cursor.getString(cursor.getColumnIndexOrThrow(COL_PACKAGE_NAME))

        val eventType = runCatching { SecurityEventType.valueOf(eventTypeName) }
            .getOrDefault(SecurityEventType.PROTECTION_ENABLED)
        val severity = runCatching { SecurityEventSeverity.valueOf(severityName) }
            .getOrDefault(SecurityEventSeverity.INFO)

        return SecurityEvent(
            id = id,
            timestamp = timestamp,
            eventType = eventType,
            severity = severity,
            title = title,
            details = details,
            packageName = packageName
        )
    }

    companion object {
        private const val DATABASE_NAME = "security_events.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_LOGS = "security_logs"

        private const val COL_ID = "id"
        private const val COL_TIMESTAMP = "timestamp"
        private const val COL_EVENT_TYPE = "event_type"
        private const val COL_SEVERITY = "severity"
        private const val COL_TITLE = "title"
        private const val COL_DETAILS = "details"
        private const val COL_PACKAGE_NAME = "package_name"

        const val MAX_RETENTION_COUNT = 1000
    }
}

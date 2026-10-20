package com.adnanalhasani.hospital

import android.util.Base64
import androidx.room.RoomDatabase
import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only, schema-aware JSON snapshot of every application table in the local Room database.
 * This is an export for portability and inspection, not a database restore format.
 */
object HospitalDatabaseExport {
    fun toJson(database: RoomDatabase): String {
        val sqlite = database.openHelper.readableDatabase
        val tables = JSONArray()
        sqlite.query(
            "SELECT name FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT LIKE 'sqlite_%' AND name != 'room_master_table' ORDER BY name"
        ).use { tableCursor ->
            while (tableCursor.moveToNext()) {
                val tableName = tableCursor.getString(0)
                val rows = JSONArray()
                val safeName = tableName.replace("\"", "\"\"")
                sqlite.query("SELECT * FROM \"$safeName\"").use { rowCursor ->
                    while (rowCursor.moveToNext()) {
                        val row = JSONObject()
                        for (columnIndex in 0 until rowCursor.columnCount) {
                            val columnName = rowCursor.getColumnName(columnIndex)
                            when (rowCursor.getType(columnIndex)) {
                                android.database.Cursor.FIELD_TYPE_NULL -> row.put(columnName, JSONObject.NULL)
                                android.database.Cursor.FIELD_TYPE_INTEGER -> row.put(columnName, rowCursor.getLong(columnIndex))
                                android.database.Cursor.FIELD_TYPE_FLOAT -> row.put(columnName, rowCursor.getDouble(columnIndex))
                                android.database.Cursor.FIELD_TYPE_STRING -> row.put(columnName, rowCursor.getString(columnIndex))
                                android.database.Cursor.FIELD_TYPE_BLOB -> row.put(
                                    columnName,
                                    JSONObject().put("encoding", "base64").put(
                                        "value",
                                        Base64.encodeToString(rowCursor.getBlob(columnIndex), Base64.NO_WRAP)
                                    )
                                )
                            }
                        }
                        rows.put(row)
                    }
                }
                tables.put(JSONObject().put("table", tableName).put("rows", rows))
            }
        }
        return JSONObject()
            .put("format", "hospital-local-database-export")
            .put("formatVersion", 1)
            .put("exportedAtEpochMillis", System.currentTimeMillis())
            .put("databaseVersion", database.openHelper.readableDatabase.version)
            .put("restoreSupported", false)
            .put("tables", tables)
            .toString(2)
    }
}

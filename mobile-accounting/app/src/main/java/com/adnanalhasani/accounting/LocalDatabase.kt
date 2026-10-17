package com.adnanalhasani.accounting

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONObject
import java.util.UUID

/**
 * Local-first storage foundation. Pending writes are retained until the API acknowledges them.
 * Do not clear pending rows on network errors.
 */
class LocalDatabase(context: Context) : SQLiteOpenHelper(context, "accounting_local.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts (local_id TEXT PRIMARY KEY, server_id INTEGER, name TEXT NOT NULL, currency TEXT NOT NULL, opening_balance TEXT NOT NULL, balance TEXT, sync_state TEXT NOT NULL DEFAULT 'pending')")
        db.execSQL("CREATE TABLE transactions (local_id TEXT PRIMARY KEY, server_id INTEGER, account_local_id TEXT NOT NULL, payload TEXT NOT NULL, sync_state TEXT NOT NULL DEFAULT 'pending')")
        db.execSQL("CREATE TABLE invoices (local_id TEXT PRIMARY KEY, server_id INTEGER, payload TEXT NOT NULL, sync_state TEXT NOT NULL DEFAULT 'pending')")
        db.execSQL("CREATE TABLE payments (local_id TEXT PRIMARY KEY, server_id INTEGER, account_local_id TEXT NOT NULL, payload TEXT NOT NULL, sync_state TEXT NOT NULL DEFAULT 'pending')")
        db.execSQL("CREATE TABLE sync_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, operation_id TEXT NOT NULL UNIQUE, entity TEXT NOT NULL, method TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Versioned migrations must preserve financial data; never drop tables here.
    }

    @Synchronized
    fun enqueue(entity: String, method: String, payload: JSONObject): String {
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("operation_id", id)
            put("entity", entity)
            put("method", method)
            put("payload", payload.toString())
            put("created_at", System.currentTimeMillis())
        }
        writableDatabase.insertOrThrow("sync_queue", null, values)
        return id
    }

    @Synchronized
    fun pendingOperations(): List<PendingOperation> {
        val rows = mutableListOf<PendingOperation>()
        readableDatabase.rawQuery(
            "SELECT id, operation_id, entity, method, payload, attempts FROM sync_queue ORDER BY id ASC",
            null
        ).use { c ->
            while (c.moveToNext()) rows.add(
                PendingOperation(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5))
            )
        }
        return rows
    }

    @Synchronized
    fun markSynced(rowId: Long) {
        writableDatabase.delete("sync_queue", "id=?", arrayOf(rowId.toString()))
    }

    @Synchronized
    fun markFailed(rowId: Long, error: String) {
        val values = ContentValues().apply {
            put("attempts", "attempts + 1")
            put("last_error", error.take(500))
        }
        writableDatabase.execSQL(
            "UPDATE sync_queue SET attempts=attempts+1, last_error=? WHERE id=?",
            arrayOf(error.take(500), rowId)
        )
    }
}

data class PendingOperation(
    val rowId: Long,
    val operationId: String,
    val entity: String,
    val method: String,
    val payload: String,
    val attempts: Int
)

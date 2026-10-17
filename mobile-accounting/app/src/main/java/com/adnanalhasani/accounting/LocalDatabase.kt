package com.adnanalhasani.accounting

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Local-first SQLite store. Financial writes remain queued until the server acknowledges them. */
class LocalDatabase(context: Context) : SQLiteOpenHelper(context, "accounting_local.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE accounts (local_id TEXT PRIMARY KEY, server_id INTEGER, name TEXT NOT NULL, currency TEXT NOT NULL, opening_balance TEXT NOT NULL, balance TEXT, sync_state TEXT NOT NULL DEFAULT 'pending')")
        db.execSQL("CREATE TABLE transactions (local_id TEXT PRIMARY KEY, server_id INTEGER, account_local_id TEXT NOT NULL, payload TEXT NOT NULL, sync_state TEXT NOT NULL DEFAULT 'pending')")
        db.execSQL("CREATE TABLE invoices (local_id TEXT PRIMARY KEY, server_id INTEGER, payload TEXT NOT NULL, sync_state TEXT NOT NULL DEFAULT 'pending')")
        db.execSQL("CREATE TABLE payments (local_id TEXT PRIMARY KEY, server_id INTEGER, account_local_id TEXT NOT NULL, payload TEXT NOT NULL, sync_state TEXT NOT NULL DEFAULT 'pending')")
        db.execSQL("CREATE TABLE sync_queue (id INTEGER PRIMARY KEY AUTOINCREMENT, operation_id TEXT NOT NULL UNIQUE, entity TEXT NOT NULL, method TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL, attempts INTEGER NOT NULL DEFAULT 0, last_error TEXT)")
        db.execSQL("CREATE TABLE catalog_records (local_id TEXT PRIMARY KEY, record_type TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX idx_catalog_type ON catalog_records(record_type)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Keep existing rows. New schema changes must use additive migrations.
        if (oldVersion < 2) db.execSQL("CREATE INDEX IF NOT EXISTS idx_sync_queue_id ON sync_queue(id)")
        if (oldVersion < 3) {
            db.execSQL("CREATE TABLE IF NOT EXISTS catalog_records (local_id TEXT PRIMARY KEY, record_type TEXT NOT NULL, payload TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_catalog_type ON catalog_records(record_type)")
        }
    }

    @Synchronized
    fun saveEntity(entity: String, payload: JSONObject): String {
        require(entity in setOf("accounts", "transactions", "invoices", "payments"))
        val localId = payload.optString("local_id").ifBlank { UUID.randomUUID().toString() }
        val normalized = JSONObject(payload.toString()).put("local_id", localId)
        val db = writableDatabase
        db.beginTransaction()
        try {
            when (entity) {
                "accounts" -> {
                    val values = ContentValues().apply {
                        put("local_id", localId)
                        if (normalized.has("server_id") && !normalized.isNull("server_id")) put("server_id", normalized.optInt("server_id"))
                        put("name", normalized.optString("name"))
                        put("currency", normalized.optString("currency", "YER").uppercase())
                        put("opening_balance", normalized.optString("opening_balance", "0"))
                        put("balance", normalized.optString("balance", normalized.optString("opening_balance", "0")))
                        put("sync_state", "pending")
                    }
                    db.insertWithOnConflict(entity, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                "transactions", "payments" -> {
                    val values = ContentValues().apply {
                        put("local_id", localId)
                        put("account_local_id", normalized.optString("account_local_id", normalized.optString("account_id")))
                        put("payload", normalized.toString())
                        put("sync_state", "pending")
                    }
                    db.insertWithOnConflict(entity, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
                "invoices" -> {
                    val values = ContentValues().apply {
                        put("local_id", localId)
                        put("payload", normalized.toString())
                        put("sync_state", "pending")
                    }
                    db.insertWithOnConflict(entity, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                }
            }
            enqueueInTransaction(db, entity, "POST", normalized)
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return localId
    }

    private fun enqueueInTransaction(db: SQLiteDatabase, entity: String, method: String, payload: JSONObject): String {
        val id = UUID.randomUUID().toString()
        val values = ContentValues().apply {
            put("operation_id", id); put("entity", entity); put("method", method)
            put("payload", payload.toString()); put("created_at", System.currentTimeMillis())
        }
        db.insertOrThrow("sync_queue", null, values)
        return id
    }

    @Synchronized
    fun readEntities(entity: String): JSONArray {
        require(entity in setOf("accounts", "transactions", "invoices", "payments"))
        val array = JSONArray()
        readableDatabase.rawQuery("SELECT * FROM $entity ORDER BY rowid DESC", null).use { c ->
            while (c.moveToNext()) {
                val obj = if (entity == "accounts") {
                    JSONObject()
                        .put("local_id", c.getString(c.getColumnIndexOrThrow("local_id")))
                        .put("server_id", if (c.isNull(c.getColumnIndexOrThrow("server_id"))) JSONObject.NULL else c.getInt(c.getColumnIndexOrThrow("server_id")))
                        .put("name", c.getString(c.getColumnIndexOrThrow("name")))
                        .put("currency", c.getString(c.getColumnIndexOrThrow("currency")))
                        .put("opening_balance", c.getString(c.getColumnIndexOrThrow("opening_balance")))
                        .put("balance", c.getString(c.getColumnIndexOrThrow("balance")))
                } else {
                    JSONObject(c.getString(c.getColumnIndexOrThrow("payload")))
                        .put("local_id", c.getString(c.getColumnIndexOrThrow("local_id")))
                        .put("server_id", if (c.isNull(c.getColumnIndexOrThrow("server_id"))) JSONObject.NULL else c.getInt(c.getColumnIndexOrThrow("server_id")))
                }
                array.put(obj)
            }
        }
        return array
    }

    @Synchronized
    fun accountByLocalNumber(number: Int): JSONObject? {
        readableDatabase.rawQuery("SELECT local_id, server_id, name, currency, opening_balance, balance FROM accounts ORDER BY rowid ASC LIMIT 1 OFFSET ?", arrayOf((number - 1).toString())).use { c ->
            if (!c.moveToFirst()) return null
            return JSONObject().put("local_id", c.getString(0)).put("server_id", if (c.isNull(1)) JSONObject.NULL else c.getInt(1))
                .put("name", c.getString(2)).put("currency", c.getString(3)).put("opening_balance", c.getString(4)).put("balance", c.getString(5))
        }
    }

    @Synchronized
    fun summary(): JSONObject {
        val accounts = readEntities("accounts")
        val transactions = readEntities("transactions")
        var income = java.math.BigDecimal.ZERO
        var expenses = java.math.BigDecimal.ZERO
        for (i in 0 until transactions.length()) {
            val item = transactions.getJSONObject(i)
            val amount = item.optString("amount", "0").toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO
            if (item.optString("kind") == "income") income = income.add(amount) else expenses = expenses.add(amount)
        }
        val recent = JSONArray()
        for (i in 0 until minOf(5, transactions.length())) recent.put(transactions.getJSONObject(i))
        return JSONObject().put("accounts_count", accounts.length()).put("income", income.toPlainString())
            .put("expenses", expenses.toPlainString()).put("net", income.subtract(expenses).toPlainString())
            .put("recent_transactions", recent)
    }


    @Synchronized
    fun saveCatalogRecord(type: String, payload: JSONObject): String {
        require(type in setOf("customers", "suppliers", "products", "stock", "purchases", "sales"))
        val id = payload.optString("local_id").ifBlank { UUID.randomUUID().toString() }
        val now = System.currentTimeMillis()
        val normalized = JSONObject(payload.toString()).put("local_id", id).put("record_type", type).put("updated_at", now)
        val values = ContentValues().apply {
            put("local_id", id); put("record_type", type); put("payload", normalized.toString())
            put("created_at", payload.optLong("created_at", now)); put("updated_at", now)
        }
        writableDatabase.insertWithOnConflict("catalog_records", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        return id
    }

    @Synchronized
    fun readCatalogRecords(type: String): JSONArray {
        require(type in setOf("customers", "suppliers", "products", "stock", "purchases", "sales"))
        val result = JSONArray()
        readableDatabase.rawQuery("SELECT payload FROM catalog_records WHERE record_type=? ORDER BY updated_at DESC", arrayOf(type)).use { c ->
            while (c.moveToNext()) result.put(JSONObject(c.getString(0)))
        }
        return result
    }

    @Synchronized
    fun deleteCatalogRecord(type: String, localId: String): Boolean {
        require(type in setOf("customers", "suppliers", "products", "stock", "purchases", "sales"))
        return writableDatabase.delete("catalog_records", "record_type=? AND local_id=?", arrayOf(type, localId)) > 0
    }

    @Synchronized
    fun hasLocalData(): Boolean {
        readableDatabase.rawQuery(
            "SELECT (SELECT COUNT(*) FROM accounts) + (SELECT COUNT(*) FROM transactions) + (SELECT COUNT(*) FROM invoices) + (SELECT COUNT(*) FROM payments)",
            null
        ).use { c -> return c.moveToFirst() && c.getInt(0) > 0 }
    }

    @Synchronized
    fun localCounts(): JSONObject {
        fun count(table: String): Int {
            readableDatabase.rawQuery("SELECT COUNT(*) FROM $table", null).use { c ->
                return if (c.moveToFirst()) c.getInt(0) else 0
            }
        }
        return JSONObject().put("accounts", count("accounts")).put("transactions", count("transactions"))
            .put("invoices", count("invoices")).put("payments", count("payments")).put("pending", pendingCount())
    }

    @Synchronized
    fun pendingOperations(): List<PendingOperation> {
        val rows = mutableListOf<PendingOperation>()
        readableDatabase.rawQuery("SELECT id, operation_id, entity, method, payload, attempts FROM sync_queue ORDER BY id ASC", null).use { c ->
            while (c.moveToNext()) rows.add(PendingOperation(c.getLong(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4), c.getInt(5)))
        }
        return rows
    }

    @Synchronized
    fun markSynced(rowId: Long) {
        writableDatabase.delete("sync_queue", "id=?", arrayOf(rowId.toString()))
    }

    @Synchronized
    fun markFailed(rowId: Long, error: String) {
        writableDatabase.execSQL("UPDATE sync_queue SET attempts=attempts+1, last_error=? WHERE id=?", arrayOf(error.take(500), rowId))
    }

    @Synchronized
    fun pendingCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM sync_queue", null).use { c -> return if (c.moveToFirst()) c.getInt(0) else 0 }
    }
}

data class PendingOperation(val rowId: Long, val operationId: String, val entity: String, val method: String, val payload: String, val attempts: Int)

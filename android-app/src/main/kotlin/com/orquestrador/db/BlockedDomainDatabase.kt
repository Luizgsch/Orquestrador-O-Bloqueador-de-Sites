package com.orquestrador.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BlockedDomainDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {

    companion object {
        private const val DB_NAME = "orquestrador.db"
        private const val DB_VERSION = 1
        private const val TABLE_NAME = "blocked_domains"
        private const val COL_DOMAIN = "domain"
        private const val COL_CATEGORY = "category"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS $TABLE_NAME (
                $COL_DOMAIN TEXT NOT NULL PRIMARY KEY,
                $COL_CATEGORY TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_domain ON $TABLE_NAME ($COL_DOMAIN)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
        onCreate(db)
    }

    fun getDomainsForCategory(category: String): Set<String> {
        val domains = mutableSetOf<String>()
        readableDatabase.query(
            TABLE_NAME,
            arrayOf(COL_DOMAIN),
            "$COL_CATEGORY = ?",
            arrayOf(category),
            null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                domains.add(cursor.getString(0))
            }
        }
        return domains
    }

    fun getAllDomains(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        readableDatabase.query(
            TABLE_NAME,
            arrayOf(COL_DOMAIN, COL_CATEGORY),
            null, null, null, null, null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result[cursor.getString(0)] = cursor.getString(1)
            }
        }
        return result
    }

    fun upsertDomain(domain: String, category: String) {
        writableDatabase.run {
            execSQL(
                "INSERT OR REPLACE INTO $TABLE_NAME ($COL_DOMAIN, $COL_CATEGORY) VALUES (?, ?)",
                arrayOf(domain, category)
            )
        }
    }

    fun upsertDomains(domains: Map<String, String>) {
        writableDatabase.run {
            beginTransaction()
            try {
                for ((domain, category) in domains) {
                    execSQL(
                        "INSERT OR REPLACE INTO $TABLE_NAME ($COL_DOMAIN, $COL_CATEGORY) VALUES (?, ?)",
                        arrayOf(domain, category)
                    )
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    fun replaceDomains(domains: Map<String, String>) {
        writableDatabase.run {
            beginTransaction()
            try {
                execSQL("DELETE FROM $TABLE_NAME")
                for ((domain, category) in domains) {
                    execSQL(
                        "INSERT INTO $TABLE_NAME ($COL_DOMAIN, $COL_CATEGORY) VALUES (?, ?)",
                        arrayOf(domain, category)
                    )
                }
                setTransactionSuccessful()
            } finally {
                endTransaction()
            }
        }
    }

    fun getDomainCount(): Int {
        readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE_NAME", null).use { cursor ->
            cursor.moveToFirst()
            return cursor.getInt(0)
        }
    }

    fun clearAllDomains() {
        writableDatabase.delete(TABLE_NAME, null, null)
    }
}

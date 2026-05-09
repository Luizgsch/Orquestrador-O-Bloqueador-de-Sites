package com.orquestrador

import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager

/**
 * SQLite local com a tabela [TABLE] (domain, category), alinhada ao modelo Room futuro no Android.
 */
class BlockedDomainDatabase(private val dbPath: Path) {

    init {
        Files.createDirectories(dbPath.parent)
        DriverManager.getConnection(jdbcUrl()).use { conn ->
            conn.createStatement().use { st ->
                st.execute(
                    """
                    CREATE TABLE IF NOT EXISTS $TABLE (
                      domain TEXT NOT NULL PRIMARY KEY,
                      category TEXT NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute("CREATE INDEX IF NOT EXISTS idx_domain ON $TABLE (domain)")
            }
        }
    }

    private fun jdbcUrl(): String = "jdbc:sqlite:${dbPath.toAbsolutePath().normalize()}"

    /**
     * Substitui todo o conteúdo (transação única). Inclui literais e linhas com [category] `regex`
     * (campo [domain] guarda o padrão; não vão para `/etc/hosts` no Linux).
     */
    fun replaceAll(rows: Collection<Pair<String, String>>) {
        val deduped = rows
            .map { it.first.trim() to it.second.trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .distinctBy { it.first.lowercase() }
        DriverManager.getConnection(jdbcUrl()).use { conn ->
            conn.autoCommit = false
            try {
                conn.createStatement().use { it.execute("DELETE FROM $TABLE") }
                conn.prepareStatement(
                    "INSERT INTO $TABLE (domain, category) VALUES (?, ?)",
                ).use { ps ->
                    for ((domain, category) in deduped) {
                        ps.setString(1, domain)
                        ps.setString(2, category)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    /** Domínios literais para `/etc/hosts` (exclui regras só-regex). */
    fun listLiteralDomains(): List<String> {
        val sql =
            "SELECT domain FROM $TABLE WHERE lower(category) != ? ORDER BY domain COLLATE NOCASE"
        DriverManager.getConnection(jdbcUrl()).use { conn ->
            conn.prepareStatement(sql).use { ps ->
                ps.setString(1, CATEGORY_REGEX)
                return ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(rs.getString(1))
                        }
                    }
                }
            }
        }
    }

    fun count(): Int {
        DriverManager.getConnection(jdbcUrl()).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM $TABLE").use { rs ->
                    check(rs.next()) { "COUNT sem linha" }
                    return rs.getInt(1)
                }
            }
        }
    }

    fun upsertDomains(rows: Collection<Pair<String, String>>) {
        val deduped = rows
            .map { it.first.trim() to it.second.trim() }
            .filter { it.first.isNotEmpty() && it.second.isNotEmpty() }
            .distinctBy { it.first.lowercase() }
        DriverManager.getConnection(jdbcUrl()).use { conn ->
            conn.autoCommit = false
            try {
                conn.prepareStatement(
                    "INSERT OR REPLACE INTO $TABLE (domain, category) VALUES (?, ?)",
                ).use { ps ->
                    for ((domain, category) in deduped) {
                        ps.setString(1, domain)
                        ps.setString(2, category)
                        ps.addBatch()
                    }
                    ps.executeBatch()
                }
                conn.commit()
            } catch (e: Exception) {
                conn.rollback()
                throw e
            }
        }
    }

    fun findDomainsContaining(keywords: Collection<String>): List<Pair<String, String>> {
        val result = mutableListOf<Pair<String, String>>()
        val seen = mutableSetOf<String>()
        for (keyword in keywords) {
            val kw = keyword.trim().lowercase()
            if (kw.isEmpty()) continue
            val sql = "SELECT domain, category FROM $TABLE WHERE lower(domain) LIKE ?"
            DriverManager.getConnection(jdbcUrl()).use { conn ->
                conn.prepareStatement(sql).use { ps ->
                    ps.setString(1, "%$kw%")
                    ps.executeQuery().use { rs ->
                        while (rs.next()) {
                            val domain = rs.getString(1)
                            val category = rs.getString(2)
                            val key = domain.lowercase()
                            if (key !in seen) {
                                seen.add(key)
                                result.add(domain to category)
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    companion object {
        const val TABLE = "blocked_domains"
        const val CATEGORY_REGEX = "regex"
        const val CATEGORY_ENTERTAINMENT_MANGA = "entertainment_manga"

        fun defaultDbPath(): Path {
            val fromEnv = System.getenv("ORQUESTRADOR_DB")?.trim().orEmpty()
            if (fromEnv.isNotEmpty()) {
                return Path.of(fromEnv)
            }
            val opt = Path.of("/opt/orquestrador/orquestrador.db")
            if (Files.isDirectory(opt.parent) || Files.exists(opt)) {
                return opt
            }
            return Path.of(System.getProperty("user.home"), ".orquestrador", "orquestrador.db")
        }
    }
}

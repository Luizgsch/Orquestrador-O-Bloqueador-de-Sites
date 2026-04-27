package com.orquestrador

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.google.gson.reflect.TypeToken
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Persiste eventos de bloqueio em `logs.json` (array JSON) e expõe resumo do dia.
 *
 * Caminho: `ORQUESTRADOR_LOGS_JSON`, senão `logs.json` ao lado do SQLite padrão ([BlockedDomainDatabase.defaultDbPath]).
 *
 * De Java: [StatisticsManager.INSTANCE] (ex.: `StatisticsManager.INSTANCE.logApkDeleted(path)`).
 */
object StatisticsManager {

    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val listType = object : TypeToken<ArrayList<StatEvent>>() {}.type
    private val lock = Any()

    fun logApkDeleted(absolutePath: String) {
        val path = absolutePath.trim()
        if (path.isEmpty()) return
        appendEvent(
            StatEvent(
                timestamp = Instant.now().toString(),
                kind = KIND_APK_DELETED,
                count = 1,
                path = path,
            ),
        )
    }

    /** Registra aplicação do bloco em `/etc/hosts` ([distractionCount] = domínios literais distintos). */
    fun logNetworkHostsApplied(distractionCount: Int) {
        if (distractionCount <= 0) return
        appendEvent(
            StatEvent(
                timestamp = Instant.now().toString(),
                kind = KIND_NETWORK_HOSTS,
                count = distractionCount,
                path = null,
            ),
        )
    }

    /**
     * Lê `logs.json` e imprime no terminal:
     * `Total de distrações bloqueadas hoje: X`
     */
    fun printTodayDistractionsSummary() {
        synchronized(lock) {
            val path = logsPath()
            val today = LocalDate.now(ZoneId.systemDefault())
            val events = readAll(path)
            var total = 0
            for (ev in events) {
                if (ev.kind != KIND_APK_DELETED && ev.kind != KIND_NETWORK_HOSTS) continue
                val day = parseEventLocalDate(ev.timestamp) ?: continue
                if (day != today) continue
                total += ev.count.coerceAtLeast(0)
            }
            println("Total de distrações bloqueadas hoje: $total")
        }
    }

    private fun logsPath(): Path {
        val env = System.getenv("ORQUESTRADOR_LOGS_JSON")?.trim().orEmpty()
        if (env.isNotEmpty()) {
            return Path.of(env)
        }
        return BlockedDomainDatabase.defaultDbPath().parent.resolve("logs.json")
    }

    private fun appendEvent(event: StatEvent) {
        synchronized(lock) {
            val path = logsPath()
            Files.createDirectories(path.parent)
            val list = readAll(path)
            list.add(event)
            writeAll(path, list)
        }
    }

    private fun readAll(path: Path): ArrayList<StatEvent> {
        if (!Files.isRegularFile(path)) {
            return ArrayList()
        }
        return try {
            val text = Files.readString(path, StandardCharsets.UTF_8).trim()
            if (text.isEmpty()) {
                return ArrayList()
            }
            gson.fromJson<ArrayList<StatEvent>>(text, listType) ?: ArrayList()
        } catch (_: Exception) {
            ArrayList()
        }
    }

    private fun writeAll(path: Path, list: List<StatEvent>) {
        val tmp = path.resolveSibling(path.fileName.toString() + ".tmp")
        val json = gson.toJson(list)
        Files.writeString(tmp, json, StandardCharsets.UTF_8)
        try {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: Exception) {
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun parseEventLocalDate(isoTimestamp: String): LocalDate? =
        try {
            Instant.parse(isoTimestamp).atZone(ZoneId.systemDefault()).toLocalDate()
        } catch (_: Exception) {
            null
        }

    const val KIND_APK_DELETED = "apk_deleted"
    const val KIND_NETWORK_HOSTS = "network_hosts"
}

/** Linha do array em `logs.json`. */
data class StatEvent(
    val timestamp: String = "",
    val kind: String = "",
    val count: Int = 1,
    val path: String? = null,
)

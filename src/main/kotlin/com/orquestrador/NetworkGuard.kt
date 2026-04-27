package com.orquestrador

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Duration

object NetworkGuard {

    private const val START_MARKER = "# START GUARD"
    private const val END_MARKER = "# END GUARD"

    /** Lista StevenBlack “porn-only” (hosts no formato clássico). */
    const val DEFAULT_STEVENBLACK_PORN_HOSTS =
        "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts"

    val hostsPath: Path = Path.of("/etc/hosts")

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(30))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    /**
     * Domínios conhecidos de repositórios / distribuição APK (expandido quando a palavra-chave
     * `apk` estiver em [forbiddenKeywords]).
     */
    val apkRepositoryDomains: Set<String> = setOf(
        "apkpure.com",
        "www.apkpure.com",
        "m.apkpure.com",
        "apkmirror.com",
        "www.apkmirror.com",
        "apkcombo.com",
        "www.apkcombo.com",
        "apkpure.net",
        "www.apkpure.net",
        "aptoide.com",
        "www.aptoide.com",
        "en.uptodown.com",
        "uptodown.com",
        "www.uptodown.com",
        "apk.support",
        "www.apk.support",
        "apkmody.io",
        "www.apkmody.io",
        "happymod.com",
        "www.happymod.com",
        "apkdone.com",
        "www.apkdone.com",
        "revdl.com",
        "www.revdl.com",
        "androidapksfree.com",
        "www.androidapksfree.com",
    )

    /** Palavras-chave → domínios extra (minúsculas). */
    private fun domainsForKeyword(keyword: String): Set<String> =
        when (keyword.lowercase()) {
            "apk" -> apkRepositoryDomains
            else -> emptySet()
        }

    fun readHosts(): String = Files.readString(hostsPath, StandardCharsets.UTF_8)

    /**
     * Baixa um arquivo hosts público (0.0.0.0 / 127.0.0.1) e retorna os hostnames encontrados.
     */
    fun fetchHostsListDomains(url: String): Set<String> {
        if (url.isBlank()) return emptySet()
        val req = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofMinutes(2))
            .GET()
            .build()
        val body = httpClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body()
        return parseHostsFileDomains(body)
    }

    fun parseHostsFileDomains(text: String): Set<String> {
        val out = linkedSetOf<String>()
        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) continue
            val hash = trimmed.indexOf('#')
            val before = if (hash >= 0) trimmed.substring(0, hash).trim() else trimmed
            val parts = before.split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (parts.size < 2) continue
            val ip = parts[0]
            if (ip !in setOf("127.0.0.1", "0.0.0.0", "::", "fe00::")) continue
            for (i in 1 until parts.size) {
                val host = parts[i].trim().lowercase()
                if (host.isEmpty() || host == "localhost" || host == "local") continue
                if (host.startsWith("#")) break
                out.add(host)
            }
        }
        return out
    }

    /**
     * Monta todas as linhas (domain, category) para gravar no SQLite e depois em `/etc/hosts`
     * (literais apenas; [BlockedDomainDatabase.CATEGORY_REGEX] não vai para hosts).
     */
    fun buildBlockedRows(
        manualDomains: Collection<String>,
        blocklistUrl: String?,
        forbiddenKeywords: Collection<String>,
        regexPatterns: Collection<String>,
    ): List<Pair<String, String>> {
        val rows = ArrayList<Pair<String, String>>()
        for (d in manualDomains) {
            val n = d.trim().lowercase()
            if (n.isNotEmpty()) rows.add(n to "default")
        }
        val url = blocklistUrl?.trim().orEmpty()
        if (url.isNotEmpty()) {
            try {
                for (d in fetchHostsListDomains(url)) {
                    rows.add(d to "remote_blocklist")
                }
            } catch (e: Exception) {
                System.err.println("[NetworkGuard] Falha ao baixar lista ($url): ${e.message}")
            }
        }
        for (kw in forbiddenKeywords) {
            val k = kw.trim().lowercase()
            if (k.isEmpty()) continue
            for (d in domainsForKeyword(k)) {
                rows.add(d.lowercase() to "keyword_$k")
            }
        }
        for (pattern in regexPatterns) {
            val p = pattern.trim()
            if (p.isEmpty()) continue
            rows.add(p to BlockedDomainDatabase.CATEGORY_REGEX)
        }
        return rows.distinctBy { it.first.lowercase() }
    }

    /** Sincroniza o banco e reescreve o bloco GUARD em [hostsPath]. */
    fun syncDatabaseAndHosts(
        db: BlockedDomainDatabase,
        manualDomains: Collection<String>,
        blocklistUrl: String?,
        forbiddenKeywords: Collection<String>,
        regexPatterns: Collection<String>,
    ) {
        val rows = buildBlockedRows(manualDomains, blocklistUrl, forbiddenKeywords, regexPatterns)
        db.replaceAll(rows)
        writeGuardBlock(db.listLiteralDomains())
    }

    fun writeGuardBlock(domains: Collection<String>) {
        val outer = if (Files.exists(hostsPath)) {
            readHosts()
        } else {
            ""
        }
        val withoutOld = removeGuardRegion(outer)
        val block = buildBlock(domains)
        val trimmed = withoutOld.trimEnd()
        val newContent = buildString {
            if (trimmed.isNotEmpty()) {
                append(trimmed)
                appendLine()
            }
            appendLine()
            append(block)
            if (!block.endsWith("\n")) appendLine()
        }
        Files.writeString(
            hostsPath,
            newContent,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE,
        )
        val literalCount = domains.asSequence()
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .distinct()
            .count()
        StatisticsManager.logNetworkHostsApplied(literalCount)
    }

    internal fun removeGuardRegion(text: String): String {
        val startIdx = text.indexOf(START_MARKER)
        val endIdx = text.indexOf(END_MARKER)
        if (startIdx == -1 || endIdx == -1 || endIdx < startIdx) {
            return text
        }
        val endAfter = endIdx + END_MARKER.length
        val afterEnd = if (endAfter < text.length && text[endAfter] == '\n') {
            endAfter + 1
        } else {
            endAfter
        }
        val before = text.substring(0, startIdx).trimEnd()
        val after = text.substring(afterEnd).trimStart()
        return when {
            before.isEmpty() && after.isEmpty() -> ""
            before.isEmpty() -> after
            after.isEmpty() -> before
            else -> before + System.lineSeparator() + after
        }
    }

    internal fun buildBlock(domains: Collection<String>): String {
        val unique = linkedMapOf<String, String>()
        for (d in domains) {
            val normalized = d.trim()
            if (normalized.isEmpty()) continue
            val key = normalized.lowercase()
            if (key !in unique) {
                unique[key] = normalized
            }
        }
        return buildString {
            appendLine(START_MARKER)
            for (domain in unique.values) {
                append("127.0.0.1")
                append('\t')
                appendLine(domain)
            }
            appendLine(END_MARKER)
        }.trimEnd() + System.lineSeparator()
    }

    fun forbiddenKeywordsFromEnv(): List<String> {
        val raw = System.getenv("ORQUESTRADOR_FORBIDDEN_KEYWORDS")?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').map { it.trim().lowercase() }.filter { it.isNotEmpty() }
    }

    fun blocklistUrlFromEnv(): String? {
        val explicit = System.getenv("ORQUESTRADOR_BLOCKLIST_URL")?.trim().orEmpty()
        if (explicit == "0" || explicit.equals("off", ignoreCase = true)) {
            return null
        }
        if (explicit.isNotEmpty()) {
            return explicit
        }
        return DEFAULT_STEVENBLACK_PORN_HOSTS
    }

    /** Padrões separados por vírgula; cada um gravado com category [BlockedDomainDatabase.CATEGORY_REGEX]. */
    fun regexPatternsFromEnv(): List<String> {
        val raw = System.getenv("ORQUESTRADOR_BLOCK_REGEX")?.trim().orEmpty()
        if (raw.isEmpty()) return emptyList()
        return raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    }
}

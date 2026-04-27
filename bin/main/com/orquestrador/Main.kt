package com.orquestrador

import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Inicia o [FileSentinel] em thread separada, aplica regras de [NetworkGuard] ao subir, e
 * permanece ativo até um comando de parada (stdin) ou sinal/parada do processo (modo serviço).
 */
fun main() {
    val serviceMode = serviceModeFromEnv()
    val manualBlocked = listOf(
        "x.com",
        "reddit.com",
    )
    val dbPath = BlockedDomainDatabase.defaultDbPath()
    val db = BlockedDomainDatabase(dbPath)
    val sentinel: FileSentinel
    try {
        sentinel = FileSentinel(resolveTargetDownloadsPath())
    } catch (e: Exception) {
        System.err.println("[Orquestrador] Não foi possível iniciar FileSentinel: ${e.message}")
        e.printStackTrace()
        return
    }
    try {
        println("[Orquestrador] SQLite: ${dbPath.toAbsolutePath()}")
        println("[Orquestrador] Aplicando regras em ${NetworkGuard.hostsPath} …")
        NetworkGuard.syncDatabaseAndHosts(
            db = db,
            manualDomains = manualBlocked,
            blocklistUrl = NetworkGuard.blocklistUrlFromEnv(),
            forbiddenKeywords = NetworkGuard.forbiddenKeywordsFromEnv(),
            regexPatterns = NetworkGuard.regexPatternsFromEnv(),
        )
        println(
            "[Orquestrador] Bloco GUARD atualizado; ${db.count()} linha(s) em ${BlockedDomainDatabase.TABLE} " +
                "(literais em /etc/hosts; category='${BlockedDomainDatabase.CATEGORY_REGEX}' só no banco).",
        )
        StatisticsManager.printTodayDistractionsSummary()
    } catch (e: Exception) {
        System.err.println("[Orquestrador] Erro ao gravar hosts/DB (precisa ser root para /etc/hosts). ${e.message}")
        e.printStackTrace()
        return
    }
    val stopped = CountDownLatch(1)
    val stopAction = {
        if (stopped.count == 1L) {
            println("[Orquestrador] Encerrando monitor de arquivos…")
            sentinel.shutdown()
            stopped.countDown()
        }
    }
    val sentinelThread = Thread {
        try {
            sentinel.run()
        } catch (e: Exception) {
            System.err.println("[Orquestrador] FileSentinel: ${e.message}")
            e.printStackTrace()
        }
    }
    sentinelThread.name = "FileSentinel"
    sentinelThread.isDaemon = false
    Runtime.getRuntime().addShutdownHook(
        java.lang.Thread(stopAction, "OrquestradorShutdown"),
    )
    sentinelThread.start()

    val mutualStop = AtomicBoolean(false)
    val mutualRaw = System.getenv("ORQUESTRADOR_MUTUAL_GUARD_UNIT")?.trim().orEmpty()
    val mutualUnit: String? = when {
        mutualRaw.isEmpty() -> "guardian-watchdog.service"
        mutualRaw in setOf("-", "off", "disabled") -> null
        else -> mutualRaw
    }
    val mutualThread = if (serviceMode && mutualUnit != null) {
        Thread({
            println(
                "[Orquestrador] Mútua sobrevivência: verificando '$mutualUnit' a cada 10s " +
                    "(desative: ORQUESTRADOR_MUTUAL_GUARD_UNIT=off).",
            )
            while (!mutualStop.get() && !Thread.currentThread().isInterrupted) {
                try {
                    if (SystemdPeer.startIfInactive(mutualUnit)) {
                        println("[Orquestrador] iniciado peer systemd: $mutualUnit")
                    }
                } catch (e: Exception) {
                    System.err.println("[Orquestrador] watchdog systemd: ${e.message}")
                }
                try {
                    Thread.sleep(10_000)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }, "OrquestradorMutualGuard").also { it.isDaemon = true }
    } else {
        null
    }
    mutualThread?.start()

    if (serviceMode) {
        println(
            "[Orquestrador] em execução (modo serviço: parada com systemctl stop ou sinais; stdin está desconectado).",
        )
        try {
            stopped.await()
        } catch (_: InterruptedException) {
            stopAction()
        }
        mutualStop.set(true)
        mutualThread?.interrupt()
        mutualThread?.join(2_000)
        sentinelThread.join(5_000)
    } else {
        println(
            """
            [Orquestrador] em execução. FileSentinel na thread '${sentinelThread.name}'.
            Digite sair, stop, quit, exit ou parar para encerrar, ou use Ctrl+C.
            """.trimIndent(),
        )
        val ioThread = Thread({
            while (true) {
                val line = readlnOrNull() ?: run {
                    stopAction()
                    return@Thread
                }
                if (line.trim().lowercase() in setOf("sair", "stop", "quit", "exit", "parar")) {
                    stopAction()
                    return@Thread
                }
            }
        }, "OrquestradorStdin")
        ioThread.isDaemon = true
        ioThread.start()
        try {
            stopped.await()
        } catch (_: InterruptedException) {
            stopAction()
        }
        mutualStop.set(true)
        mutualThread?.interrupt()
        ioThread.join(1_000)
        sentinelThread.join(5_000)
    }
    println("[Orquestrador] Término.")
}

/** Pasta vigiada pelo [FileSentinel]: variável de ambiente `TARGET_DOWNLOADS` ou `/home/luiz/Downloads`. */
private fun resolveTargetDownloadsPath(): Path {
    val fromEnv = System.getenv("TARGET_DOWNLOADS")?.trim().orEmpty()
    return Path.of(if (fromEnv.isEmpty()) "/home/luiz/Downloads" else fromEnv)
}

/** true com -Dorquestrador.service=true, ORQUESTRADOR_SERVICE=1, ou sem TTY; false com ORQUESTRADOR_SERVICE=0. */
private fun serviceModeFromEnv(): Boolean {
    if (listOf("0", "no", "false", "off").contains(
            System.getenv("ORQUESTRADOR_SERVICE")?.lowercase().orEmpty()
        ) || System.getProperty("orquestrador.service", "") == "false"
    ) {
        return false
    }
    if (System.getProperty("orquestrador.service", "").equals("true", ignoreCase = true)) {
        return true
    }
    if (listOf("1", "true", "yes").contains(System.getenv("ORQUESTRADOR_SERVICE")?.lowercase().orEmpty())) {
        return true
    }
    return System.console() == null
}

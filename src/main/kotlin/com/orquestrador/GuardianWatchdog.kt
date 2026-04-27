package com.orquestrador

/**
 * Processo dedicado: a cada [intervalSec] garante que a unidade peer systemd esteja ativa.
 * Instale como `guardian-watchdog.service` (root) para mútua sobrevivência com `orquestrador-bloqueador`.
 *
 * Variáveis de ambiente:
 * - `GUARDIAN_PEER_SERVICE` (default `orquestrador-bloqueador.service`)
 * - `GUARDIAN_INTERVAL_SEC` (default `10`)
 */
fun main() {
    val intervalSec = System.getenv("GUARDIAN_INTERVAL_SEC")?.toLongOrNull()?.coerceAtLeast(1L) ?: 10L
    val peer = System.getenv("GUARDIAN_PEER_SERVICE")?.trim()?.ifEmpty { null }
        ?: "orquestrador-bloqueador.service"
    println("[GuardianWatchdog] monitorando '$peer' a cada ${intervalSec}s (Ctrl+C ou SIGTERM para sair)")
    while (!Thread.currentThread().isInterrupted) {
        try {
            if (SystemdPeer.startIfInactive(peer)) {
                println("[GuardianWatchdog] iniciado: $peer")
            }
        } catch (e: Exception) {
            System.err.println("[GuardianWatchdog] erro: ${e.message}")
        }
        try {
            Thread.sleep(intervalSec * 1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            break
        }
    }
    println("[GuardianWatchdog] encerrado.")
}

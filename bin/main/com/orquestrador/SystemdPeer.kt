package com.orquestrador

/**
 * Checagem mínima de unidades systemd (root típico nos serviços do orquestrador).
 */
object SystemdPeer {

    fun isActive(unit: String): Boolean {
        val pb = ProcessBuilder("systemctl", "is-active", "--quiet", unit)
        pb.redirectError(ProcessBuilder.Redirect.DISCARD)
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD)
        return try {
            pb.start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }

    fun startIfInactive(unit: String): Boolean {
        if (isActive(unit)) return false
        return try {
            val pb = ProcessBuilder("systemctl", "start", unit)
            pb.redirectError(ProcessBuilder.Redirect.INHERIT)
            pb.redirectOutput(ProcessBuilder.Redirect.INHERIT)
            pb.start().waitFor() == 0
        } catch (_: Exception) {
            false
        }
    }
}

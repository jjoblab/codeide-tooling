package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HeapEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Surveillance du tas de l'orchestrateur (§4.6) : coroutine à tick
 * périodique — remplace le `ScheduledExecutorService` de l'ancienne
 * version, comportement identique. [instantane] répond aussi à la demande
 * ([HeapRequest]), le tick périodique est borné par `--heap-intervalle-ms`
 * (0 = désactivé : à la demande seule).
 */
internal class HeapMonitor(
    private val bus: EventBus,
) {
    private val portee = CoroutineScope(SupervisorJob())
    private var tic: Job? = null

    /** Démarre l'émission périodique (idempotent, sans effet si [intervalleMs] ≤ 0). */
    fun demarrer(intervalleMs: Long) {
        if (intervalleMs <= 0) return
        if (tic?.isActive == true) return
        tic =
            portee.launch {
                while (isActive) {
                    delay(intervalleMs)
                    bus.publier(capturer())
                }
            }
    }

    /** Instantané à la demande (§3.2 : réponse à `HeapRequest`). */
    fun instantane(): HeapEvent = capturer()

    /** Arrête le tick périodique (idempotent). */
    fun arreter() {
        tic?.cancel()
        tic = null
        portee.coroutineContext[Job]?.cancel()
    }

    private fun capturer(): HeapEvent {
        val runtime = Runtime.getRuntime()
        return HeapEvent(
            id = nouvelId(),
            protocolVersion = GradleProtocol.PROTOCOL_VERSION,
            usedMb = (runtime.totalMemory() - runtime.freeMemory()) / MEGA_OCTET,
            maxMb = runtime.maxMemory() / MEGA_OCTET,
        )
    }

    private companion object {
        /** Exemption detekt ciblée (règle 16) : MagicNumber — 1 Mo = 1 << 20
         * est la définition même de l'unité, le nommer serait du bruit. */
        @Suppress("MagicNumber")
        const val MEGA_OCTET: Long = 1_048_576L
    }
}

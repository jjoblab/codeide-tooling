package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ProtocolJson
import jo.codeide.tooling.protocol.ToolingEvent
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.BlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Bus de diffusion des événements vers l'app (§4.3 : les handlers y
 * publient, le socket y consomme).
 *
 * Contrat de NON-PERTE (§5.2 adapté au serveur) : la file est bornée et
 * [publier] BLOQUE l'émetteur sous contre-pression (jamais `DROP_OLDEST` —
 * perdre une ligne de sortie de build serait trompeur). Les émetteurs sont
 * les coroutines des handlers ET les fils internes de Gradle
 * ([StreamingOutputStream], [ProgressBridge]) : bloquer un fil de pompage
 * de sortie Gradle est du contrôle de flux légitime, pas une fuite.
 *
 * Un unique fil consommateur écrit sur le socket : aucune frame ne peut
 * s'entrelacer (§4.5).
 */
internal interface EventBus {
    /** Publie un événement — bloquant sous contre-pression, jamais perdant. */
    fun publier(evenement: ToolingEvent)

    /** Démarre le consommateur unique (idempotent). */
    fun demarrer()

    /** Arrête le consommateur et vide ce qui reste (idempotent). */
    fun arreter()
}

/** Implémentation sur le socket de l'app. */
internal class EventBusSocket(
    private val socket: SocketClient,
) : EventBus {
    /** File bornée : 8192 événements en attente maximum. */
    private val file: BlockingQueue<ToolingEvent> = ArrayBlockingQueue(CAPACITE)

    @Volatile
    private var consommateur: Thread? = null

    @Volatile
    private var actif = false

    override fun publier(evenement: ToolingEvent) {
        // put() bloquant : contre-pression sur l'émetteur, aucune perte.
        file.put(evenement)
    }

    override fun demarrer() {
        if (consommateur?.isAlive == true) return
        actif = true
        consommateur =
            thread(isDaemon = true, name = "gradle-server-evenements") {
                try {
                    while (actif || file.isNotEmpty()) {
                        val evenement = file.poll(POLL_VIDE_MS, TimeUnit.MILLISECONDS) ?: continue
                        socket.envoyer(ProtocolJson.encoder(evenement).encodeToByteArray())
                    }
                } catch (interruption: InterruptedException) {
                    // arrêt demandé : vider ce qui reste puis sortir
                    while (file.isNotEmpty()) {
                        val evenement = file.poll() ?: break
                        envoyerMalgreTout(evenement)
                    }
                } catch (t: Throwable) {
                    Journal.error("consommateur d'événements interrompu : ${t.message}", t)
                }
            }
    }

    private fun envoyerMalgreTout(evenement: ToolingEvent) {
        runCatching { socket.envoyer(ProtocolJson.encoder(evenement).encodeToByteArray()) }
    }

    override fun arreter() {
        actif = false
        consommateur?.interrupt()
        consommateur?.join(ATTENTE_JOINTURE_MS)
    }

    private companion object {
        /** Capacité de la file (rafales de sortie de build, §5.2 : large). */
        const val CAPACITE = 8192

        /** Attente max d'un événement avant de réévaluer l'état d'activité. */
        const val POLL_VIDE_MS = 100L

        /** Attente max de fin du consommateur à l'arrêt. */
        const val ATTENTE_JOINTURE_MS = 2_000L
    }
}

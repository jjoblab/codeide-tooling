package jo.codeide.tooling.client

import jo.codeide.tooling.protocol.FrameCodec
import jo.codeide.tooling.protocol.ProtocolJson
import jo.codeide.tooling.protocol.ProtocolMessage
import jo.codeide.tooling.protocol.ToolingEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import kotlin.concurrent.thread

/**
 * Session de dialogue avec l'orchestrateur (§7.3) — le point de couture
 * des tests : l'implémentation réelle ([SessionSocketAndroid]) parle à un
 * `LocalSocket`, les tests la remplacent par une session factice.
 *
 * Interface publique depuis G4 : le daemon (`tooling:daemon`) accepte des
 * sessions via son hôte de socket puis les injecte dans
 * [GradleApiImpl.ouvrirSession] — et son test bout-en-bout (§7.4) branche
 * une session JVM sur un vrai socket Unix.
 */
interface SessionTooling {
    /**
     * Envoie un message (requête vers l'orchestrateur, ou réponse de
     * handshake — les deux sens partagent le canal, écritures
     * sérialisées par l'implémentation).
     */
    suspend fun envoyer(message: ProtocolMessage)

    /**
     * Événements entrants, dans l'ordre d'émission — le flux se TERMINE
     * à la déconnexion (EOF propre ou perte) : le consommateur en déduit
     * l'état [jo.codeide.core.domain.EtatConnexion.DECONNECTEE].
     */
    val evenements: Flow<ToolingEvent>

    /**
     * Installe le chemin rapide de routage (v0.50.0, ADR 0081) et dit si
     * la session l'empruntera.
     *
     * LECTURE PAR FIL DÉDIÉ : la session réelle lit son socket dans UN fil
     * qui n'appartient à AUCUN dispatcheur de coroutines — le réveil est
     * celui du noyau à l'arrivée des octets, deux changements de contexte
     * de scheduler PAR FRAME en moins (retour terrain v0.49.0 : sous la
     * charge CPU de l'app — diagnostics post-sync, classpath LSP — la
     * lecture coroutine s'égouttait à 0,5–2 s par frame, sorties de build
     * reçues 1 à 14 s après leur émission alors que l'orchestrateur avait
     * tout écrit immédiatement).
     *
     * Le chemin installé DOIT être non suspendant ([GradleApiImpl.router]
     * l'est par construction : `trySend` vers voies non bornées, écritures
     * atomiques, promesses complétées) — il est appelé par le fil de
     * lecture, au vrai point de réception : le pong et la latence de
     * transport sont marqués AVANT toute file coroutine.
     *
     * @return `true` si la session route chaque événement via ce chemin
     * (la collecte de [evenements] ne ROUTE plus — elle ne reste que le
     * signal de fin de flux) ; `false` si elle ne le peut pas (sessions
     * factices : la collecte du flux reste la voie de routage, comme
     * avant).
     */
    fun acheminerVia(chemin: (ToolingEvent) -> Unit): Boolean = false

    /** Ferme la session (idempotent). */
    fun fermer()
}

/**
 * Session réelle sur un `LocalSocket` Android connecté à l'orchestrateur.
 *
 * Écritures : un verrou sérialise les frames — un unique écrivain à la
 * fois, jamais d'entrelacement (miroir du consommateur unique côté
 * orchestrateur, §4.5). Lectures : FIL DÉDIÉ hors dispatcheurs (v0.50.0,
 * ADR 0081) — le `read()` bloquant dort en appel système, le noyau le
 * réveille à l'arrivée des octets ; aucune saturation du scheduler (le
 * pool `Dispatchers.IO` est partagé avec le travail LSP/diagnostics de
 * l'app) ne peut plus retarder la lecture, EOF = fin de flux.
 */
internal class SessionSocketAndroid(
    private val socket: android.net.LocalSocket,
) : SessionTooling {
    private val verrouEcriture = Mutex()

    /** Chemin rapide installé par [acheminerVia] — appelé par le fil. */
    @Volatile
    private var cheminRapide: ((ToolingEvent) -> Unit)? = null

    /** Fil de lecture dédié (démarré à la première collecte, v0.50.0). */
    @Volatile
    private var filLecture: Thread? = null

    override suspend fun envoyer(message: ProtocolMessage) {
        val payload = ProtocolJson.encoder(message).encodeToByteArray()
        withContext(Dispatchers.IO) {
            verrouEcriture.withLock {
                FrameCodec.writeFrame(socket.outputStream, payload)
            }
        }
    }

    override fun acheminerVia(chemin: (ToolingEvent) -> Unit): Boolean {
        cheminRapide = chemin
        return true
    }

    // Exemption detekt ciblée (règle 16) : SwallowedException — EOF et
    // IOException sont ici le SIGNAL DE FIN DU FLUX (déconnexion propre
    // ou sale, §5.2/§7.5) : la complétion du flux EST l'information, la
    // remonter en échec ferait prendre une déconnexion pour un bug de test.
    @Suppress("SwallowedException")
    override val evenements: Flow<ToolingEvent> =
        flow {
            val canal = Channel<ToolingEvent>(Channel.UNLIMITED)
            val fil =
                thread(
                    isDaemon = true,
                    name = NOM_FIL_LECTURE,
                    priority = Thread.NORM_PRIORITY + 1,
                ) {
                    try {
                        while (true) {
                            // Lecture DIRECTE, hors de tout dispatcheur : le
                            // fil dort en appel système, réveillé par le noyau.
                            val payload = FrameCodec.readFrame(socket.inputStream)
                            val evenement =
                                ProtocolJson.decoderEvenement(String(payload, Charsets.UTF_8))
                            // Chemin rapide non suspendant (router) AU POINT
                            // DE LECTURE : pong et latence transport marqués
                            // avant toute file coroutine.
                            runCatching { cheminRapide?.invoke(evenement) }
                            // Le canal ne porte plus que le signal de fin de
                            // flux (et garde le flux froid vivant) : trySend
                            // non borné, jamais bloquant, jamais perdant.
                            canal.trySend(evenement)
                        }
                    } catch (fin: java.io.EOFException) {
                        // Déconnexion propre de l'orchestrateur : fin du flux.
                    } catch (fermee: IOException) {
                        // Canal fermé ou corrompu : fin du flux — l'état de
                        // connexion est déduit par le consommateur (§7.5).
                    } finally {
                        canal.close()
                    }
                }
            filLecture = fil
            try {
                // Consommé pour la mémoire et la fin de flux ; le ROUTAGE a
                // déjà eu lieu dans le fil (acheminerVia a retourné true).
                for (evenement in canal) emit(evenement)
            } finally {
                filLecture = null
                // Le read() bloquant ne sort pas de son appel système sur
                // interrupt() : fermer() referme le socket juste après et
                // lève l'IOException qui termine le fil.
                fil.interrupt()
            }
        }

    override fun fermer() {
        runCatching { socket.close() }
    }

    private companion object {
        /** Nom du fil de lecture dédié (journaux de terrain, v0.50.0). */
        const val NOM_FIL_LECTURE = "tooling-lecteur"
    }
}

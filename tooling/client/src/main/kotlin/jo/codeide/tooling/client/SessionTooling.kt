package jo.codeide.tooling.client

import jo.codeide.tooling.protocol.FrameCodec
import jo.codeide.tooling.protocol.ProtocolJson
import jo.codeide.tooling.protocol.ProtocolMessage
import jo.codeide.tooling.protocol.ToolingEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

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

    /** Ferme la session (idempotent). */
    fun fermer()
}

/**
 * Session réelle sur un `LocalSocket` Android connecté à l'orchestrateur.
 *
 * Écritures : un verrou sérialise les frames — un unique écrivain à la
 * fois, jamais d'entrelacement (miroir du consommateur unique côté
 * orchestrateur, §4.5). Lectures : flux froid bloquant sur `Dispatchers.IO`,
 * EOF = fin de flux.
 */
internal class SessionSocketAndroid(
    private val socket: android.net.LocalSocket,
) : SessionTooling {
    private val verrouEcriture = Mutex()

    override suspend fun envoyer(message: ProtocolMessage) {
        val payload = ProtocolJson.encoder(message).encodeToByteArray()
        withContext(Dispatchers.IO) {
            verrouEcriture.withLock {
                FrameCodec.writeFrame(socket.outputStream, payload)
            }
        }
    }

    // Exemption detekt ciblée (règle 16) : SwallowedException — EOF et
    // IOException sont ici le SIGNAL DE FIN DU FLUX (déconnexion propre
    // ou sale, §5.2/§7.5) : la complétion du flux EST l'information, la
    // remonter en échec ferait prendre une déconnexion pour un bug de test.
    @Suppress("SwallowedException")
    override val evenements: Flow<ToolingEvent> =
        flow {
            try {
                while (true) {
                    val payload =
                        withContext(Dispatchers.IO) {
                            FrameCodec.readFrame(socket.inputStream)
                        }
                    emit(ProtocolJson.decoderEvenement(String(payload, Charsets.UTF_8)))
                }
            } catch (fin: java.io.EOFException) {
                // Déconnexion propre de l'orchestrateur : fin du flux.
            } catch (fermee: IOException) {
                // Canal fermé ou corrompu : fin du flux — l'état de
                // connexion est déduit par le consommateur (§7.5).
            }
        }

    override fun fermer() {
        runCatching { socket.close() }
    }
}

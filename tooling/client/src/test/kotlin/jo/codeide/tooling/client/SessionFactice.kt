package jo.codeide.tooling.client

import jo.codeide.tooling.protocol.ProtocolMessage
import jo.codeide.tooling.protocol.ToolingEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import java.io.IOException
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Session factice (§7.3 — « SocketClient fake ») : les messages reçus
 * sont enregistrés puis confiés à un script de réaction qui émet les
 * événements simulés de l'orchestrateur.
 */
internal class SessionFactice(
    private var reagir: suspend (ProtocolMessage, SessionFactice) -> Unit = { _, _ -> },
) : SessionTooling {
    /** Messages reçus, dans l'ordre d'envoi. */
    val messages = CopyOnWriteArrayList<ProtocolMessage>()

    // Canal NON BORNÉ : miroir du tampon de l'OS sur une vraie socket —
    // les événements émis AVANT l'abonnement du pompe attendent au lieu de
    // disparaître (un flux chaud à rejeu nul introduirait une course que la
    // production n'a pas : la vraie session lit un socket).
    private val bus = Channel<ToolingEvent>(Channel.UNLIMITED)

    /** La session a-t-elle été fermée ? */
    var fermee: Boolean = false
        private set

    /** Simule l'échec des envois ultérieurs (connexion morte). */
    fun refuserEnvois() {
        refusEnvois = true
    }

    @Volatile
    private var refusEnvois: Boolean = false

    override suspend fun envoyer(message: ProtocolMessage) {
        if (refusEnvois) throw EnvoiImpossible()
        messages += message
        reagir(message, this)
    }

    override val evenements: Flow<ToolingEvent> = bus.receiveAsFlow()

    /** Simule un événement émis par l'orchestrateur. */
    suspend fun emettre(evenement: ToolingEvent) {
        bus.send(evenement)
    }

    /** Simule la FIN du flux d'événements (déconnexion de l'orchestrateur). */
    fun deconnecter() {
        bus.close()
    }

    override fun fermer() {
        fermee = true
    }
}

/** Erreur d'envoi simulée (connexion morte). */
internal class EnvoiImpossible(
    message: String = "connexion morte (simulation)",
) : IOException(message)

package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ErrorCode
import jo.codeide.tooling.protocol.ErrorResponse
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HelloRequest
import jo.codeide.tooling.protocol.HelloResponse
import jo.codeide.tooling.protocol.ProtocolJson
import java.io.IOException

/**
 * Échec typé du handshake (§4.4/§1.6) : la cause passe au [Journal] pour
 * l'opérateur, le message au canal de sortie — jamais de comportement
 * indéfini ensuite (le serveur s'arrête proprement, code de sortie dédié).
 */
internal class EchecHandshake(
    message: String,
    val codeSortie: Int,
) : IOException(message)

/**
 * Négociation d'ouverture (§4.1/§4.4) : l'orchestrateur (client du socket)
 * envoie le [HelloRequest] portant le secret, l'app valide et répond
 * [HelloResponse]. Un désaccord de version produit un message clair
 * ([ErrorResponse] PROTOCOL_VERSION_MISMATCH vers l'app, [EchecHandshake]
 * vers l'opérateur) — jamais un comportement indéfini plus tard dans la
 * session (§1.6).
 *
 * Exemption detekt ciblée (règle 16) : ThrowsCount — chaque `throw`
 * distingue une cause de rejet DIFFÉRENTE (version, refus explicite de
 * l'app, réponse inattendue) avec son code de sortie dédié ; même
 * justification que le FrameCodec de G1.
 */
@Suppress("ThrowsCount")
internal object Handshake {
    /** Version côté app simulée par les tests (§7.2). */
    val VERSION_APP_DEFAUT: Int = GradleProtocol.PROTOCOL_VERSION

    /**
     * Envoie le [HelloRequest] (secret de [config]) et attend la réponse de
     * l'app. Le secret n'apparaît JAMAIS dans le [Journal] (§4.4).
     */
    fun negocier(
        socket: SocketClient,
        secret: String,
    ): HelloResponse {
        socket.envoyer(
            ProtocolJson
                .encoder(
                    HelloRequest(
                        id = nouvelId(),
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        clientVersion = ServerVersion.CURRENT,
                        handshakeSecret = secret,
                    ),
                ).encodeToByteArray(),
        )
        val reponse =
            ProtocolJson.decoderMessage(String(socket.lireFrame(), Charsets.UTF_8))
        when {
            reponse is HelloResponse && reponse.protocolVersion == GradleProtocol.PROTOCOL_VERSION -> {
                Journal.info(
                    "handshake accepté — app ${reponse.serverVersion}, " +
                        "fonctionnalités : ${reponse.supportedFeatures.joinToString()}",
                )
                return reponse
            }

            reponse is HelloResponse -> {
                val message =
                    "version de protocole incompatible : l'app parle la v${reponse.protocolVersion}, " +
                        "l'orchestrateur la v${GradleProtocol.PROTOCOL_VERSION} — mets l'app à jour"
                envoyerErreur(socket, "handshake", ErrorCode.PROTOCOL_VERSION_MISMATCH, message)
                throw EchecHandshake(message, CODE_VERSION)
            }

            reponse is ErrorResponse -> {
                Journal.error("l'app a refusé le handshake : ${reponse.message}")
                throw EchecHandshake("l'app a refusé le handshake : ${reponse.message}", CODE_REFUS)
            }

            else -> {
                val message = "réponse de handshake inattendue : ${reponse::class.simpleName}"
                throw EchecHandshake(message, CODE_REFUS)
            }
        }
    }

    /** L'app doit recevoir l'erreur typée avant la fermeture (§1.6). */
    private fun envoyerErreur(
        socket: SocketClient,
        idRequete: String,
        code: ErrorCode,
        message: String,
    ) {
        runCatching {
            socket.envoyer(
                ProtocolJson
                    .encoder(
                        ErrorResponse(
                            id = nouvelId(),
                            protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                            requestId = idRequete,
                            code = code,
                            message = message,
                        ),
                    ).encodeToByteArray(),
            )
        }
    }

    /** Codes de sortie dédiés (opérateur ≠ version ≠ refus). */
    const val CODE_VERSION: Int = 3
    const val CODE_REFUS: Int = 4
}

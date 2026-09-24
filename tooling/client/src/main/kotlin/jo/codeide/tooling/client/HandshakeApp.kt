package jo.codeide.tooling.client

import jo.codeide.tooling.protocol.ErrorCode
import jo.codeide.tooling.protocol.ErrorResponse
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HelloRequest
import jo.codeide.tooling.protocol.HelloResponse
import java.io.IOException

/**
 * Refus typé du handshake côté app (§4.4) : secret invalide, version
 * incompatible ou réponse inattendue — l'orchestrateur a reçu l'erreur
 * protocolaire AVANT la fermeture, l'app sait précisément pourquoi.
 *
 * Classe publique depuis G4 : [GradleSocketServer.accepterUneFois] la
 * lève à travers l'API publique de l'écoute — le daemon doit l'attraper
 * par type pour décider d'un échec DÉFINITIF (version incompatible) ou
 * d'une nouvelle tentative.
 */
class EchecHandshakeClient(
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

/**
 * Validation du handshake CÔTÉ APP (§4.4) : AUCUNE requête ne peut
 * atteindre un handler avant validation du secret — le refus ferme la
 * connexion après avoir envoyé l'erreur typée à l'orchestrateur (§1.6 :
 * message clair, jamais un comportement indéfini).
 *
 * Logique pure sur une [SessionTooling] — testée avec une session factice
 * (§7.3) : la colle `LocalServerSocket`/`LocalSocket` de
 * [GradleSocketServer] n'est pas exécutable sous Robolectric (aucune
 * shadow — vérifié 4.17), c'est précisément pour cela que cette couture
 * existe.
 */
internal object HandshakeApp {
    /**
     * Valide le [HelloRequest] reçu de l'orchestrateur : répond
     * [HelloResponse] et retourne si tout concorde, REFUSE (erreur typée
     * envoyée puis connexion fermée) et lève [EchecHandshakeClient] sinon.
     *
     * @param session session porteuse (réponse/envoi + fermeture au refus).
     * @param recu le HelloRequest décodé de la première frame.
     * @param secretAttendu secret généré par l'app pour CE démarrage.
     * @param versionApp version de l'app, annoncée dans la réponse.
     */
    suspend fun valider(
        session: SessionTooling,
        recu: HelloRequest,
        secretAttendu: String,
        versionApp: String,
    ) {
        when {
            recu.handshakeSecret != secretAttendu -> {
                refuser(
                    session,
                    recu.id,
                    ErrorCode.HANDSHAKE_FAILED,
                    "secret de handshake invalide",
                )
                throw EchecHandshakeClient("secret de handshake invalide — connexion rejetée et fermée")
            }

            recu.protocolVersion != GradleProtocol.PROTOCOL_VERSION -> {
                refuser(
                    session,
                    recu.id,
                    ErrorCode.PROTOCOL_VERSION_MISMATCH,
                    "l'app parle la v${GradleProtocol.PROTOCOL_VERSION}, l'orchestrateur la v${recu.protocolVersion}",
                )
                throw EchecHandshakeClient("version de protocole incompatible : ${recu.protocolVersion}")
            }

            else -> {
                session.envoyer(
                    HelloResponse(
                        id = recu.id,
                        protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        serverVersion = versionApp,
                        gradleToolingApiVersion = "embarquée",
                        supportedFeatures = FONCTIONNALITES,
                    ),
                )
            }
        }
    }

    /** L'orchestrateur reçoit l'erreur typée AVANT la fermeture (§1.6). */
    private suspend fun refuser(
        session: SessionTooling,
        idRequete: String,
        code: ErrorCode,
        message: String,
    ) {
        runCatching {
            session.envoyer(
                ErrorResponse(
                    id = idRequete,
                    protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                    requestId = idRequete,
                    code = code,
                    message = message,
                ),
            )
        }
        session.fermer()
    }

    /** Fonctionnalités annoncées au handshake (§3.2). */
    val FONCTIONNALITES =
        setOf("build", "sync", "tasks", "dependencies", "model", "cancel", "heap")
}

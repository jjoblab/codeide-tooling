package jo.codeide.tooling.client

import jo.codeide.tooling.protocol.FrameCodec
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HelloRequest
import jo.codeide.tooling.protocol.ProtocolJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException

/**
 * Écoute du socket de l'orchestrateur (§5.1) : **l'app est le serveur** —
 * l'écoute s'ouvre AVANT le lancement du process JVM (élimine tout fichier
 * de découverte et tout polling), celui-ci s'y connecte dès son démarrage.
 *
 * Namespace FICHIER (§4.4) : le client JVM de l'orchestrateur (JDK 17,
 * `UnixDomainSocketAddress`) ne sait joindre QUE des chemins de fichiers
 * — le namespace abstrait par défaut de `LocalServerSocket(String)` lui
 * est inaccessible (vérifié dans le source AOSP : le constructeur String
 * bind en ABSTRACT). L'astuce API publique : `LocalSocket.bind(FILESYSTEM)`
 * occupe l'adresse fichier, puis `LocalServerSocket(FileDescriptor)` pose
 * le `listen()` sur ce descripteur — tout est public depuis l'API 8, sans
 * réflexion sur les interfaces cachées.
 *
 * Défense en profondeur (§4.4) : socket sous un répertoire privé `0700`,
 * résidu d'une session précédente supprimé, permissions du fichier socket
 * resserrées au mieux — le secret de handshake reste OBLIGATOIRE dans
 * tous les cas (le prompt ne conditionne jamais la sécurité à cette seule
 * vérification).
 *
 * Colle `LocalSocket`/`LocalServerSocket` non exécutée sous Robolectric
 * (aucune shadow, 4.17 — vérifié) : la LOGIQUE de validation vit dans
 * [HandshakeApp] (testée via session factice) et la session dans
 * [SessionSocketAndroid] ; ce fichier reste la couche mince d'écoute.
 *
 * Classe publique depuis G4 : le daemon (`tooling:daemon`) l'enveloppe
 * dans son hôte de socket (cette classe reste la seule à parler
 * `LocalSocket`) — ses méthodes sont le contrat d'écoute.
 */
class GradleSocketServer(
    private val dossierSocket: File,
    private val versionApp: String,
) {
    private var porteur: android.net.LocalSocket? = null
    private var serveur: android.net.LocalServerSocket? = null

    /** Chemin du socket à communiquer au process orchestrateur (G4). */
    val cheminSocket: File
        get() = File(dossierSocket, GradleProtocol.SOCKET_NAME)

    /** Ouvre l'écoute — répertoire privé `0700`, résidu retiré. */
    fun ouvrir() {
        if (!dossierSocket.isDirectory) {
            dossierSocket.mkdirs()
        }
        dossierSocket.setReadable(false, false)
        dossierSocket.setWritable(false, false)
        dossierSocket.setExecutable(false, false)
        dossierSocket.setReadable(true, true)
        dossierSocket.setWritable(true, true)
        dossierSocket.setExecutable(true, true)

        cheminSocket.delete()

        // Occuper l'adresse fichier (bind) puis y poser l'écoute : le
        // porteur garde la propriété du descripteur (la fermeture passe
        // par lui), le LocalServerSocket n'y ajoute que listen/accept.
        val porteur = android.net.LocalSocket()
        porteur.bind(
            android.net.LocalSocketAddress(
                cheminSocket.absolutePath,
                android.net.LocalSocketAddress.Namespace.FILESYSTEM,
            ),
        )
        this.porteur = porteur
        serveur = android.net.LocalServerSocket(porteur.fileDescriptor)

        // Permissions du fichier socket : au mieux « 0600 » (best effort
        // §4.4 — le secret reste la protection réelle).
        cheminSocket.setReadable(false, false)
        cheminSocket.setWritable(false, false)
        cheminSocket.setExecutable(false, false)
        cheminSocket.setReadable(true, true)
        cheminSocket.setWritable(true, true)
    }

    /**
     * Attend UNE connexion et négocie le handshake : la première frame
     * doit être le [HelloRequest] de l'orchestrateur, validé par
     * [HandshakeApp.valider] — AUCUNE requête n'atteint un handler avant
     * validation du secret (§4.4).
     *
     * Le `accept()` d'`android.net.LocalServerSocket` n'est PAS
     * interruptible (libcore, contrairement aux canaux NIO interruptibles
     * de l'hôte JVM des tests) : un simple
     * `withTimeout { runInterruptible { serveur.accept() } }` ne revient
     * JAMAIS tant que personne ne se connecte — gel silencieux constaté
     * sur le terrain en v0.35.0 (daemon figé, aucune relance, aucun
     * diagnostic). L'accept vit donc dans un job détaché, attendu sous
     * [delaiMs] ; au délai, un client FACTICE rejoint l'écoute pour
     * DÉBLOQUER le fil bloqué (le noyau complète sa connexion dans le
     * backlog : `accept(2)` rend la main — seul réveil garanti), ce que
     * le fil a fini par accepter est refermé, puis l'échec typé
     * [IOException] remonte au daemon qui consomme la tentative et
     * relance (ADR 0061).
     *
     * Exemption detekt ciblée (règle 16) : ThrowsCount — chaque `throw`
     * porte une cause DIFFÉRENTE (délai expiré, première frame illisible,
     * première frame inattendue) avec son traitement propre ; même
     * justification que le Handshake de G2.
     *
     * @param secretAttendu secret généré par l'app pour CE démarrage.
     * @param delaiMs délai d'attente de la connexion (fenêtre du daemon,
     * lancement de la JVM compris).
     * @throws EchecHandshakeClient secret invalide, version incompatible
     * ou réponse inattendue — l'orchestrateur a été informé puis fermé.
     * @throws IOException délai expiré sans connexion de l'orchestrateur,
     * ou écoute défaillante — la tentative est consommable par le daemon.
     */
    @Suppress("ThrowsCount")
    suspend fun accepterUneFois(
        secretAttendu: String,
        delaiMs: Long,
    ): SessionTooling {
        val serveur = requireNotNull(this.serveur) { "ouvrir() n'a pas été appelé" }

        val porteeAccept = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val acceptation: Deferred<Result<android.net.LocalSocket>> =
            porteeAccept.async { runCatching { serveur.accept() } }
        val canal =
            try {
                withTimeout(delaiMs) { acceptation.await().getOrThrow() }
            } catch (delai: TimeoutCancellationException) {
                rejeterAcceptEnRetard(acceptation)
                throw IOException(
                    "orchestrateur silencieux à la connexion (délai de $delaiMs ms)",
                    delai,
                )
            } finally {
                porteeAccept.cancel()
            }

        val session = SessionSocketAndroid(canal)

        // Première frame : obligatoirement le HelloRequest de
        // l'orchestrateur — consommée SANS passer par le flux d'événements
        // (le pompe ne verra que la suite, à partir de la frame 2).
        val premiere =
            try {
                ProtocolJson.decoderMessage(
                    String(
                        withContext(Dispatchers.IO) {
                            FrameCodec.readFrame(canal.inputStream)
                        },
                        Charsets.UTF_8,
                    ),
                )
            } catch (t: Throwable) {
                session.fermer()
                throw EchecHandshakeClient("première frame illisible : ${t.message}", t)
            }

        if (premiere !is HelloRequest) {
            session.fermer()
            throw EchecHandshakeClient("première frame inattendue : ${premiere::class.simpleName}")
        }

        HandshakeApp.valider(session, premiere, secretAttendu, versionApp)
        return session
    }

    /**
     * Réveille l'accept bloqué d'un délai expiré : un client factice
     * rejoint l'écoute (le noyau complète sa connexion dans le backlog —
     * `accept(2)` rend la main, seul réveil garanti d'un appel non
     * interruptible) et ce que le fil a fini par accepter (le factice,
     * ou l'orchestrateur trop tardif) est refermé — la tentative est
     * consommée, la relance partira sur un secret frais.
     *
     * Best effort borné : un réveil impossible n'entrave PAS la remontée
     * de l'échec au daemon (le fil restant bloqué sur une écoute que le
     * daemon referme de toute façon).
     */
    private suspend fun rejeterAcceptEnRetard(acceptation: Deferred<Result<android.net.LocalSocket>>) {
        runCatching {
            android.net.LocalSocket().apply {
                connect(
                    android.net.LocalSocketAddress(
                        cheminSocket.absolutePath,
                        android.net.LocalSocketAddress.Namespace.FILESYSTEM,
                    ),
                )
                close()
            }
        }
        runCatching {
            withTimeout(REVEIL_ACCEPT_MS) { acceptation.await() }
        }.getOrNull()?.getOrNull()?.close()
    }

    private companion object {
        /** Délai du réveil borné de l'accept bloqué (le factice est immédiat). */
        const val REVEIL_ACCEPT_MS: Long = 1_000L
    }

    /** Referme l'écoute (idempotent — le socket fichier est retiré). */
    fun fermer() {
        serveur?.let { runCatching { it.close() } }
        serveur = null
        porteur?.let { runCatching { it.close() } }
        porteur = null
        cheminSocket.delete()
    }
}

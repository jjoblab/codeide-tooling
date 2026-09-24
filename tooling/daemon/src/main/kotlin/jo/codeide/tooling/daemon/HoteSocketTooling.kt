package jo.codeide.tooling.daemon

import jo.codeide.tooling.client.GradleSocketServer
import jo.codeide.tooling.client.SessionTooling
import java.io.File

/**
 * Hôte de l'écoute du socket du tooling (§5.1) — couture des tests.
 *
 * Production : [HoteSocketAndroid] enveloppe le `GradleSocketServer` de
 * `tooling:client` (seul détenteur de la colle `LocalSocket`, ADR 0041).
 * Tests : un hôte JVM sur vrai socket Unix (bout-en-bout §7.4) ou un hôte
 * factice (logique du daemon sans Android).
 *
 * L'ordre imposé par §5.1 : [ouvrir] précède TOUJOURS le lancement du
 * process — le daemon y veille, l'hôte ne fait que l'exécuter.
 */
internal interface HoteSocketTooling {
    /** Chemin du socket à transmettre au process orchestrateur. */
    val cheminSocket: File

    /** Ouvre l'écoute (répertoire privé, résidu retiré). */
    fun ouvrir()

    /**
     * Attend UNE connexion et négocie le handshake.
     *
     * @param secretAttendu secret généré pour CE démarrage du daemon.
     * @param delaiMs délai d'attente de la connexion (`CONNECT_TIMEOUT_MS`).
     * @return session validée, prête à être pompée.
     * @throws jo.codeide.tooling.client.EchecHandshakeClient secret
     * invalide, version incompatible ou réponse inattendue.
     */
    suspend fun accepterUneFois(
        secretAttendu: String,
        delaiMs: Long,
    ): SessionTooling

    /** Referme l'écoute (idempotent). */
    fun fermer()
}

/**
 * Hôte production : délégation pure au `GradleSocketServer` de
 * `tooling:client` — la colle `LocalSocket`/`LocalServerSocket` reste
 * concentrée là-bas (aucune shadow Robolectric : filtrée du kover des
 * deux modules, même précédent que la colle Termux/JNI).
 */
internal class HoteSocketAndroid(
    private val serveur: GradleSocketServer,
) : HoteSocketTooling {
    override val cheminSocket: File
        get() = serveur.cheminSocket

    override fun ouvrir() = serveur.ouvrir()

    override suspend fun accepterUneFois(
        secretAttendu: String,
        delaiMs: Long,
    ): SessionTooling = serveur.accepterUneFois(secretAttendu, delaiMs)

    override fun fermer() = serveur.fermer()
}

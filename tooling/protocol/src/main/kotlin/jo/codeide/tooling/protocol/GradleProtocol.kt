package jo.codeide.tooling.protocol

/**
 * Constantes du protocole du tooling Gradle (prompt compagnon Tooling,
 * section 3.4).
 *
 * Une seule source de vérité partagée par l'app Android (serveur du
 * socket) et le process JVM orchestrateur (client) — un désaccord ici
 * se voit au handshake, pas au milieu d'un build.
 */
public object GradleProtocol {
    /** Version courante du protocole (négociée au handshake, §4.4/§3.2). */
    public const val PROTOCOL_VERSION: Int = 2

    /** Nom du fichier de socket (UDS) — fichier, pas namespace abstrait. */
    public const val SOCKET_NAME: String = "gradle.sock"

    /** Intervalle ping/pong du health check (§5.4). */
    public const val HEARTBEAT_INTERVAL_MS: Long = 5_000L

    /** Délai sans pong avant de déclarer le process mort (§5.4). */
    public const val HEARTBEAT_TIMEOUT_MS: Long = 15_000L

    /** Délai d'établissement de la connexion socket initiale (§7.5). */
    public const val CONNECT_TIMEOUT_MS: Long = 10_000L

    /** Tentatives de reconnexion maximales avant l'état Failed (§5.4). */
    public const val MAX_RECONNECT_ATTEMPTS: Int = 5

    /** Nom du JAR orchestrateur déployé par le daemon (§5.4). */
    public const val SERVER_JAR_NAME: String = "gradle-server.jar"

    /**
     * Taille maximale d'une frame annoncée (garde DoS mémoire, §3.1) :
     * 16 Mo au départ — la sortie d'un build voyage en petites lignes,
     * pas en monoblocs.
     */
    public const val MAX_FRAME_SIZE: Int = 16 * 1024 * 1024
}

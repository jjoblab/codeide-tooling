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
    /**
     * Version courante du protocole (négociée au handshake, §4.4/§3.2).
     *
     * v4 (tooling professionnel) : phases de sync RÉELLES ([SyncPhase] passe
     * de CONNEXION/MODELE_GRADLE/MODELE_IDEA à OUTILS → DISTRIBUTION →
     * DAEMON → CONFIGURATION → MODELE_TACHES → MODELE_IDE → DEPENDANCES →
     * CLASSPATHS — la connexion ne télécharge rien, l'ancienne phase mentait) ;
     * [SyncProgress] enrichi de détails (octets reçus/total, élément,
     * compteur n/total — champs à défaut, compatibles v3 côté décodage) ;
     * [ProgressEvent] porteur d'un détail de téléchargement structuré pour
     * le canal Build (addendum §6 : les téléchargements se voient pour TOUTE
     * action Gradle) ; [SyncRequest] et [ClasspathRequest] embarquent les
     * arguments réglés (`--offline`, arguments libres). Le handshake exige
     * l'égalité EXACTE des deux côtés : un orchestrateur v4 qui parlerait à
     * une app v3 est refusé avec un message clair au lieu d'échouer au
     * décodage d'un événement inconnu.
     */
    public const val PROTOCOL_VERSION: Int = 4

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

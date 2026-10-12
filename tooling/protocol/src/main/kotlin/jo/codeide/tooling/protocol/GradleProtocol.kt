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
     *
     * v5 (aperçu du tooling) : [SyncProgress.sautee] — une phase satisfaite
     * d'avance (distribution Gradle déjà en cache) se déclare SAUTÉE et non
     * achevée : la console n'affiche un téléchargement que s'il a LIEU, la
     * rangée porte « en cache ».
     *
     * v6 (prompt de suivi §2) : le drapeau `sautee` est SUPPRIMÉ — une phase
     * qui n'a pas lieu n'est plus émise du tout. Le catalogue affiché est
     * construit pour la sync en cours (étape non concernée = absente de la
     * liste). Cf. ADR 0073 à venir.
     *
     * v7 (ADR 0079 + mission Projet P1) : [SyncOutput] — une ligne de sortie
     * stdout/stderr de la synchronisation voyage avec le dossier du projet
     * (plus de `buildId` de requête sync jeté à la réception) ; la console
     * Sync affiche le vrai flux de Gradle comme la fenêtre Sync d'Android
     * Studio. Un nouveau TYPE d'événement n'est PAS décodable par un client
     * antérieur (le discriminant `type` échoue avant `ignoreUnknownKeys`).
     * Ajout aussi de [BuildScriptsRequest]/[BuildScriptsResult] (P1) et
     * du golden manquant [BuildInput] (correction dette technique v0.41.1).
     *
     * v8 (mission « LSP Java » étape 1, ADR 0113) : la source du classpath
     * est étendue aux modules ANDROID par le modèle v2 d'AGP
     * (builder-model via Tooling API) — les 26 modules Android du dépôt
     * de référence livrent enfin sources, classpath et android.jar
     * (IdeaProject restait vide pour eux). Conséquences sur le contrat :
     * [ClasspathModule.sourceDirs] ne porte plus que les racines PRINCIPALES
     * (les racines de TEST migrent vers [ClasspathModule.sourceDirsTest] —
     * les sourcesets main/test ne se mélangent plus, W5) ; les entrées
     * d'AAR sont livrées NORMALISÉES en [ClasspathKind.JAR] (jars extraits
     * par les transforms du cache Gradle, AGP le fait déjà — l'ancienne
     * livraison d'AAR bruts « telle quelle » d'ADR 0058 est remplacée),
     * [ClasspathEntry.aar] porte la provenance ; [ClasspathModule.androidJar]
     * (boot classpath), [ClasspathModule.fichiersGeneres],
     * [ClasspathModule.varianteAndroid] (variante RÉELLE, plus une
     * heuristique) et [ClasspathModule.ignore]/[ClasspathModule.avertissements]
     * sont désormais remplis. Champs optionnels → un client v7 n'est pas
     * cassé par le décodage, mais le handshake exige l'égalité exacte : les
     * deux bouts vivant dans le même APK, la sémantique change ensemble.
     *
     * v9 (mission « Cycle de vie » étape D, E9) :
     * [FermerProjetRequest]/[FermerProjetResult] — la fermeture de session
     * de projet gagne sa voie UI : l'app demande à l'orchestrateur de
     * libérer la connexion Tooling API du projet et de purger le cache de
     * sync quand la session se ferme (hors build/sync en vol — §2.1c).
     * Le démon applicatif continue de vivre (politique de durée de vie
     * reportée aux mesures §4.5/S6). Idempotente par construction
     * (fermeture d'un projet inconnu = no-op accusé). Un NOUVEAU TYPE de
     * requête/événement : indécodable par un pair v8 — le handshake exige
     * l'égalité exacte, les deux bouts vivant dans le même APK.
     */
    public const val PROTOCOL_VERSION: Int = 9

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

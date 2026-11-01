package jo.codeide.tooling.server

/**
 * Configuration de l'orchestrateur (§4.1) : `ServerConfig.parse(args)` lit
 * les arguments de lancement passés par le daemon (G4).
 *
 * `--socket <chemin>` et `--secret <secret>` sont obligatoires — le secret
 * ne transite QUE par cet argument (§4.4 : jamais écrit sur disque, jamais
 * loggé en clair). `--log-level` borne la verbosité du [Journal] ;
 * `--heap-intervalle-ms` pilote le [HeapMonitor] (0 = désactivé, §4.6).
 *
 * `--applog-repo <chemin>` (mission « Exécuter » R2, ADR 0103) : chemin du
 * dépôt maven local de la bibliothèque applog-runtime, déployé par le
 * daemon dans filesDir. ABSENT = injection désactivée (le réglage
 * utilisateur de CodeIDE coupe l'argument à la source — un serveur
 * démarré sans lui ne génère aucun script d'init).
 *
 * `--instru-transport` (Phase 1, règle 10) : DRAPEAU sans valeur — arme
 * l'[InstrumentationTransport] (T1, T1b, T2, T2b, T3b, métronome du
 * daemon). Désactivé par défaut : aucun changement de comportement, aucun
 * coût, aucune sortie pour les builds de tout le monde.
 *
 * `--banc-socket-seul` (Phase 1, §3.1b) : mode BANC — l'orchestrateur
 * émet une séquence `BuildStarted` → N `BuildOutput` → `BuildFinished`
 * à travers la VRAIE pile serveur (EventBus → SocketClient → FrameCodec)
 * SANS Gradle, puis reprend sa boucle normale (pong) : isole le coût par
 * frame du transport de tout ce qui touche Gradle. Paramètres :
 * `--banc-lignes <n>` (défaut 60 — le profil du journal du propriétaire),
 * `--banc-cadence-ms <n>` (défaut 0 = rafale), `--banc-longueur-ligne <n>`
 * (défaut 80), `--banc-contenu plain|ansi|utf8|multi` (défaut plain).
 */
internal data class ServerConfig(
    val cheminSocket: String,
    val secret: String,
    val niveauJournal: NiveauJournal,
    val intervalleTasMs: Long,
    val depotAppLog: String? = null,
    val instrumentationTransport: Boolean = false,
    val banc: ParametresBanc? = null,
) {
    /** Niveaux de journalisation de l'orchestrateur. */
    enum class NiveauJournal {
        INFO,
        WARN,
        ERROR,
    }

    /**
     * Paramètres du banc « socket seul » (Phase 1, §3.1b) — réglés par le
     * daemon quand le propriétaire arme le mode banc côté app.
     *
     * @property lignes nombre total de lignes émises.
     * @property cadenceMs écart entre deux lignes (0 = rafale maximale).
     * @property longueurLigne longueur CIBLE d'une ligne (payload réaliste).
     * @property contenu nature des lignes (voir [ContenuLigneBanc]).
     */
    data class ParametresBanc(
        val lignes: Int,
        val cadenceMs: Long,
        val longueurLigne: Int,
        val contenu: ContenuLigneBanc = ContenuLigneBanc.PLAIN,
    )

    /** Nature des lignes du banc (§3.1a — déluge paramétrable). */
    enum class ContenuLigneBanc {
        /** Lignes simples « banc 000042 …x… » (profil du journal). */
        PLAIN,

        /** Séquences ANSI (couleurs) — parseur et rendu sous déluge. */
        ANSI,

        /** UTF-8 multi-octets (accents, CJK) — réassemblage des suites. */
        UTF8,

        /** Traces d'exception multi-lignes (« at … », « Caused by: … »). */
        MULTI,
    }

    companion object {
        /** Intervalle par défaut du [HeapMonitor] (§4.6 : tick périodique). */
        const val INTERVALLE_TAS_MS_DEFAUT: Long = 5_000L

        /** Lignes du banc par défaut : le profil du journal du propriétaire. */
        const val BANC_LIGNES_DEFAUT: Int = 60

        /** Cadence du banc par défaut : rafale (0 ms entre deux lignes). */
        const val BANC_CADENCE_MS_DEFAUT: Long = 0L

        /** Longueur de ligne du banc par défaut (sortie Gradle réaliste). */
        const val BANC_LONGUEUR_LIGNE_DEFAUT: Int = 80

        /** Usage imprimé sur la sortie d'erreur en cas d'arguments invalides. */
        const val USAGE =
            "usage : --socket <chemin> --secret <secret> " +
                "[--log-level INFO|WARN|ERROR] [--heap-intervalle-ms <n>] " +
                "[--applog-repo <chemin>] [--instru-transport] " +
                "[--banc-socket-seul [--banc-lignes <n>] [--banc-cadence-ms <n>] " +
                "[--banc-longueur-ligne <n>] [--banc-contenu plain|ansi|utf8|multi]]"

        /**
         * Analyse les arguments de lancement. [IllegalArgumentException]
         * (message orienté opérateur) si la ligne est invalide.
         *
         * Exemption detekt ciblée (règle 16) : ThrowsCount — chaque `throw`
         * est une **issue typée d'usage** (argument inconnu, valeur manquante,
         * niveau/entier illisibles) ; même justification que le [FrameCodec]
         * de G1, fusionnerait en une erreur générique ce qui doit guider
         * l'opérateur précisément. LongMethod/CyclomaticComplexMethod —
         * une branche PAR option de la ligne de commande, c'est le dessin
         * même d'un analyseur d'arguments (même exemption que le routeur
         * `MessageDispatcher.traiter`) : les options du banc Phase 1 ont
         * porté la famille au-delà des seuils sans changer sa nature.
         */
        @Suppress("ThrowsCount", "LongMethod", "CyclomaticComplexMethod")
        fun analyser(args: Array<String>): ServerConfig {
            var cheminSocket: String? = null
            var secret: String? = null
            var niveau = NiveauJournal.INFO
            var intervalleTas = INTERVALLE_TAS_MS_DEFAUT
            var depotAppLog: String? = null
            var instrumentationTransport = false
            var bancArme = false
            var bancLignes = BANC_LIGNES_DEFAUT
            var bancCadenceMs = BANC_CADENCE_MS_DEFAUT
            var bancLongueurLigne = BANC_LONGUEUR_LIGNE_DEFAUT
            var bancContenu = ContenuLigneBanc.PLAIN

            var i = 0
            while (i < args.size) {
                when (args[i]) {
                    "--socket" -> {
                        cheminSocket = valeur(args, i)
                        i += 2
                    }

                    "--secret" -> {
                        secret = valeur(args, i)
                        i += 2
                    }

                    "--log-level" -> {
                        niveau =
                            when (valeur(args, i).uppercase()) {
                                "INFO" -> NiveauJournal.INFO

                                "WARN" -> NiveauJournal.WARN

                                "ERROR" -> NiveauJournal.ERROR

                                else -> throw IllegalArgumentException(
                                    "Niveau de journal inconnu : ${args[i + 1]} (INFO, WARN ou ERROR)",
                                )
                            }
                        i += 2
                    }

                    "--heap-intervalle-ms" -> {
                        intervalleTas =
                            valeur(args, i).toLongOrNull()
                                ?: throw IllegalArgumentException("Intervalle de tas illisible : ${args[i + 1]}")
                        i += 2
                    }

                    "--applog-repo" -> {
                        depotAppLog = valeur(args, i)
                        i += 2
                    }

                    "--instru-transport" -> {
                        instrumentationTransport = true
                        i += 1
                    }

                    "--banc-socket-seul" -> {
                        bancArme = true
                        i += 1
                    }

                    "--banc-lignes" -> {
                        bancLignes =
                            valeur(args, i).toIntOrNull()
                                ?: throw IllegalArgumentException("Lignes du banc illisibles : ${args[i + 1]}")
                        i += 2
                    }

                    "--banc-cadence-ms" -> {
                        bancCadenceMs =
                            valeur(args, i).toLongOrNull()
                                ?: throw IllegalArgumentException("Cadence du banc illisible : ${args[i + 1]}")
                        i += 2
                    }

                    "--banc-longueur-ligne" -> {
                        bancLongueurLigne =
                            valeur(args, i).toIntOrNull()
                                ?: throw IllegalArgumentException("Longueur de ligne illisible : ${args[i + 1]}")
                        i += 2
                    }

                    "--banc-contenu" -> {
                        bancContenu =
                            when (valeur(args, i).lowercase()) {
                                "plain" -> ContenuLigneBanc.PLAIN

                                "ansi" -> ContenuLigneBanc.ANSI

                                "utf8" -> ContenuLigneBanc.UTF8

                                "multi" -> ContenuLigneBanc.MULTI

                                else -> throw IllegalArgumentException(
                                    "Contenu du banc inconnu : ${args[i + 1]} (plain, ansi, utf8 ou multi)",
                                )
                            }
                        i += 2
                    }

                    else -> {
                        throw IllegalArgumentException("Argument inconnu : ${args[i]}")
                    }
                }
            }

            return ServerConfig(
                cheminSocket =
                    requireNotNull(cheminSocket) { "Argument obligatoire manquant : --socket <chemin>" },
                secret = requireNotNull(secret) { "Argument obligatoire manquant : --secret <secret>" },
                niveauJournal = niveau,
                intervalleTasMs = intervalleTas,
                depotAppLog = depotAppLog,
                instrumentationTransport = instrumentationTransport,
                banc =
                    if (bancArme) {
                        ParametresBanc(bancLignes, bancCadenceMs, bancLongueurLigne, bancContenu)
                    } else {
                        null
                    },
            )
        }

        private fun valeur(
            args: Array<String>,
            i: Int,
        ): String {
            require(i + 1 < args.size) { "Valeur manquante pour ${args[i]}" }
            return args[i + 1]
        }
    }
}

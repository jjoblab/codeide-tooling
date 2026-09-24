package jo.codeide.tooling.server

/**
 * Configuration de l'orchestrateur (§4.1) : `ServerConfig.parse(args)` lit
 * les arguments de lancement passés par le daemon (G4).
 *
 * `--socket <chemin>` et `--secret <secret>` sont obligatoires — le secret
 * ne transite QUE par cet argument (§4.4 : jamais écrit sur disque, jamais
 * loggé en clair). `--log-level` borne la verbosité du [Journal] ;
 * `--heap-intervalle-ms` pilote le [HeapMonitor] (0 = désactivé, §4.6).
 */
internal data class ServerConfig(
    val cheminSocket: String,
    val secret: String,
    val niveauJournal: NiveauJournal,
    val intervalleTasMs: Long,
) {
    /** Niveaux de journalisation de l'orchestrateur. */
    enum class NiveauJournal {
        INFO,
        WARN,
        ERROR,
    }

    companion object {
        /** Intervalle par défaut du [HeapMonitor] (§4.6 : tick périodique). */
        const val INTERVALLE_TAS_MS_DEFAUT: Long = 5_000L

        /** Usage imprimé sur la sortie d'erreur en cas d'arguments invalides. */
        const val USAGE =
            "usage : --socket <chemin> --secret <secret> " +
                "[--log-level INFO|WARN|ERROR] [--heap-intervalle-ms <n>]"

        /**
         * Analyse les arguments de lancement. [IllegalArgumentException]
         * (message orienté opérateur) si la ligne est invalide.
         *
         * Exemption detekt ciblée (règle 16) : ThrowsCount — chaque `throw`
         * est une **issue typée d'usage** (argument inconnu, valeur manquante,
         * niveau/entier illisibles) ; même justification que le FrameCodec
         * de G1, fusionnerait en une erreur générique ce qui doit guider
         * l'opérateur précisément.
         */
        @Suppress("ThrowsCount")
        fun analyser(args: Array<String>): ServerConfig {
            var cheminSocket: String? = null
            var secret: String? = null
            var niveau = NiveauJournal.INFO
            var intervalleTas = INTERVALLE_TAS_MS_DEFAUT

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

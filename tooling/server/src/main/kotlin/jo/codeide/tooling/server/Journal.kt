package jo.codeide.tooling.server

/**
 * Journal de l'orchestrateur (§4.4/§6) : ce process JVM séparé ne peut pas
 * appeler l'AppLogger de l'app — sa sortie d'erreur EST son canal de
 * journalisation. Le daemon (G4) la relancera vers l'app pour réinjection
 * dans le journal applicatif unifié, avec le tag distinct `gradle-server`
 * (section 6 du prompt Tooling). Le secret de handshake n'y passe JAMAIS.
 *
 * Exemption detekt ciblée (detekt-serveur-tooling.yml) : l'impression est
 * le seul canal disponible d'un process séparé — rien d'autre n'y est levé.
 */
internal object Journal {
    /** Tag distinct requis par la section 6 du prompt Tooling. */
    private const val TAG = "gradle-server"

    /** Niveau courant (fixé par `--log-level` au démarrage). */
    @Volatile
    var niveau: ServerConfig.NiveauJournal = ServerConfig.NiveauJournal.INFO

    /** Trace d'exécution, visible au niveau INFO. */
    fun info(message: String) {
        if (niveau <= ServerConfig.NiveauJournal.INFO) ecrire("INFO", message)
    }

    /** Anomalie récupérable, visible au niveau WARN. */
    fun warn(message: String) {
        if (niveau <= ServerConfig.NiveauJournal.WARN) ecrire("WARN", message)
    }

    /** Échec bloquant, toujours visible. */
    fun error(
        message: String,
        erreur: Throwable? = null,
    ) {
        if (niveau <= ServerConfig.NiveauJournal.ERROR) {
            ecrire("ERROR", message)
            erreur?.let { ecrire("ERROR", "${it::class.simpleName} : ${it.message}") }
        }
    }

    private fun ecrire(
        niveau: String,
        message: String,
    ) {
        System.err.println("[$TAG] [$niveau] $message")
    }
}

package jo.codeide.tooling.server

import java.util.UUID

/** Identifiants de corrélation des messages (§3.2 : `id` de tout message). */
internal fun nouvelId(): String = UUID.randomUUID().toString()

/**
 * Délais de garde de l'orchestrateur (§7.5 : « valeurs de départ,
 * ajustables mais jamais absentes »).
 */
internal object TimeoutsServeur {
    /** Délai maximal d'un build complet avant annulation forcée. */
    const val BUILD_MS: Long = 30 * 60_000L

    /** Délai maximal d'une synchronisation de projet. */
    const val SYNC_MS: Long = 5 * 60_000L

    /** Délai maximal de la liste des tâches. */
    const val TACHES_MS: Long = 30_000L

    /** Délai maximal du graphe de dépendances. */
    const val DEPENDANCES_MS: Long = 30_000L

    /** Délai maximal de résolution du classpath LSP (ADR 0058). */
    const val CLASSPATH_MS: Long = 5 * 60_000L

    /** Délai maximal de résolution d'un modèle de projet. */
    const val MODELE_MS: Long = 5 * 60_000L
}

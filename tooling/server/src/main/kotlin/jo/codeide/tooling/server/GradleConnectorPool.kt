package jo.codeide.tooling.server

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.gradle.tooling.GradleConnector
import org.gradle.tooling.ProjectConnection
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Pool de connexions Tooling API (§4.2) : une [ProjectConnection] par
 * répertoire de projet, mise en cache et fermée proprement à l'arrêt —
 * le VRAI daemon Gradle (process séparé, géré par Gradle lui-même) est
 * spawné/réutilisé en dessous, l'orchestrateur n'y touche jamais.
 *
 * [ConcurrentHashMap] : les lectures à chaud (connexions déjà établies)
 * sont sans verrou ; le Mutex ne protège que la création, en double
 * contrôle — deux requêtes concurrentes pour un même projet obtiennent la
 * même connexion (style coroutine, §4.2).
 */
internal class GradleConnectorPool : AutoCloseable {
    private val connexions = ConcurrentHashMap<String, ProjectConnection>()
    private val verrou = Mutex()

    /** Connexion (créée au besoin) pour le répertoire de projet [dossier]. */
    suspend fun connexion(dossier: File): ProjectConnection {
        val cle = dossier.canonicalFile.absolutePath
        connexions[cle]?.let { return it }
        return withContext(Dispatchers.IO) {
            verrou.withLock {
                connexions[cle]?.let { return@withLock it }
                Journal.info("connexion Tooling API ouverte pour $cle")
                GradleConnector
                    .newConnector()
                    .forProjectDirectory(dossier)
                    .connect()
                    .also { connexions[cle] = it }
            }
        }
    }

    /** Ferme toutes les connexions (hook d'arrêt, idempotent). */
    override fun close() {
        val aFermer = connexions.values.toList()
        connexions.clear()
        aFermer.forEach { connexion ->
            runCatching { connexion.close() }
                .onFailure { Journal.warn("fermeture de connexion Tooling API échouée : ${it.message}") }
        }
    }

    /**
     * Ferme la connexion du projet [dossier] UNIQUEMENT (mission « Cycle
     * de vie » étape D, v9 : fermeture PAR PROJET, E9) — la clé est
     * normalisée comme [connexion] (canonique) pour correspondre à ce
     * que l'app envoie (`canonicalPath`).
     *
     * Idempotente : un projet sans connexion ouverte est un no-op (la
     * requête est accusée quand même). Fermer une [ProjectConnection]
     * ne tue PAS le démon Gradle sous-jacent (géré par Gradle —
     * politique de durée de vie explicitement reportée aux mesures
     * §4.5/S6, docs/CYCLE_DE_VIE.md §2.2).
     */
    suspend fun fermer(dossier: File) {
        val cle = dossier.canonicalFile.absolutePath
        val connexion = connexions.remove(cle) ?: return
        withContext(Dispatchers.IO) {
            runCatching { connexion.close() }
                .onSuccess { Journal.info("connexion Tooling API fermée pour $cle (fermeture de projet)") }
                .onFailure { Journal.warn("fermeture de connexion Tooling API échouée pour $cle : ${it.message}") }
        }
    }
}

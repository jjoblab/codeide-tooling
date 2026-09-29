package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.TaskInfo
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache serveur de la dernière synchronisation réussie (v4, §3.1) : l'action
 * unique résout les modèles UNE fois et en dépose le résultat ici — le
 * listage des tâches et le classpath LSP répondent depuis le cache sans
 * re-résolution tant qu'aucune nouvelle sync ne l'a remplacé.
 *
 * Politique de fraîcheur : remplacé à CHAQUE sync aboutie (partielle
 * comprise — ses modèles résolus valent mieux qu'un échec sec) ; la clé est
 * le chemin canonique du projet. Aucune expiration temporelle : une
 * mutation du build se voit à la sync suivante, qui remplace l'entrée.
 */
internal class CacheSync {
    private val entrees = ConcurrentHashMap<String, Entree>()

    /** Dépose le résultat d'une sync (remplace l'entrée précédente). */
    fun deposer(
        projectDir: String,
        taches: List<TacheDto>,
        modules: List<ModuleClasspathDto>,
    ) {
        entrees[projectDir] =
            Entree(
                taches =
                    taches.map { dto ->
                        TaskInfo(path = dto.path, group = dto.group, displayName = dto.displayName)
                    },
                modules =
                    modules.map { dto ->
                        ClasspathModule(
                            name = dto.name,
                            sourceDirs = dto.sourceDirs,
                            entries =
                                dto.entries.map { entree ->
                                    ClasspathEntry(
                                        path = entree.path,
                                        kind =
                                            when (entree.kind) {
                                                "MODULE" -> ClasspathKind.MODULE
                                                "DOSSIER" -> ClasspathKind.DOSSIER
                                                "AAR" -> ClasspathKind.AAR
                                                else -> ClasspathKind.JAR
                                            },
                                        scope = entree.scope,
                                        sources = entree.sources,
                                    )
                                },
                        )
                    },
            )
    }

    /** Dernière entrée déposée pour [projectDir], `null` si aucune. */
    fun consulter(projectDir: String): Entree? = entrees[projectDir]

    /** Entrée du cache (types du protocole, prête à publier). */
    internal data class Entree(
        val taches: List<TaskInfo>,
        val modules: List<ClasspathModule>,
    )
}

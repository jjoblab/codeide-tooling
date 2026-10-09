package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.ResolvedDependenciesRequest
import jo.codeide.tooling.protocol.ResolvedDependenciesResult
import jo.codeide.tooling.protocol.ResolvedDependencyKind
import jo.codeide.tooling.protocol.ResolvedDependencyNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.io.File

/**
 * Arbre des dépendances résolues (mission Projet P4, ADR 0095) : utilise
 * le modèle `IdeaProject` de la Tooling API Gradle pour exposer
 * l'arbre des dépendances d'un module × configuration.
 *
 * Le modèle `IdeaProject` ne distingue pas versions demandée vs
 * retenue (c'est le rôle du rapport `dependencyInsight` — non exposé
 * par TAPI). Ici [versionDemandee] = [versionRetenue] = la version
 * résolue connue d'Idea, et [raison] est laissée vide. L'enrichissement
 * par `dependencyInsight` est un livrable P6+ (besoin d'exécuter une
 * tâche Gradle dédiée).
 *
 * Les transitives ne sont PAS exposées par `IdeaModule.dependencies`
 * (qui ne liste que les dépendances DIRECTES) — les nœuds ont donc
 * `transitives = emptyList()`. L'arbre transitif complet nécessiterait
 * le modèle `ImmutableDependencies` ou une tâche Gradle dédiée
 * (`dependencyInsight --configuration <conf>`).
 */
@Suppress("TooManyFunctions")
internal class ResolvedDependenciesHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
) {
    /** Publie [ResolvedDependenciesResult] : arbre résolu du module. */
    suspend fun resoudre(requete: ResolvedDependenciesRequest) {
        val dossier = File(requete.projectDir)
        val racine =
            withContext(Dispatchers.IO) {
                val idea = pool.connexion(dossier).model(IdeaProject::class.java).get()
                idea.modules.all
                    .firstOrNull { module: IdeaModule -> module.gradleProject.path == requete.module }
                    ?.let { module -> extraireArbre(module, requete.configuration) }
                    ?: emptyList()
            }
        bus.publier(
            ResolvedDependenciesResult(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                module = requete.module,
                configuration = requete.configuration,
                racine = racine,
            ),
        )
    }

    /** Extrait les dépendances directes d'un [module] Idea. */
    private fun extraireArbre(
        module: IdeaModule,
        configuration: String,
    ): List<ResolvedDependencyNode> =
        module.dependencies.all
            .map { dep -> versNoeud(dep, configuration) }
            .toList()

    /** Convertit une dépendance Idea en [ResolvedDependencyNode]. */
    private fun versNoeud(
        dep: org.gradle.tooling.model.idea.IdeaDependency,
        configuration: String,
    ): ResolvedDependencyNode =
        when (dep) {
            is org.gradle.tooling.model.idea.IdeaModuleDependency -> {
                ResolvedDependencyNode(
                    group = "",
                    name = dep.targetModuleName ?: "",
                    versionDemandee = "",
                    versionRetenue = "",
                    configuration = configuration,
                    type = ResolvedDependencyKind.PROJECT,
                    raison = "",
                    transitives = emptyList(),
                )
            }

            is IdeaSingleEntryLibraryDependency -> {
                val moduleVersion = dep.gradleModuleVersion
                ResolvedDependencyNode(
                    group = moduleVersion?.group ?: "",
                    name = moduleVersion?.name ?: "",
                    versionDemandee = moduleVersion?.version ?: "",
                    versionRetenue = moduleVersion?.version ?: "",
                    configuration = configuration,
                    type = ResolvedDependencyKind.LIBRARY,
                    raison = "",
                    transitives = emptyList(),
                )
            }

            else -> {
                ResolvedDependencyNode(
                    group = "",
                    name = "",
                    versionDemandee = "",
                    versionRetenue = "",
                    configuration = configuration,
                    type = ResolvedDependencyKind.FILE,
                    raison = "",
                    transitives = emptyList(),
                )
            }
        }
}

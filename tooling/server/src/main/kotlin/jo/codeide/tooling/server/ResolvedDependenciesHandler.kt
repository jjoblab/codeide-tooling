package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.ResolvedDependenciesRequest
import jo.codeide.tooling.protocol.ResolvedDependenciesResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Arbre des dépendances résolues (mission Projet P4, ADR 0095) : utilise
 * le modèle `IdeaProject` de la Tooling API Gradle pour exposer
 * l'arbre des dépendances d'un module × configuration.
 *
 * E2 (Phase 2 — cache d'empreinte) : la sync dépose l'arbre des
 * dépendances DIRECTES de chaque module au moment où elle résout déjà le
 * modèle IdeaProject (extraction gratuite, [versDependances]) ; ce
 * handler répond alors SANS re-résoudre (3,81 s bureau par requête
 * répétée, mesuré dans l'audit) et ne touche Gradle que pour un module
 * jamais synchronisé — auquel cas il complète le cache pour la fois
 * suivante. La clé de fraîcheur est celle du projet ENTIER : une
 * modification de build invalide tout, jamais un arbre périmé.
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
internal class ResolvedDependenciesHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
    /** Cache d'empreinte (E2) — `null` : comportement d'avant (Gradle toujours). */
    private val cache: CacheSyncPersistant? = null,
) {
    /** Publie [ResolvedDependenciesResult] : arbre résolu du module. */
    suspend fun resoudre(requete: ResolvedDependenciesRequest) {
        val dossier = File(requete.projectDir)
        val depuisCache = cache?.dependancesDe(requete.projectDir, requete.module)
        val racine =
            if (depuisCache != null) {
                // E2 : l'étiquette de configuration est celle de la REQUÊTE
                // (l'arbre direct d'Idea ne dépend pas de la configuration).
                depuisCache.map { it.copy(configuration = requete.configuration) }
            } else {
                withContext(Dispatchers.IO) {
                    val idea =
                        pool
                            .connexion(dossier)
                            .model(org.gradle.tooling.model.idea.IdeaProject::class.java)
                            .get()
                    idea.modules.all
                        .firstOrNull { module -> module.gradleProject.path == requete.module }
                        ?.versNoeudsDependances()
                        ?: emptyList()
                }.also { resolu ->
                    // Premier accès hors sync : l'arbre rejoint le cache pour
                    // les fois suivantes (SANS étiquette de configuration —
                    // celle de la requête s'applique au moment de servir,
                    // comme pour les arbres déposés par la sync).
                    if (resolu.isNotEmpty()) {
                        cache?.deposerDependances(requete.projectDir, requete.module, resolu)
                    }
                }.map { it.copy(configuration = requete.configuration) }
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
}

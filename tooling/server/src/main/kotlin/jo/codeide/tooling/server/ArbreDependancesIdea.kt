package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ResolvedDependencyKind
import jo.codeide.tooling.protocol.ResolvedDependencyNode
import org.gradle.tooling.model.idea.IdeaDependency
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency

// Arbre des dépendances DIRECTES du modèle IdeaProject (E2, Phase 2) —
// conversion PARTAGÉE entre la sync (pré-remplissage du cache
// d'empreinte : le modèle est déjà résolu, l'extraction est gratuite)
// et le ResolvedDependenciesHandler (premier accès hors sync).
//
// Limites héritées du modèle Idea (ADR 0095) : pas de distinction
// demandée/retenue (d'où versionDemandee = versionRetenue), pas de
// transitives (IdeaModule.dependencies ne liste que les DIRECTES).
// La configuration du nœud reste VIDE ici : elle est l'étiquette de la
// REQUÊTE, appliquée au moment de servir.

/** Arbre des dépendances directes PAR CHEMIN de module (racine `:app`…). */
internal fun IdeaProject.versDependances(): Map<String, List<ResolvedDependencyNode>> =
    modules.all.associate { module -> module.cheminGradle() to module.versNoeudsDependances() }

/** Dépendances directes d'UN module Idea, en nœuds du protocole. */
internal fun IdeaModule.versNoeudsDependances(): List<ResolvedDependencyNode> =
    dependencies.all.map { dependance -> versNoeudDependance(dependance) }

/** Conversion d'une dépendance Idea en nœud résolu du protocole. */
private fun versNoeudDependance(dep: IdeaDependency): ResolvedDependencyNode =
    when (dep) {
        is IdeaModuleDependency -> {
            ResolvedDependencyNode(
                group = "",
                name = dep.targetModuleName ?: "",
                versionDemandee = "",
                versionRetenue = "",
                configuration = "",
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
                configuration = "",
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
                configuration = "",
                type = ResolvedDependencyKind.FILE,
                raison = "",
                transitives = emptyList(),
            )
        }
    }

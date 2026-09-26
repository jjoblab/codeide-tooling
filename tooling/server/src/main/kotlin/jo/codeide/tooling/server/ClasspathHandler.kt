package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ClasspathEntry
import jo.codeide.tooling.protocol.ClasspathKind
import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.ClasspathRequest
import jo.codeide.tooling.protocol.ClasspathResult
import jo.codeide.tooling.protocol.GradleProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.gradle.tooling.model.idea.IdeaModule
import org.gradle.tooling.model.idea.IdeaModuleDependency
import org.gradle.tooling.model.idea.IdeaProject
import org.gradle.tooling.model.idea.IdeaSingleEntryLibraryDependency
import java.io.File

/**
 * Classpath compilé d'un projet pour les LSP (ADR 0058 — comme Android
 * Studio prépare l'index du projet à la sync) : chaque module livre ses
 * répertoires SOURCES et son classpath COMPILÉ — jars, AARs et dossiers
 * de classes, modules frères portés par leur nom, jar de sources attaché
 * quand Gradle le connaît.
 *
 * La requête vit APRÈS la sync dans le parcours du client (même connexion
 * Tooling API, modèle `IdeaProject` déjà résolu et mis en cache par le
 * pool) : la réponse est PERSISTÉE par l'app sous
 * `.codeide/local/lsp-classpath.json` pour que les LSP s'en servent le
 * moment venu, sans re-résolution.
 *
 * Les dépendances sans fichier localisable (variables d'AGP internes,
 * bibliothèques multi-fichiers) sont ignorées silencieusement : seules les
 * entrées LOCALISABLES comptent pour un LSP.
 */
internal class ClasspathHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
) {
    /** Publie [ClasspathResult] : classpath compilé de chaque module. */
    suspend fun classpath(requete: ClasspathRequest) {
        val dossier = File(requete.projectDir)
        val modules =
            withContext(Dispatchers.IO) {
                val idea = pool.connexion(dossier).model(IdeaProject::class.java).get()
                idea.modules.all.map { module: IdeaModule -> module.versClasspath() }
            }
        bus.publier(
            ClasspathResult(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                modules = modules,
            ),
        )
    }

    /** Traduit un module Idea en classpath LSP (sources + entrées). */
    private fun IdeaModule.versClasspath(): ClasspathModule {
        val sources =
            contentRoots.all
                .flatMap { racine ->
                    racine.sourceDirectories.all.map { it.directory } +
                        racine.testDirectories.all.map { it.directory }
                }.map { it.absolutePath }
                .distinct()
        val entrees =
            dependencies.all
                .mapNotNull { dependance ->
                    when (dependance) {
                        is IdeaModuleDependency -> {
                            ClasspathEntry(
                                path = dependance.targetModuleName,
                                kind = ClasspathKind.MODULE,
                                scope = dependance.scope.scope,
                            )
                        }

                        is IdeaSingleEntryLibraryDependency -> {
                            ClasspathEntry(
                                path = dependance.file.path,
                                kind = dependance.file.natureEntree(),
                                scope = dependance.scope.scope,
                                sources = dependance.source?.path,
                            )
                        }

                        else -> {
                            null
                        }
                    }
                }.distinct()
        return ClasspathModule(name = name, sourceDirs = sources, entries = entrees)
    }

    /** Nature d'une entrée par son fichier (AAR, dossier de classes ou JAR). */
    private fun File.natureEntree(): ClasspathKind =
        when {
            isDirectory -> ClasspathKind.DOSSIER
            extension.equals(other = "aar", ignoreCase = true) -> ClasspathKind.AAR
            else -> ClasspathKind.JAR
        }
}

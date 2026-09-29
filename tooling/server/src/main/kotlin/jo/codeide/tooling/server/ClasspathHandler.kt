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
 * v4 : répond D'ABORD depuis le [CacheSync] quand une sync a résolu
 * l'action unique (aucun aller-retour, aucune re-résolution) ; sinon
 * résout `IdeaProject` en direct, avec les arguments réglés de la requête
 * (`--offline`, arguments libres — §3.1 : le classpath ne les ignorait
 * plus). La réponse est PERSISTÉE par l'app sous
 * `.codeide/local/lsp-classpath.json`.
 *
 * Les dépendances sans fichier localisable (variables d'AGP internes,
 * bibliothèques multi-fichiers) sont ignorées silencieusement : seules les
 * entrées LOCALISABLES comptent pour un LSP.
 */
internal class ClasspathHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
    private val cache: CacheSync,
) {
    /** Publie [ClasspathResult] : classpath compilé de chaque module. */
    suspend fun classpath(requete: ClasspathRequest) {
        val dossier = File(requete.projectDir)

        // Cache d'abord (v4) : la sync a résolu l'action unique — le
        // classpath en est un sous-produit, répondre sans re-résoudre.
        cache.consulter(requete.projectDir)?.let { entree ->
            bus.publier(
                ClasspathResult(
                    id = requete.id,
                    protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                    projectDir = requete.projectDir,
                    modules = entree.modules,
                ),
            )
            return
        }

        val modules =
            withContext(Dispatchers.IO) {
                val idea =
                    pool
                        .connexion(dossier)
                        .model(IdeaProject::class.java)
                        .withArguments(requete.arguments)
                        .get()
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

    /** Traduit un module Idea en classpath LSP (sources + entrées) avec
     *  statistiques pré-calculées par module (v0.40.1 §4). */
    @Suppress("TooGenericExceptionCaught")
    private fun IdeaModule.versClasspath(): ClasspathModule {
        val sources =
            contentRoots.all
                .flatMap { racine ->
                    racine.sourceDirectories.all.map { it.directory } +
                        racine.testDirectories.all.map { it.directory }
                }.map { it.absolutePath }
                .distinct()
        val dependances = dependencies.all
        val entrees =
            dependances
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
        // v0.40.1 (prompt de suivi §4) : statistiques par module pré-
        // calculées côté serveur — l'UI les affiche en sous-lignes
        // (« :app · 312 jars · 4 sources · variante debug ») sans
        // re-parcourir les entries côté client.
        val nbJars = entrees.count { it.kind == ClasspathKind.JAR }
        val nbAars = entrees.count { it.kind == ClasspathKind.AAR }
        val nbDependancesProjet = entrees.count { it.kind == ClasspathKind.MODULE }
        // Tentative de détection de la variante Android : le nom du module
        // peut contenir « debug » ou « release » quand c'est un variant
        // spécifique, mais en général c'est la config Gradle (compileDebug
        // Kotlin). On garde une heuristique conservatrice — `null` si non
        // déterminé.
        val varianteAndroid = detecterVarianteAndroid()
        return ClasspathModule(
            name = name,
            sourceDirs = sources,
            entries = entrees,
            nbJars = nbJars,
            nbAars = nbAars,
            nbSources = sources.size,
            varianteAndroid = varianteAndroid,
            nbDependancesProjet = nbDependancesProjet,
        )
    }

    /**
     * Tente de détecter la variante Android courante d'un module (v0.40.1,
     * prompt de suivi §4) — heuristique conservatrice : la Tooling API
     * Gradle n'expose pas directement la variante retenue par défaut. On
     * regarde les `contentRoots` (peut contenir `build/intermediates/
     * <variant>/`) ou le nom du module (suffixe `Debug` / `Release`).
     * Retourne `null` si non déterminé (module non-Android ou heuristique
     * incertaine).
     */
    @Suppress("ReturnCount", "SwallowedException", "TooGenericExceptionCaught")
    private fun IdeaModule.detecterVarianteAndroid(): String? {
        // Le nom du module peut suffire pour des modules nommés
        // `app-debug` ou `lib-release` — sinon, on regarde les
        // contentRoots pour un chemin contenant `intermediates/<variant>/`.
        val nomModule = name.lowercase()
        when {
            nomModule.endsWith("-debug") || nomModule.endsWith("debug") -> return "debug"
            nomModule.endsWith("-release") || nomModule.endsWith("release") -> return "release"
        }
        // Cherche un chemin `intermediates/<variant>/` dans les content
        // roots — c'est la signature d'un module Android compilé.
        return try {
            contentRoots.all
                .firstNotNullOfOrNull { racine ->
                    racine.sourceDirectories.all
                        .firstOrNull {
                            it.directory.absolutePath.contains("/intermediates/debug/") ||
                                it.directory.absolutePath.contains("/intermediates/release/")
                        }?.directory
                        ?.absolutePath
                }?.let { chemin ->
                    when {
                        chemin.contains("/intermediates/debug/") -> "debug"
                        chemin.contains("/intermediates/release/") -> "release"
                        else -> null
                    }
                }
        } catch (e: Exception) {
            // SwallowedException : le serveur n'a pas AppLogger (règle 14),
            // retourner `null` est honnête — la variante reste non
            // déterminée, l'UI n'affiche pas le champ variante.
            null
        }
    }

    /** Nature d'une entrée par son fichier (AAR, dossier de classes ou JAR). */
    private fun File.natureEntree(): ClasspathKind =
        when {
            isDirectory -> ClasspathKind.DOSSIER
            extension.equals(other = "aar", ignoreCase = true) -> ClasspathKind.AAR
            else -> ClasspathKind.JAR
        }
}

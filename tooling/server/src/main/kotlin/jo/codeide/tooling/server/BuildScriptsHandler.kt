package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.BuildScriptInfo
import jo.codeide.tooling.protocol.BuildScriptsRequest
import jo.codeide.tooling.protocol.BuildScriptsResult
import jo.codeide.tooling.protocol.GradleProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Lecture des scripts de build (mission Projet P1, ADR 0095) : parcourt le
 * `projectDir` réel (chemin FUSE côté app, chemin natif côté serveur de
 * tooling), retient les `build.gradle.kts`, `build.gradle`,
 * `settings.gradle.kts`, `settings.gradle`, `gradle.properties` et le
 * catalogue `gradle/libs.versions.toml`.
 *
 * Le serveur de tooling exécute dans la JVM de l'orchestrateur (processus
 * séparé de l'app Android) : il voit le système de fichiers réel du
 * projet, pas le pont SAF — l'app lui passe `projectDir` après l'avoir
 * résolu via [ResoudreRepertoireProjet] (ADR 0038).
 *
 * Aucune modification, aucun appel à la Tooling API Gradle — lecture brute
 * pour affichage dans l'onglet Scripts de build du tiroir Projet.
 */
internal class BuildScriptsHandler(
    private val bus: EventBus,
) {
    /** Noms de fichiers reconnus comme scripts de build. */
    private val nomsScripts =
        setOf(
            "build.gradle.kts",
            "build.gradle",
            "settings.gradle.kts",
            "settings.gradle",
            "gradle.properties",
        )

    /** Nom du catalogue de versions. */
    private val nomCatalogue = "libs.versions.toml"

    /** Dossiers systématiquement exclus du parcours. */
    private val dossiersExclus = setOf("build", ".gradle", ".git", "node_modules")

    /** Publie [BuildScriptsResult] : scripts de build lus depuis [requete]. */
    suspend fun scripts(requete: BuildScriptsRequest) {
        val scripts =
            withContext(Dispatchers.IO) {
                collecter(File(requete.projectDir))
            }
        bus.publier(
            BuildScriptsResult(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                scripts = scripts,
            ),
        )
    }

    /**
     * Parcourt [racine] et collecte les scripts de build reconnus.
     * Retourne une liste vide si la racine n'existe pas ou n'est pas un
     * répertoire — l'app gère l'absence (projet pas encore synchronisé).
     */
    private fun collecter(racine: File): List<BuildScriptInfo> {
        if (!racine.isDirectory) return emptyList()
        val scripts = mutableListOf<BuildScriptInfo>()
        racine.walkTopDown().forEach { fichier ->
            if (!fichier.isFile) return@forEach
            val relatif = fichier.relativeTo(racine).path
            if (estExclu(relatif)) return@forEach
            val nom = fichier.name
            if (nom in nomsScripts || (nom == nomCatalogue && relatif.startsWith("gradle/"))) {
                scripts +=
                    BuildScriptInfo(
                        cheminRelatif = relatif,
                        contenu = fichier.readText(),
                        tailleOctets = fichier.length(),
                    )
            }
        }
        return scripts.sortedBy { it.cheminRelatif }
    }

    /** Exclut les dossiers [dossiersExclus] où qu'ils soient dans l'arbre. */
    private fun estExclu(relatif: String): Boolean =
        dossiersExclus.any { exclus ->
            relatif.startsWith("$exclus/") || relatif.contains("/$exclus/")
        }
}

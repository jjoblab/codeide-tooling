package jo.codeide.tooling.testing

import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.div

/**
 * Accès aux mini-projets Gradle de test (§7.2) : les fixtures vivent en
 * ressources de ce module et se copient vers un répertoire temporaire
 * avant tout usage — **jamais construites en place** (un build Gradle y
 * écrirait des artéfacts et corromprait la ressource pour les tests
 * suivants).
 */
@OptIn(kotlin.io.path.ExperimentalPathApi::class)
public object FixturesGradle {
    /** Racine des fixtures dans les ressources du module. */
    private const val RACINE_RESSOURCES = "/fixtures/gradle"

    /** Noms des fixtures disponibles (§7.2). */
    public val noms: List<String> =
        listOf(
            "minimal-java",
            "erreur-compilation",
            "multi-module",
            "tache-longue",
        )

    /**
     * Copie la fixture [nom] vers un répertoire frais sous [cible] et
     * retourne son chemin — l'appelant construit dedans librement.
     */
    public fun copier(
        nom: String,
        cible: Path,
    ): Path {
        require(nom in noms) { "Fixture inconnue : $nom (disponibles : $noms)" }

        val source =
            requireNotNull(FixturesGradle::class.java.getResource("$RACINE_RESSOURCES/$nom")) {
                "Ressource fixture introuvable : $RACINE_RESSOURCES/$nom"
            }

        val destination = (cible / nom).createDirectories()
        val racineSource = cheminDeRessource(source)
        racineSource.copyToRecursively(
            destination,
            followLinks = false,
            overwrite = false,
        )
        return destination
    }

    /**
     * Résout le chemin d'une ressource, QUEL QUE SOIT son support (G2) :
     * depuis le module lui-même elle vit sur le système de fichiers, mais
     * depuis un module dépendant elle est empaquetée dans le jar de test
     * (URI `jar:`) — le système de fichiers zip doit alors être monté
     * explicitement, sinon `Path.of(uri)` lève
     * `FileSystemNotFoundException`. Le montage reste ouvert pour la JVM :
     * les copies suivantes le réutilisent.
     */
    private fun cheminDeRessource(source: java.net.URL): Path {
        val uri = source.toURI()
        if (uri.scheme == "jar") {
            runCatching { FileSystems.getFileSystem(uri) }
                .onFailure { FileSystems.newFileSystem(uri, emptyMap<String, Any>()) }
        }
        return Path.of(uri)
    }

    /** Répertoire temporaire jetable pour un test (le test le supprime). */
    public fun dossierTemporaire(prefix: String = "codeide-tooling"): Path = Files.createTempDirectory(prefix)
}

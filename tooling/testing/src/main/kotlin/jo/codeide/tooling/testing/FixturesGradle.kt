package jo.codeide.tooling.testing

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
        val racineSource = Path.of(source.toURI())
        racineSource.copyToRecursively(
            destination,
            followLinks = false,
            overwrite = false,
        )
        return destination
    }

    /** Répertoire temporaire jetable pour un test (le test le supprime). */
    public fun dossierTemporaire(prefix: String = "codeide-tooling"): Path = Files.createTempDirectory(prefix)
}

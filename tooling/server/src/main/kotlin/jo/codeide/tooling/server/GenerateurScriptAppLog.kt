package jo.codeide.tooling.server

import java.io.File
import java.io.IOException

/**
 * Générateur du script d'initialisation Gradle de l'injection applog
 * (mission « Exécuter » R2, ADR 0103 §7 — « injection propre »).
 *
 * Le script est ÉCRIT par le serveur (jamais versionné, jamais dans les
 * sources du projet de l'utilisateur) et passé à chaque build par
 * `-I <chemin>`. Il n'utilise que des API PUBLIQUES de Gradle :
 *
 * 1. `gradle.beforeSettings { }` : le dépôt maven LOCAL (filesDir de
 *    CodeIDE, où le daemon a déployé l'AAR) rejoint les dépôts de la
 *    résolution des dépendances — au niveau des réglages, comme si
 *    l'utilisateur l'avait déclaré dans son `dependencyResolutionManagement`
 *    (compatible avec `RepositoriesMode.FAIL_ON_PROJECT_REPOS` : aucun
 *    dépôt de PROJET n'est ajouté) ;
 *
 * 2. `gradle.allprojects { configurations.configureEach { … } }` : la
 *    dépendance `jo.codeide:applog-runtime` est ajoutée aux
 *    configurations d'EXÉCUTION des variantes DEBUG de l'application
 *    (`<variante>RuntimeClasspath` — API Gradle publique, JAMAIS de
 *    conversion vers les classes internes d'AGP : la casse mesurée chez
 *    AndroidIDE) ;
 *
 * 3. interrupteur honnête : la propriété Gradle
 *    `codeide.applog.isEnabled=false` (gradle.properties du projet de
 *    l'utilisateur) coupe l'injection pour CE projet — l'utilisateur
 *    garde le dernier mot sur SON build.
 */
internal object GenerateurScriptAppLog {
    /** Marqueur du chemin du dépôt dans le modèle (sans dollar : raw string sûre). */
    private const val MARQUEUR_DEPOT = "@@DEPOT@@"

    /** Marqueur du groupe maven dans le modèle. */
    private const val MARQUEUR_GROUPE = "@@GROUPE@@"

    /** Marqueur de l'artefact maven dans le modèle. */
    private const val MARQUEUR_ARTEFACT = "@@ARTEFACT@@"

    /** Marqueur de la version maven dans le modèle. */
    private const val MARQUEUR_VERSION = "@@VERSION@@"

    /**
     * Écrit le script d'init vers [cible].
     *
     * @param depot chemin absolu du dépôt maven local déployé par le daemon
     * @param coordonnee coordonnée maven « groupe:artefact:version »
     *        (éclatée en notation carte dans le script)
     * @throws IOException impossible d'écrire (disque plein, droits)
     */
    fun ecrire(
        cible: File,
        depot: File,
        coordonnee: String,
    ) {
        cible.parentFile?.mkdirs()
        cible.writeText(texte(depot, coordonnee))
    }

    /** Testabilité : le texte du script est exposé en lecture. */
    fun texte(
        depot: File,
        coordonnee: String,
    ): String =
        MODELE
            .replace(MARQUEUR_DEPOT, depot.absolutePath)
            .replace(MARQUEUR_GROUPE, groupe(coordonnee))
            .replace(MARQUEUR_ARTEFACT, artefact(coordonnee))
            .replace(MARQUEUR_VERSION, version(coordonnee))

    /** Groupe d'une coordonnée « groupe:artefact:version ». */
    private fun groupe(coordonnee: String): String = coordonnee.substringBefore(':')

    /** Artefact d'une coordonnée « groupe:artefact:version ». */
    private fun artefact(coordonnee: String): String = coordonnee.substringAfter(':').substringBefore(':')

    /** Version d'une coordonnée « groupe:artefact:version ». */
    private fun version(coordonnee: String): String = coordonnee.substringAfterLast(':')

    /**
     * Corps du script — modèle à deux substituts (chemin du dépôt,
     * coordonnée). Les marqueurs @@…@@ évitent tout dollar : le modèle
     * reste une raw string Kotlin sans interpolation.
     */
    private const val MODELE =
        """
// Script d'init généré par CodeIDE (mission « Exécuter » R2, ADR 0103).
// Injecte la bibliothèque applog-runtime dans les variantes debug de
// l'application : zéro permission, zéro dépendance ajoutée (POM écrit à
// la main). Interrupteur utilisateur : gradle.properties OU -P ->
// codeide.applog.isEnabled=false coupe la dépendance pour CE projet.

val depotAppLog = uri("file://@@DEPOT@@")
// Notation CARTE (et pas la chaîne « g:a:v ») : les surcharges Kotlin de
// DependencyHandler.add tranchent différemment selon les récepteurs — la
// carte n'a qu'un seul candidat possible, le script compile partout.
val notationAppLog =
    mapOf(
        "group" to "@@GROUPE@@",
        "name" to "@@ARTEFACT@@",
        "version" to "@@VERSION@@",
    )

// 1) Le dépôt local rejoint la RÉSOLUTION DES DÉPENDANCES, au niveau des
// réglages — jamais dans un projet (FAIL_ON_PROJECT_REPOS reste respecté :
// ce mode interdit les dépôts de PROJET, pas ceux-là). Un dépôt déclaré
// sans dépendance consommée ne coûte rien si l'interrupteur est coupé.
gradle.beforeSettings {
    dependencyResolutionManagement {
        repositories {
            maven { url = depotAppLog }
        }
    }
}

// 2) La dépendance rejoint les classpaths d'EXÉCUTION des variantes debug
// de l'application (configurations « <variante>RuntimeClasspath », API
// Gradle publique — aucune classe interne d'AGP). findProperty lit -P ET
// gradle.properties : le dernier mot appartient à l'utilisateur, sur SON
// build — c'est l'interrupteur honnête de l'ADR 0103 §7.
gradle.allprojects {
    // Le gestionnaire de dépendances du PROJET est capturé AVANT le bloc
    // configureEach : à l'intérieur, le récepteur est la CONFIGURATION,
    // dont la propriété « dependencies » est sa PROPRE collection — pas
    // le DependencyHandler (le piège de la surcharge gagnante).
    val gestionnaireDependances = dependencies
    val injectionAppLogActive = findProperty("codeide.applog.isEnabled") != "false"
    if (injectionAppLogActive) {
        configurations.configureEach {
            if (estClassepathVarianteDebug(name)) {
                gestionnaireDependances.add(name, notationAppLog)
            }
        }
    }
}

// Une configuration « <variante>RuntimeClasspath » de l'APP (pas des
// tests, pas d'androidTest) dont la variante est « debug » ou se TERMINE
// par « Debug » (produits de saveurs : paidDebug, etc.).
fun estClassepathVarianteDebug(nom: String): Boolean {
    if (!nom.endsWith("RuntimeClasspath")) {
        return false
    }
    val variante = nom.removeSuffix("RuntimeClasspath")
    if (variante == "debug") {
        return true
    }
    return variante.endsWith("Debug") && !variante.contains("Test")
}
        """
}

package jo.codeide.buildlogic

import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import com.diffplug.gradle.spotless.SpotlessExtension
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalog
import org.gradle.api.artifacts.VersionCatalogsExtension
import java.util.Properties

/**
 * Accès au catalogue de versions du build principal depuis les conventions.
 *
 * Les convention plugins vivent dans un build inclus (`build-logic`) mais
 * s'exécutent sur les projets du build principal : ils lisent donc le
 * catalogue `libs` du build hôte, source unique des versions.
 */
internal val Project.libs: VersionCatalog
    get() = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

/**
 * Lit une version entière du catalogue (ex. `compileSdk`).
 *
 * @throws GradleException si la version demandée n'existe pas dans le catalogue.
 */
internal fun Project.catalogInt(name: String): Int =
    libs.findVersion(name)
        .orElseThrow { org.gradle.api.GradleException("Version '$name' absente du catalogue libs.versions.toml") }
        .toString()
        .toInt()

/**
 * Lit la version applicative dans `version.properties` (source unique, section 9.1).
 *
 * @return la paire (nom lisible, code numérique) de la version courante.
 */
internal fun Project.lireVersion(): Pair<String, Int> {
    val fichier = rootDir.resolve("version.properties")
    val proprietes = Properties().apply {
        fichier.inputStream().use { load(it) }
    }
    val nom = requireNotNull(proprietes.getProperty("VERSION_NAME")) {
        "VERSION_NAME manquant dans ${fichier.absolutePath}"
    }
    val code = requireNotNull(proprietes.getProperty("VERSION_CODE")) {
        "VERSION_CODE manquant dans ${fichier.absolutePath}"
    }.toInt()
    return nom to code
}

/**
 * Configure detekt pour un module : interprétation stricte (maxIssues = 0)
 * et règle d'interdiction de la journalisation directe (règle 14 du prompt).
 *
 * `core:logging` et `core:crash` sont les seuls modules autorisés à utiliser
 * `android.util.Log`, `println` ou `printStackTrace` (implémentations basses
 * du système de journalisation maison) : ils reçoivent un fichier de
 * configuration additionnel qui désactive la règle.
 */
internal fun Project.configurerDetekt() {
    val detekt = extensions.getByType(DetektExtension::class.java)
    detekt.buildUponDefaultConfig = true
    val base = rootDir.resolve("config/detekt/detekt.yml")
    val exemtes = setOf(":core:logging", ":core:crash")
    if (path in exemtes) {
        detekt.config.setFrom(base, rootDir.resolve("config/detekt/detekt-journalisation-autorisee.yml"))
    } else {
        detekt.config.setFrom(base)
    }
}

/**
 * Configure Spotless avec ktlint (version figée dans le catalogue).
 *
 * Le formatage vérifie uniquement les sources Kotlin du module ;
 * les fichiers générés (répertoires `build/`) sont exclus.
 */
internal fun Project.configurerSpotless() {
    val spotless = extensions.getByType(SpotlessExtension::class.java)
    spotless.kotlin {
        target("src/**/*.kt")
        targetExclude("**/build/**")
        ktlint(libs.findVersion("ktlint").get().toString())
    }
}

/**
 * Espace de noms dérivé du chemin de module (section 2 du prompt) :
 * `:core:model` devient `jo.codeide.core.model`, `:feature:home` devient
 * `jo.codeide.feature.home`.
 */
internal fun Project.namespaceDerive(): String {
    val suffixe = path.removePrefix(":").replace(":", ".").lowercase()
    return "jo.codeide.$suffixe"
}

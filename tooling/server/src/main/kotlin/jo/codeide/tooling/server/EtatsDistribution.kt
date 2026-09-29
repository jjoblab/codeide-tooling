package jo.codeide.tooling.server

import java.io.File
import java.util.Properties

/**
 * État de la distribution Gradle d'un projet (v4 — phase DISTRIBUTION
 * réelle) : l'ancienne phase CONNEXION mentait, `connect()` ne télécharge
 * RIEN — la distribution se résout paresseusement au premier usage de la
 * connexion. Ces sondes locales disent si le téléchargement aura lieu et
 * combien d'octets sont déjà reçus PENDANT qu'il a lieu.
 *
 * Layout vérifié sur un `GRADLE_USER_HOME` réel :
 * `wrapper/dists/gradle-9.7.1-bin/<hash>/gradle-9.7.1-bin.zip.ok` — le
 * fichier `.ok` est le marqueur d'installation, le téléchargement partiel
 * grossit en `.part` à côté.
 */
internal object EtatsDistribution {
    /** URL de distribution du wrapper (`gradle-wrapper.properties`), `null`
     *  si le projet n'a pas de wrapper. */
    fun lireUrlWrapper(dossier: File): String? {
        val proprietes = File(dossier, "gradle/wrapper/gradle-wrapper.properties")
        if (!proprietes.isFile) return null
        return try {
            Properties()
                .apply { proprietes.inputStream().use { load(it) } }
                .getProperty("distributionUrl")
        } catch (t: Throwable) {
            Journal.warn("gradle-wrapper.properties illisible : ${t.message}")
            null
        }
    }

    /** La distribution du wrapper est-elle déjà installée (marqueur `.ok`) ?
     *  Sans wrapper (`null`) : la Tooling API résout sa propre distribution
     *  embarquée — rien à télécharger. */
    fun estInstallee(urlWrapper: String?): Boolean =
        when (val nomDossier = urlWrapper?.let(::nomDossierDistribution)) {
            null -> {
                true
            }

            else -> {
                File(gradleUserHome(), "wrapper/dists/$nomDossier")
                    .listFiles { fichier -> fichier.isDirectory }
                    ?.orEmpty()
                    ?.any { sousDossier ->
                        sousDossier
                            .listFiles { fichier -> fichier.isFile }
                            ?.any { it.name.endsWith(".ok") } == true
                    } == true
            }
        }

    /** Somme des octets des téléchargements EN COURS (fichiers `.part` de
     *  `wrapper/dists`) : le « Mo reçus » de la phase DISTRIBUTION — la
     *  Tooling API ne donne AUCUN octet pour la distribution (vérifié sur
     *  le JAR 9.7.1 : les événements `FILE_DOWNLOAD` ne couvrent que les
     *  dépendances), la taille du fichier partiel est la seule vérité. */
    fun octetsPartiels(): Long {
        val racineDists = File(gradleUserHome(), "wrapper/dists")
        if (!racineDists.isDirectory) return 0L
        var total = 0L
        racineDists
            .walkTopDown()
            .filter { fichier -> fichier.isFile && fichier.name.endsWith(".part") }
            .forEach { fichier -> total += fichier.length() }
        return total
    }

    /** `GRADLE_USER_HOME` effectif du process (l'environnement canonique du
     *  daemon le pose TOUJOURS — ADR 0046 ; repli `~/.gradle` défensif). */
    private fun gradleUserHome(): File =
        System.getenv("GRADLE_USER_HOME")?.let(::File) ?: File(System.getProperty("user.home"), ".gradle")

    /** Nom du dossier de dists pour une URL de wrapper
     *  (`gradle-9.7.1-bin.zip` → `gradle-9.7.1-bin`). */
    private fun nomDossierDistribution(url: String): String? =
        url.substringAfterLast('/').removeSuffix(".zip").ifBlank { null }
}

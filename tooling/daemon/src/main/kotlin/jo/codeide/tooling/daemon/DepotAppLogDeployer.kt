package jo.codeide.tooling.daemon

import android.content.Context
import jo.codeide.tooling.protocol.ApplogCoordonnees
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Source du dépôt applog — couture des tests (même patron que
 * [SourceJarTooling]).
 *
 * Production : [SourceAppLogAssets] (AssetManager de l'app — l'AAR et le
 * POM sont des artefacts de build contrôlés par `controlerAarAssets`,
 * ADR 0103). Tests : fichiers, pour éprouver le déploiement sans Android.
 */
internal interface SourceAppLog {
    /**
     * Ouvre le flux d'un fichier du dépôt ([nom] = nom de fichier, ex.
     * « applog-runtime-1.0.0.aar »).
     *
     * @throws IOException asset absent ou illisible — traduit en échec
     *         typé par l'appelant, jamais avalé.
     */
    fun flux(nom: String): InputStream
}

/** Source production : le dossier `assets/applog/` de l'app. */
internal class SourceAppLogAssets(
    private val context: Context,
) : SourceAppLog {
    override fun flux(nom: String): InputStream = context.assets.open("${ApplogCoordonnees.DOSSIER_ASSETS}/$nom")
}

/**
 * Déploiement du dépôt maven local de la bibliothèque applog-runtime
 * (mission « Exécuter » R2, ADR 0103) : AAR + POM voyagent dans les
 * assets de l'app et sont déployés en DISPOSITION MAVEN sous
 * `filesDir/applog-repo/jo/codeide/applog-runtime/<version>/`.
 *
 * **Pourquoi un dépôt local** : la bibliothèque est LIVRÉE avec CodeIDE
 * (jamais téléchargée — ADR 0103 §7 : « dépôt local maven généré à
 * l'installation, --offline ») ; le build de l'utilisateur la résout
 * comme n'importe quelle dépendance, sans réseau.
 *
 * **Marqueur de version** (même contrat que [JarDeployer]) : la copie ne
 * se refait que si l'empreinte des sources diffère ; les fichiers sont
 * écrits en `.tmp` puis renommés (atomicité — un build lancé pendant le
 * déploiement voit toujours un dépôt complet ou l'ancien).
 *
 * @property dossierCible `filesDir/applog-repo`.
 */
internal class DepotAppLogDeployer(
    private val source: SourceAppLog,
    private val dossierCible: File,
) {
    /** Racine du dépôt maven local déployé (à passer au serveur par --applog-repo). */
    val racine: File
        get() = dossierCible

    /** Marqueur de version : empreinte SHA-256 combinée des sources. */
    private val marqueur: File
        get() = File(dossierCible, NOM_MARQUEUR)

    /**
     * Déploie le dépôt si les sources ont changé, retourne sa racine.
     *
     * @throws IOException source absente (installation corrompue : le
     *         build de l'app GARANTIT leur présence — contrôle
     *         controlerAarAssets) ou écriture impossible.
     */
    suspend fun deployer(): File =
        withContext(Dispatchers.IO) {
            val empreinte = empreinteSources()
            if (marqueur.isFile && marqueur.readText() == empreinte) {
                return@withContext dossierCible
            }
            val dossierVersion = dossierVersion()
            copierAtomique(ApplogCoordonnees.NOM_AAR, File(dossierVersion, ApplogCoordonnees.NOM_AAR))
            copierAtomique(ApplogCoordonnees.NOM_POM, File(dossierVersion, ApplogCoordonnees.NOM_POM))
            marqueur.writeText(empreinte)
            dossierCible
        }

    /** Dossier maven de la version — `jo/codeide/applog-runtime/<version>`. */
    private fun dossierVersion(): File =
        dossierCible
            .resolve(ApplogCoordonnees.GROUPE.replace('.', '/'))
            .resolve(ApplogCoordonnees.ARTEFACT)
            .resolve(ApplogCoordonnees.VERSION)

    /**
     * Empreinte des DEUX sources (noms + SHA-256) : le moindre changement
     * de bibliothèque redéploie le dépôt.
     */
    private fun empreinteSources(): String {
        val condense = MessageDigest.getInstance("SHA-256")
        for (nom in listOf(ApplogCoordonnees.NOM_AAR, ApplogCoordonnees.NOM_POM)) {
            condense.update(nom.toByteArray())
            source.flux(nom).use { flux ->
                val tampon = ByteArray(TAILLE_TAMPON)
                var lus: Int
                while (flux.read(tampon).also { lus = it } > 0) {
                    condense.update(tampon, 0, lus)
                }
            }
        }
        return condense.digest().joinToString("") { octet -> "%02x".format(octet) }
    }

    /** Copie atomique d'une source vers [cible] : `.tmp` puis renommage. */
    private fun copierAtomique(
        nom: String,
        cible: File,
    ) {
        // La disposition maven crée les dossiers au passage (première fois).
        cible.parentFile?.mkdirs()
        val temporaire = File(cible.parentFile, cible.name + ".tmp")
        source.flux(nom).use { entree ->
            temporaire.outputStream().use { sortie ->
                val tampon = ByteArray(TAILLE_TAMPON)
                var lus: Int
                while (entree.read(tampon).also { lus = it } > 0) {
                    sortie.write(tampon, 0, lus)
                }
                sortie.flush()
            }
        }
        if (cible.exists()) {
            cible.delete()
        }
        if (!temporaire.renameTo(cible)) {
            throw IOException("renommage impossible : $temporaire -> $cible")
        }
    }

    private companion object {
        /** Nom du marqueur de version (miroir de JarDeployer). */
        const val NOM_MARQUEUR = ".depot-applog-version"

        /** Taille des tampons de copie/empreinte (16 Kio suffit). */
        const val TAILLE_TAMPON = 16384
    }
}

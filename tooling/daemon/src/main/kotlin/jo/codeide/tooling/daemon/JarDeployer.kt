package jo.codeide.tooling.daemon

import android.content.Context
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.tooling.protocol.GradleProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest

/**
 * Source du JAR orchestrateur — couture des tests.
 *
 * Production : [SourceJarAssets] (AssetManager de l'app, le JAR est un
 * artefact de build contrôlé par `preBuild`, ADR 0040). Tests : flux
 * mémoire ou fichier, pour éprouver le déploiement sans Android.
 */
internal interface SourceJarTooling {
    /**
     * Ouvre le flux du JAR (`tooling/gradle-server.jar`).
     *
     * @throws IOException asset absent ou illisible — traduit en échec
     * typé par l'appelant, jamais avalé.
     */
    fun flux(): InputStream
}

/**
 * Source production : le JAR vit dans les assets de l'app.
 *
 * L'asset est contrôlé par la tâche `controlerJarAssets` (présence + non
 * vide) branchée sur `preBuild` (ADR 0040) — son absence ici est un état
 * corrompu d'installation, signalé tel quel.
 */
internal class SourceJarAssets(
    private val context: Context,
) : SourceJarTooling {
    override fun flux(): InputStream = context.assets.open(CHEMIN_ASSET_JAR)

    private companion object {
        /** Chemin du JAR dans les assets (miroir de copierJarVersAssets). */
        const val CHEMIN_ASSET_JAR = "tooling/${GradleProtocol.SERVER_JAR_NAME}"
    }
}

/**
 * Déploiement du JAR orchestrateur des assets vers le stockage privé
 * (§5.4, « JarDeployer à marqueur de version »).
 *
 * Le process JVM ne peut pas exécuter un JAR d'assets directement (les
 * assets sont compressés dans l'APK et ouverts en lecture seule via
 * AssetManager) : le JAR est copié sous `filesDir/tooling/`, où le
 * binaire `java` peut l'exécuter.
 *
 * **Marqueur de version** : le déploiement ne refait la copie (7,6 Mo)
 * que si l'empreinte SHA-256 de la source diffère de celle du marqueur —
 * un redémarrage d'app sur un JAR inchangé ne recopie rien. La copie est
 * atomique (`.tmp` puis renommage) : un process concurrent qui lirait
 * l'ancien JAR pendant la mise à jour voit toujours un fichier complet.
 *
 * @property dossierCible répertoire de déploiement (`filesDir/tooling`).
 */
internal class JarDeployer(
    private val source: SourceJarTooling,
    private val dossierCible: File,
    private val dispatchers: DispatcherProvider,
) {
    /** Chemin du JAR déployé (exécutable par le binaire java). */
    val jar: File
        get() = File(dossierCible, GradleProtocol.SERVER_JAR_NAME)

    /** Marqueur de version : empreinte SHA-256 de la dernière copie. */
    private val marqueur: File
        get() = File(dossierCible, NOM_MARQUEUR)

    /**
     * Déploie le JAR si la source a changé, retourne son chemin.
     *
     * @return chemin du JAR exécutable, déjà en place.
     * @throws IOException lecture de la source ou écriture impossible —
     * l'appelant (DaemonManager) traduit en échec typé.
     */
    suspend fun deployer(): File =
        withContext(dispatchers.io) {
            if (!dossierCible.isDirectory) {
                dossierCible.mkdirs()
            }
            val empreinte = empreinteSource()
            if (jar.isFile && marqueur.isFile && marqueur.readText() == empreinte) {
                return@withContext jar
            }

            val temporaire = File(dossierCible, "${GradleProtocol.SERVER_JAR_NAME}.tmp")
            source.flux().use { entree ->
                temporaire.outputStream().use { sortie ->
                    entree.copyTo(sortie, TAILLE_TAMPON)
                }
            }
            if (jar.isFile && !jar.delete()) {
                throw IOException("impossible de remplacer l'ancien JAR : ${jar.absolutePath}")
            }
            if (!temporaire.renameTo(jar)) {
                // Filesystem sans renommage fiable (rare) : repli par copie.
                temporaire.copyTo(jar, overwrite = true)
                temporaire.delete()
            }
            marqueur.writeText(empreinte)
            jar
        }

    /** Empreinte SHA-256 hexadécimale du JAR source (hex minuscule). */
    private fun empreinteSource(): String {
        val condense = MessageDigest.getInstance("SHA-256")
        source.flux().use { entree ->
            val tampon = ByteArray(TAILLE_TAMPON)
            while (true) {
                val lus = entree.read(tampon)
                if (lus < 0) break
                condense.update(tampon, 0, lus)
            }
        }
        return condense.digest().joinToString(separator = "") { octet -> "%02x".format(octet) }
    }

    private companion object {
        /** Nom du marqueur de version, à côté du JAR. */
        const val NOM_MARQUEUR = "version.txt"

        /** Taille du tampon de copie/digest (64 Kio). */
        const val TAILLE_TAMPON = 64 * 1024
    }
}

/**
 * Génération du secret de handshake (§4.4) : 32 octets aléatoires
 * ( SecureRandom), encodés URL-safe — un secret FRAIS par démarrage,
 * jamais écrit sur disque, jamais journalisé.
 */
internal object GenerateurSecret {
    /** Nouveau secret pour CE démarrage du daemon. */
    fun nouveau(): String {
        val octets = ByteArray(NB_OCTETS)
        SECURE.nextBytes(octets)
        return java.util.Base64
            .getUrlEncoder()
            .withoutPadding()
            .encodeToString(octets)
    }

    private val SECURE = java.security.SecureRandom()

    private const val NB_OCTETS = 32
}

/** Ouvre un flux sur des octets en mémoire (tests du déploiement). */
internal class SourceJarMemoire(
    private val octets: ByteArray,
) : SourceJarTooling {
    override fun flux(): InputStream = octets.inputStream()
}

/** Fournisseur de dispatcher renvoyant [Dispatchers.IO] (tests de déploiement). */
internal class DispatchersIoDirect : DispatcherProvider {
    override val main: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    override val io: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
    override val default: kotlinx.coroutines.CoroutineDispatcher = Dispatchers.IO
}

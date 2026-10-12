package jo.codeide.tooling.daemon

import android.content.Context
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.tooling.protocol.GradleProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

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
 * Échec typé du déploiement du JAR orchestrateur (v0.99.1 — retour
 * utilisateur « JAR orchestrateur indisponible : <chemin>: The source
 * file doesn't exist. »).
 *
 * Le déploiement échoue de TROIS façons distinctes, aux conséquences
 * opposées pour la boucle de vie ([DaemonManager]) :
 *
 * - [SourceJarIndisible] — l'ASSET est illisible (absent de l'APK ou
 *   corrompu) : réinstaller l'app est la seule issue, échec DÉFINITIF ;
 * - [CibleInaccessible] — le répertoire de déploiement est incréable
 *   (mkdirs en échec, état transitoire du stockage) : RETENTABLE —
 *   l'état du disque peut changer entre deux tentatives ;
 * - [EcritureImpossible] — copie/renommage/marqueur en échec (disque
 *   plein, permissions) : RETENTABLE pour la même raison.
 *
 * Chaque sous-classe porte un message FRANÇAIS explicite (chemin en
 * cause + cause originale) : le journal de l'échec est ACTIONNABLE,
 * pas un « The source file doesn't exist. » sans contexte.
 */
internal sealed class EchecDeploiementJar(
    message: String,
    cause: Throwable?,
) : IOException(message, cause) {
    /** L'asset source est illisible — installation corrompue : définitif. */
    internal class SourceJarIndisible(
        cause: Throwable,
    ) : EchecDeploiementJar(
            "asset $CHEMIN_ASSET_JAR illisible (installation corrompue ? réinstaller l'app) : ${cause.message}",
            cause,
        )

    /** Le répertoire cible est incréable : retentable. */
    internal class CibleInaccessible(
        chemin: File,
    ) : EchecDeploiementJar("répertoire de déploiement incréable : $chemin", null)

    /** Écriture, remplacement ou marqueur en échec : retentable. */
    internal class EcritureImpossible(
        message: String,
        cause: Throwable?,
    ) : EchecDeploiementJar(message, cause)
}

/** Chemin du JAR dans les assets (miroir de [SourceJarAssets]). */
private const val CHEMIN_ASSET_JAR = "tooling/${GradleProtocol.SERVER_JAR_NAME}"

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
 * **v0.99.1** : tout échec est TYÉ ([EchecDeploiementJar]) — répertoire
 * cible créé AVANT toute écriture (le `mkdirs()` d'origine ignorait son
 * résultat : un répertoire incréable produisait en cascade une
 * `FileNotFoundException` ENOENT sans contexte — journal de terrain
 * « <chemin>: The source file doesn't exist. », indéchiffrable).
 *
 * **v0.100.1** (retour d'appareil, pile JarDeployer.kt:183) : le repli
 * « copie après échec de renommage » restait HORS de la hiérarchie
 * typée, et le `.tmp` portait un nom FIXE — deux déploiements CONCURRENTS
 * (deux process de l'app pendant un redémarrage, deux tentative()
 * qui se chevauchent) partageaient le même `.tmp` : le gagnant le
 * renommait, le perdant voyait SA source disparaître et mourrait d'un
 * `NoSuchFileException` BRUT (« The source file doesn. ») — classé
 * DÉFINITIF par [DaemonManager] alors que le JAR ÉTAIT déployé. Trois
 * durcissements : `.tmp` UNIQUE (compteur + nano — l'horloge monotone
 * est COMMUNE aux process : deux process distincts ne produisent pas le
 * même nom, et `android.os.Process.myPid()` serait inaccessible aux
 * tests JVM purs du module — « not mocked »), exclusion mutuelle
 * in-process ([Mutex]), repli TYÉ et TOLÉRANT (un `.tmp` disparu avec
 * un JAR en place portant DÉJÀ notre empreinte = un concurrent a gagné
 * la course : résultat atteint, pas un échec).
 *
 * @property dossierCible répertoire de déploiement (`filesDir/tooling`).
 */
internal class JarDeployer(
    private val source: SourceJarTooling,
    private val dossierCible: File,
    private val dispatchers: DispatcherProvider,
) {
    /** Exclusion mutuelle in-process des déploiements (v0.100.1). */
    private val exclusion = Mutex()

    /** Chemin du JAR déployé (exécutable par le binaire java). */
    val jar: File
        get() = File(dossierCible, GradleProtocol.SERVER_JAR_NAME)

    /** Marqueur de version : empreinte SHA-256 de la dernière copie. */
    private val marqueur: File
        get() = File(dossierCible, NOM_MARQUEUR)

    /**
     * Déploie le JAR si la source a changé, retourne son chemin.
     *
     * v0.100.1 : EXCLUSIF in-process ([exclusion]) — deux appels
     * concurrents se sérialisent (le second voit le marqueur frais et
     * ne recopie rien) ; la garde est REVERIFIÉE sous le verrou.
     *
     * @return chemin du JAR exécutable, déjà en place.
     * @throws EchecDeploiementJar échec typé — [EchecDeploiementJar.SourceJarIndisible]
     *   (définitif) ou CibleInaccessible/EcritureImpossible (retentables) ;
     *   l'appelant ([DaemonManager]) décide selon le type.
     */
    suspend fun deployer(): File =
        exclusion.withLock {
            withContext(dispatchers.io) {
                // v0.99.1 : la création du répertoire est VERIFIÉE — un
                // échec silencieux produisait en cascade un ENOENT obscur
                // sur le `.tmp` (le « The source file doesn't exist. » de
                // terrain, sans chemin ni cause).
                if (!dossierCible.isDirectory && !dossierCible.mkdirs()) {
                    throw EchecDeploiementJar.CibleInaccessible(dossierCible)
                }
                val empreinte =
                    try {
                        empreinteSource()
                    } catch (lecture: IOException) {
                        throw EchecDeploiementJar.SourceJarIndisible(lecture)
                    }
                // v0.100.1 : un marqueur illisible (stockage transitoire) n'est
                // PAS un échec — il force simplement le redéploiement.
                if (jar.isFile && marqueurPorte(empreinte)) {
                    return@withContext jar
                }
                purgerTmpHerite()
                val temporaire = nomTemporaireUnique()
                copier(temporaire)
                installer(temporaire, empreinte)
                jar
            }
        }

    /**
     * Purge du `.tmp` à NOM FIXE hérité (≤ v0.100.0) — le nouveau code
     * n'écrit plus jamais ce nom, aucun process vivant ne le possède :
     * purge sûre (7,6 Mo rendus au disque).
     */
    private fun purgerTmpHerite() {
        File(dossierCible, "${GradleProtocol.SERVER_JAR_NAME}.tmp").delete()
    }

    /**
     * `.tmp` UNIQUE par tentative (compteur + nano — l'horloge monotone
     * est commune aux process, deux déploiements de process DISTINCTS ne
     * partagent plus jamais la même source intermédiaire ;
     * `android.os.Process.myPid()` n'est pas utilisé : inaccessible aux
     * tests JVM purs du module — « not mocked »).
     */
    private fun nomTemporaireUnique(): File =
        File(
            dossierCible,
            "${GradleProtocol.SERVER_JAR_NAME}.${COMPTEUR_DEPLOIEMENTS.incrementAndGet()}" +
                ".${System.nanoTime()}.tmp",
        )

    /**
     * Copie de la source vers le `.tmp` — TYÉ en échec d'écriture
     * (retentable). La source a pu s'ouvrir pour l'empreinte puis mourir
     * en cours de copie : les deux côtés sont couverts — une écriture
     * partiale du `.tmp` reste SANS effet (jamais renommée), le prochain
     * déploiement la réécrit.
     */
    private fun copier(temporaire: File) {
        try {
            source.flux().use { entree ->
                temporaire.outputStream().use { sortie ->
                    entree.copyTo(sortie, TAILLE_TAMPON)
                }
            }
        } catch (transfert: IOException) {
            temporaire.delete()
            throw EchecDeploiementJar.EcritureImpossible(
                "copie du JAR impossible vers ${temporaire.absolutePath} : ${transfert.message}",
                transfert,
            )
        }
    }

    /**
     * Installe le `.tmp` comme JAR (remplacement + renommage + marqueur).
     *
     * v0.100.1 (pile de terrain JarDeployer.kt:183) : le repli « copie
     * après échec de renommage » est TYÉ et TOLÉRANT — un `.tmp` disparu
     * avec un JAR en place portant DÉJÀ notre empreinte signale un
     * déploiement CONCURRENT gagnant (autre process) : le résultat EST en
     * place, ce n'est pas un échec.
     *
     * Exemption detekt ciblée (règle 16) : ThrowsCount — chaque `throw`
     * distingue une cause DIFFÉRENTE (ancien JAR inamovible, recopie
     * impossible après renommage perdu, marqueur incréable) avec son
     * message typé propre.
     */
    @Suppress("ThrowsCount")
    private fun installer(
        temporaire: File,
        empreinte: String,
    ) {
        if (jar.isFile && !jar.delete()) {
            temporaire.delete()
            throw EchecDeploiementJar.EcritureImpossible(
                "impossible de remplacer l'ancien JAR : ${jar.absolutePath}",
                null,
            )
        }
        if (!temporaire.renameTo(jar)) {
            if (jar.isFile && marqueurPorte(empreinte)) {
                // Un déploiement CONCURRENT a gagné la course — le JAR
                // en place EST celui de notre empreinte : résultat atteint.
                return
            }
            // Filesystem sans renommage fiable (rare) : repli par copie.
            try {
                temporaire.copyTo(jar, overwrite = true)
                temporaire.delete()
            } catch (recopie: IOException) {
                throw EchecDeploiementJar.EcritureImpossible(
                    "recopie après échec de renommage impossible (${temporaire.absolutePath} vers " +
                        "${jar.absolutePath}) : ${recopie.message}",
                    recopie,
                )
            }
        }
        try {
            marqueur.writeText(empreinte)
        } catch (ecriture: IOException) {
            throw EchecDeploiementJar.EcritureImpossible(
                "marqueur de version incréable : ${marqueur.absolutePath} (${ecriture.message})",
                ecriture,
            )
        }
    }

    /**
     * Le marqueur porte-t-il DÉJÀ cette empreinte (illisible = non).
     *
     * SwallowedException ciblé (règle 16) : le contrat de la garde EST un
     * booléen — un marqueur illisible force le REDÉPLOIEMENT (chemin de
     * succès), l'exception n'est PAS une erreur à remonter.
     */
    @Suppress("SwallowedException")
    private fun marqueurPorte(empreinte: String): Boolean =
        try {
            marqueur.isFile && marqueur.readText() == empreinte
        } catch (lecture: IOException) {
            false
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

        /** Compteur process-wide des déploiements (unicité du `.tmp`). */
        val COMPTEUR_DEPLOIEMENTS = AtomicLong(0)
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

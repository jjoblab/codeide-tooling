package jo.codeide.tooling.server

import java.io.File
import java.security.MessageDigest

/**
 * Empreinte SHA-256 des fichiers Gradle d'un projet (E1, Phase 2) — la
 * clé de fraîcheur du [CacheSyncPersistant], miroir serveur du
 * `sync-state.json` client (`CalculerEmpreinteGradleUseCase`) : la même
 * question (« le build a-t-il changé depuis la dernière sync ? ») se
 * pose désormais des DEUX côtés du socket.
 *
 * Plus CONSERVATRICE que la version client (SAF, fichiers racine +
 * builds inclus) : l'orchestrateur voit le vrai système de fichiers, il
 * parcourt donc TOUT l'arbre à la recherche des scripts de build — un
 * `build.gradle.kts` de SOUS-PROJET modifié invalide l'entrée (le client
 * ne le voit pas : faux négatif accepté côté client, refusé ici — un
 * cache servi périmé serait pire qu'une sync repayée). Les faux
 * positifs (fichier modifié sans effet sur la résolution) ne coûtent
 * qu'une sync complète : la direction sûre.
 *
 * Fichiers retenus (à toute profondeur utile) : `build.gradle`,
 * `build.gradle.kts`, `settings.gradle`, `settings.gradle.kts`,
 * `gradle.properties`, `libs.versions.toml`,
 * `gradle-wrapper.properties`. Sont EXCLUS les répertoires de sorties et
 * de caches (`build`, `.gradle`, `.git`, `.idea`, `node_modules`, tout
 * répertoire caché) : leurs mutations ne définissent pas le build.
 *
 * Bornes (garde-fous mémoire sur appareil) : [PROFONDEUR_MAX] niveaux,
 * [FICHIERS_MAX] fichiers, [OCTETS_PAR_FICIER_MAX] octets par fichier —
 * au-delà, le fichier est haché par ses premiers octets (un projet
 * dépassant ces bornes retombe de fait sur la sync complète par
 * invalidation fréquente, jamais sur un cache périmé).
 *
 * Fonction pure sans état : le même arbre donne toujours la même
 * empreinte (chemins RELATIFS triés + taille + contenu).
 */
internal object EmpreinteProjet {
    /** Noms de fichiers qui définissent le build. */
    private val FICHIERS_GRADLE =
        setOf(
            "build.gradle",
            "build.gradle.kts",
            "settings.gradle",
            "settings.gradle.kts",
            "gradle.properties",
            "libs.versions.toml",
            "gradle-wrapper.properties",
        )

    /** Répertoires exclus (sorties, caches, métadonnées d'IDE) — buildSrc
     *  en fait PARTIE de la définition du build, il n'est PAS exclu. */
    private val REPERTOIRES_EXCLUS =
        setOf("build", ".gradle", ".git", ".idea", "node_modules", "buildOutputCleanup")

    /**
     * Calcule l'empreinte du projet enraciné à [racine] — chaîne VIDE si
     * la racine est illisible ou sans fichiers Gradle (l'appelant traite
     * vide comme « inconnu → pas de restitution », même contrat que le
     * côté client).
     */
    fun calculer(racine: File): String {
        val fichiers = collecterFichiers(racine)
        if (fichiers.isEmpty()) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        for ((chemin, fichier) in fichiers) {
            // Marqueur de chemin + taille + contenu : un fichier RENOMMÉ
            // ou RESIZE compte comme un changement, un fichier absent
            // disparaît de la liste — les trois signatures se lisent dans
            // l'empreinte.
            digest.update("$chemin:${taille(fichier)}:".toByteArray(Charsets.UTF_8))
            hacherFichier(fichier, digest)
            digest.update(byteArrayOf('\n'.code.toByte()))
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** Collecte triée des fichiers Gradle du projet (vide si racine muette). */
    private fun collecterFichiers(racine: File): List<Pair<String, File>> {
        val fichiers = mutableListOf<Pair<String, File>>()
        if (racine.isDirectory) {
            collecter(racine, "", fichiers)
            fichiers.sortBy { it.first }
        }
        return fichiers
    }

    /** Collecte récursive bornée des fichiers Gradle (chemins relatifs). */
    private fun collecter(
        dossier: File,
        prefixe: String,
        fichiers: MutableList<Pair<String, File>>,
    ) {
        if (fichiers.size >= FICHIERS_MAX) return
        val enfants = dossier.listFiles() ?: return
        for (enfant in enfants) {
            if (fichiers.size >= FICHIERS_MAX) break
            traiterEnfant(enfant, prefixe, fichiers)
        }
    }

    /** Un enfant du parcours : fichier Gradle retenu, dossier filtré ou
     *  descendu, le reste ignoré — UN point de décision, lisible. */
    private fun traiterEnfant(
        enfant: File,
        prefixe: String,
        fichiers: MutableList<Pair<String, File>>,
    ) {
        when {
            enfant.isFile && enfant.name in FICHIERS_GRADLE -> {
                fichiers += "$prefixe${enfant.name}" to enfant
            }

            enfant.isDirectory && enfant.name !in REPERTOIRES_EXCLUS &&
                !enfant.name.startsWith(".") &&
                prefixe.count { it == '/' } < PROFONDEUR_MAX -> {
                collecter(enfant, "$prefixe${enfant.name}/", fichiers)
            }
        }
    }

    /** Taille bornée (un fichier illisible compte pour 0 — signature stable). */
    private fun taille(fichier: File): Long = runCatching { fichier.length() }.getOrDefault(0L)

    /** Hache le contenu du fichier, borné à [OCTETS_PAR_FICIER_MAX] octets. */
    private fun hacherFichier(
        fichier: File,
        digest: MessageDigest,
    ) {
        runCatching {
            fichier.inputStream().use { entree ->
                val tampon = ByteArray(TAILLE_TAMPON)
                var restant = OCTETS_PAR_FICIER_MAX
                while (restant > 0) {
                    val lus = entree.read(tampon, 0, minOf(tampon.size, restant))
                    if (lus < 0) break
                    digest.update(tampon, 0, lus)
                    restant -= lus
                }
            }
        }
    }

    private const val PROFONDEUR_MAX = 12

    private const val FICHIERS_MAX = 256

    private const val OCTETS_PAR_FICIER_MAX = 4 * 1024 * 1024

    private const val TAILLE_TAMPON = 64 * 1024
}

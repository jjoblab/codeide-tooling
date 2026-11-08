package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ClasspathModule
import jo.codeide.tooling.protocol.CodecCacheSync
import jo.codeide.tooling.protocol.EntreeCacheSync
import jo.codeide.tooling.protocol.ResolvedDependencyNode
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Cache de sync PERSISTANT (E1, Phase 2 — écart n°1 du plan
 * d'améliorations) : enveloppe le [CacheSync] mémoire d'une couche
 * disque qui survit au redémarrage de l'orchestrateur. Le verdict du
 * 2026-11-08 ayant tranché la question du retard, le PLUS GROS reste
 * sur la table : la sync de 91–194 s repaidée à la réouverture d'un
 * projet inchangé (le `DIAGNOSTIC-LOGS.md` §1 du 2026-10-31 la
 * chiffrait déjà côté client).
 *
 * Contrat de fraîcheur : une entrée ne se restitue QUE si l'empreinte
 * courante des fichiers Gradle ([EmpreinteProjet]) ET le contexte
 * d'exécution (arguments effectifs + version Gradle imposée) sont
 * IDENTIQUES au dépôt — tout changement retombe sur la sync complète,
 * jamais sur un modèle périmé. Le [SyncHandler] annonce la restitution
 * dans la console Sync (ligne [jo.codeide.tooling.protocol.SyncOutput])
 * et au [Journal] : l'honnêteté d'« état sync restitué » du client
 * (INC-05) s'applique au serveur aussi.
 *
 * Bornage : [ENTREES_MAX] projets persistés (les plus récemment déposés
 * gagnent), un fichier JSON par projet nommé par le SHA-256 de son
 * chemin, écriture atomique (provisoire + renommage) — un échec disque
 * ne fait JAMAIS échouer une sync (rétrogradation mémoire silencieuse,
 * avertissement au [Journal]).
 *
 * `dossier == null` : comportement du [CacheSync] seul (aucune
 * persistance) — les tests et les lancements sans répertoire gardent le
 * contrat d'avant.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — chaque
 * fonction est une PIÈCE du contrat du cache (déposer, consulter,
 * restituer, dépendances, purger, persister, lire, évincer) ; les
 * regrouper en classes « disque » et « mémoire » séparées exigerait de
 * partager les états internes (clés, dépendances), précisément ce que
 * l'enveloppe borne (même justification que [SocketClient]).
 */
@Suppress("TooManyFunctions")
internal class CacheSyncPersistant(
    private val dossier: File?,
    /** Cache mémoire enveloppé — partagé avec les handlers de listage/classpath. */
    val interne: CacheSync = CacheSync(),
) {
    /** Dernière clé de fraîcheur déposée par projet (empreinte + contexte). */
    private val cles = ConcurrentHashMap<String, Pair<String, String>>()

    /** Arbres de dépendances directes par projet, puis par chemin de module (E2). */
    private val dependancesMemoire = ConcurrentHashMap<String, Map<String, List<ResolvedDependencyNode>>>()

    /**
     * Dépose le résultat d'une sync RÉUSSIE : entrée mémoire (contrat du
     * [CacheSync] inchangé) + fichier persistant, avec sa clé de
     * fraîcheur. Une empreinte VIDE (racine illisible) ne se persiste
     * pas — elle ne pourrait jamais être validée à la restitution.
     */
    fun deposer(
        projectDir: String,
        empreinte: String,
        contexte: String,
        resultat: ResultatModelesSync,
    ) {
        interne.deposer(projectDir, resultat.taches, resultat.modules)
        cles[projectDir] = empreinte to contexte
        dependancesMemoire[projectDir] = resultat.dependances
        if (empreinte.isNotEmpty()) {
            persister(projectDir, empreinte, contexte, resultat.dependances)
        }
    }

    /** Dernière entrée mémoire du projet — délégation au [CacheSync]. */
    fun consulter(projectDir: String): CacheSync.Entree? = interne.consulter(projectDir)

    /**
     * Restitue l'entrée du projet quand la clé de fraîcheur courante
     * correspond au dépôt — mémoire d'abord, disque ensuite (l'entrée
     * disque RECHARGE la mémoire : tâches, classpaths et dépendances
     * redeviennent servables par les autres handlers sans Gradle).
     * `null` : aucun dépôt, clé périmée ou disque muet → sync complète.
     */
    fun restituer(
        projectDir: String,
        empreinte: String,
        contexte: String,
    ): CacheSync.Entree? {
        if (empreinte.isEmpty()) return null
        return memoireFraiche(projectDir, empreinte, contexte) ?: restituerDuDisque(projectDir, empreinte, contexte)
    }

    /** Entrée mémoire quand la clé déposée correspond encore — sinon `null`. */
    private fun memoireFraiche(
        projectDir: String,
        empreinte: String,
        contexte: String,
    ): CacheSync.Entree? =
        if (cles[projectDir] == empreinte to contexte) {
            interne.consulter(projectDir)
        } else {
            null
        }

    /** Recharge l'entrée DISQUE dans la mémoire quand la clé correspond. */
    private fun restituerDuDisque(
        projectDir: String,
        empreinte: String,
        contexte: String,
    ): CacheSync.Entree? {
        val entree = lire(projectDir)
        val exploitable =
            entree != null &&
                entree.schema == EntreeCacheSync.SCHEMA_COURANT &&
                entree.empreinte == empreinte &&
                entree.contexte == contexte
        if (!exploitable) return null
        interne.deposerProtocole(projectDir, entree.taches, entree.modules)
        cles[projectDir] = empreinte to contexte
        dependancesMemoire[projectDir] = entree.dependances
        return interne.consulter(projectDir)
    }

    /**
     * Arbres de dépendances directes du module, depuis le cache (E2) —
     * `null` si le projet ou le module n'y figure pas (l'appelant
     * retombe sur Gradle puis complète le cache).
     */
    fun dependancesDe(
        projectDir: String,
        module: String,
    ): List<ResolvedDependencyNode>? = dependancesMemoire[projectDir]?.get(module)

    /**
     * Complète le cache par l'arbre d'UN module résolu hors sync (E2 :
     * premier accès à un module jamais synchronisé) — fusion en mémoire
     * et sur disque, sans toucher à la clé de fraîcheur (la résolution
     * qui vient d'avoir lieu est fraîche PAR DÉFINITION).
     */
    fun deposerDependances(
        projectDir: String,
        module: String,
        racine: List<ResolvedDependencyNode>,
    ) {
        val completees = (dependancesMemoire[projectDir] ?: emptyMap()) + (module to racine)
        dependancesMemoire[projectDir] = completees
        val cle = cles[projectDir] ?: return
        persister(projectDir, cle.first, cle.second, completees)
    }

    /**
     * Purge l'entrée du projet : mémoire TOUJOURS, disque JAMAIS — la
     * fermeture de projet (v9, étape D) libère les ressources vives, la
     * réouverture d'un projet inchangé reste le bénéfice n°1 de E1
     * (c'est précisément le scénario « 194 s repayées »). Le disque se
     * purge par éviction LRU ([ENTREES_MAX]) — un message dédié pourra
     * l'exiger explicitement quand le protocole l'exposera.
     */
    fun purger(projectDir: String) {
        interne.purger(projectDir)
        cles.remove(projectDir)
        dependancesMemoire.remove(projectDir)
    }

    /** Écrit l'entrée courante du [CacheSync] atomiquement, puis éviction LRU. */
    private fun persister(
        projectDir: String,
        empreinte: String,
        contexte: String,
        dependances: Map<String, List<ResolvedDependencyNode>>,
    ) {
        val dossier = this.dossier ?: return
        val memoire = interne.consulter(projectDir) ?: return
        runCatching {
            dossier.mkdirs()
            val entree =
                EntreeCacheSync(
                    empreinte = empreinte,
                    contexte = contexte,
                    instantMs = System.currentTimeMillis(),
                    taches = memoire.taches,
                    modules = memoire.modules,
                    dependances = dependances,
                )
            val cible = fichier(dossier, projectDir)
            val provisoire = File(dossier, "${cible.name}.provisoire")
            provisoire.writeText(CodecCacheSync.encoder(entree), StandardCharsets.UTF_8)
            if (!provisoire.renameTo(cible)) {
                cible.delete()
                check(provisoire.renameTo(cible)) { "renommage impossible vers ${cible.name}" }
            }
            evicter()
        }.onFailure { echec ->
            Journal.warn("cache de sync non persisté (${echec::class.simpleName})")
        }
    }

    /** Lit l'entrée disque du projet — `null` si absente ou illisible. */
    private fun lire(projectDir: String): EntreeCacheSync? {
        val cible = dossier?.let { fichier(it, projectDir) }
        return if (cible?.isFile == true) {
            runCatching { CodecCacheSync.decoder(cible.readText(StandardCharsets.UTF_8)) }
                .onFailure { runCatching { cible.delete() } }
                .getOrNull()
        } else {
            null
        }
    }

    /** Fichier d'un projet : SHA-256 du chemin (nom stable, sans fuite de chemin). */
    private fun fichier(
        dossier: File,
        projectDir: String,
    ): File {
        val hex =
            MessageDigest
                .getInstance("SHA-256")
                .digest(projectDir.toByteArray(StandardCharsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        return File(dossier, "$hex.json")
    }

    /** Éviction LRU : garde les [ENTREES_MAX] fichiers les plus récents. */
    private fun evicter() {
        val fichiers = dossier?.listFiles { f -> f.isFile && f.name.endsWith(SUFFIXE) } ?: return
        if (fichiers.size <= ENTREES_MAX) return
        fichiers
            .sortedByDescending { it.lastModified() }
            .drop(ENTREES_MAX)
            .forEach { runCatching { it.delete() } }
    }

    private companion object {
        /** Projets persistés au plus (éviction LRU au-delà). */
        const val ENTREES_MAX = 8

        /** Suffixe des fichiers d'entrée. */
        const val SUFFIXE = ".json"
    }
}

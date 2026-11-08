package jo.codeide.tooling.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Entrée du cache de sync PERSISTÉ SUR DISQUE (E1, Phase 2 — plan
 * d'améliorations « niveau Android Studio », écart n°1) : ce que le
 * [CacheSync] d'un orchestrateur gardait en mémoire survit désormais au
 * redémarrage du process. La réouverture d'un projet dont les fichiers
 * Gradle n'ont PAS changé cesse de re-payer la synchronisation complète
 * (appareil de référence : 91–194 s pour rien) — l'empreinte sert de clé
 * de fraîcheur, exactement comme le `sync-state.json` du client.
 *
 * **Ce type ne voyage JAMAIS sur le fil** : c'est un format de
 * persistance disque privé à l'orchestrateur, hébergé dans le module
 * protocole parce que c'est ici que vivent la sérialisation et les types
 * qu'il transporte ([TaskInfo], [ClasspathModule],
 * [ResolvedDependencyNode]). Le fichier vit à côté du socket (répertoire
 * privé de l'app), nommé par le SHA-256 du chemin canonique du projet.
 *
 * La fraîcheur se vérifie par TOUTES les clés : [empreinte] (fichiers
 * Gradle) ET [contexte] (arguments effectifs + version Gradle imposée) —
 * un argument qui change la résolution invalide le cache, pas seulement
 * un fichier de build.
 *
 * @property schema version du format (un lecteur plus récent ignore une
 *           entrée de schéma inconnu — la sync complète la remplace).
 * @property empreinte SHA-256 hexadécimale des fichiers Gradle du projet
 *           au moment du dépôt.
 * @property contexte empreinte des arguments effectifs de la sync et de
 *           la version Gradle imposée (clé de fraîcheur complémentaire).
 * @property instantMs epoch du dépôt (ordre LRU du bornage en nombre
 *           d'entrées).
 * @property taches tâches résolues (le [SyncResult] les porte à la
 *           restitution — le bouton Tâches s'arme sans Gradle).
 * @property modules classpaths LSP extraits (le ClasspathHandler répond
 *           sans re-résoudre).
 * @property dependances arbre des dépendances DIRECTES par chemin de
 *           module (E2 : le ResolvedDependenciesHandler répond depuis le
 *           cache d'empreinte).
 */
@Serializable
public data class EntreeCacheSync(
    public val schema: Int = SCHEMA_COURANT,
    public val empreinte: String,
    public val contexte: String,
    public val instantMs: Long,
    public val taches: List<TaskInfo> = emptyList(),
    public val modules: List<ClasspathModule> = emptyList(),
    public val dependances: Map<String, List<ResolvedDependencyNode>> = emptyMap(),
) {
    public companion object {
        /** Schéma courant du format de persistance. */
        public const val SCHEMA_COURANT: Int = 1
    }
}

/**
 * Codec JSON de [EntreeCacheSync] — réglages alignés sur [ProtocolJson]
 * (`ignoreUnknownKeys` : une entrée écrite par un schéma plus récent se
 * lit tant que les champs présents correspondent ; `encodeDefaults`
 * figé pour des fichiers stables octet à octet à champ constant).
 */
public object CodecCacheSync {
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Encode une entrée en payload JSON (une ligne, fichier plat). */
    public fun encoder(entree: EntreeCacheSync): String = json.encodeToString(EntreeCacheSync.serializer(), entree)

    /** Décode une entrée depuis son payload JSON. */
    public fun decoder(payload: String): EntreeCacheSync = json.decodeFromString(EntreeCacheSync.serializer(), payload)
}

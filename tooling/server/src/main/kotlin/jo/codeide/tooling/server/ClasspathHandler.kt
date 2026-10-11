package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.ClasspathRequest
import jo.codeide.tooling.protocol.ClasspathResult
import jo.codeide.tooling.protocol.GradleProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Classpath compilé d'un projet pour les LSP (ADR 0058 — comme Android
 * Studio prépare l'index du projet à la sync) : chaque module livre ses
 * répertoires SOURCES et son classpath COMPILÉ — jars, AARs et dossiers
 * de classes, modules frères portés par leur chemin, jar de sources
 * attaché quand Gradle le connaît.
 *
 * v4 : répond D'ABORD depuis le [CacheSync] quand une sync a résolu
 * l'action unique (aucun aller-retour, aucune re-résolution). La réponse
 * est PERSISTÉE par l'app sous `.codeide/local/lsp-classpath.json`.
 *
 * v8 (mission « LSP Java » étape 1, ADR 0113) : la résolution DIRECTE
 * (sans sync préalable) exécute la MÊME action que la sync
 * ([ActionSyncModeles], sans marqueurs ni tâches) — IdeaProject pour les
 * modules JVM purs + modèles v2 d'AGP pour les modules ANDROID, avec
 * arguments réglés de la requête (`--offline`, arguments libres). Les
 * champs `androidJar`/`fichiersGeneres`/`ignore`/`avertissements` sont
 * remplis, les AAR livrés NORMALISÉS (jars extraits par les transforms du
 * cache Gradle, AAR d'origine en provenance) — voir [ModelesAndroidV2].
 */
internal class ClasspathHandler(
    private val pool: GradleConnectorPool,
    private val bus: EventBus,
    private val cache: CacheSync,
) {
    /** Publie [ClasspathResult] : classpath compilé de chaque module. */
    suspend fun classpath(requete: ClasspathRequest) {
        val dossier = File(requete.projectDir)

        // Cache d'abord (v4) : la sync a résolu l'action unique — le
        // classpath en est un sous-produit, répondre sans re-résoudre.
        cache.consulter(requete.projectDir)?.let { entree ->
            bus.publier(
                ClasspathResult(
                    id = requete.id,
                    protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                    projectDir = requete.projectDir,
                    modules = entree.modules,
                ),
            )
            return
        }

        // Résolution directe (v8) : la MÊME action que la sync, sans
        // marqueurs de phase (pas de SyncProgress à alimenter) ni tâches
        // (le modèle GradleProject complet est inutile ici).
        val modules =
            withContext(Dispatchers.IO) {
                val connexion = pool.connexion(dossier)
                // v8 : l'action porte les modules Android (modèle v2 AGP) ;
                // IdeaProject — modules JVM purs — passe par le MODEL
                // BUILDER (les dépendances typées n'existent pas dans une
                // action, cf. ModelesSync) ; le démon est déjà configuré.
                val resultatAction =
                    connexion
                        .action(ActionSyncModeles(marquerPhases = false, avecTaches = false))
                        .withArguments(requete.arguments)
                        .run()
                val modulesIdea = connexion.modelesIdea(requete.arguments)
                fusionner(modulesIdea, resultatAction.modulesAndroid).map { it.versClasspathModule() }
            }
        bus.publier(
            ClasspathResult(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                modules = modules,
            ),
        )
    }
}

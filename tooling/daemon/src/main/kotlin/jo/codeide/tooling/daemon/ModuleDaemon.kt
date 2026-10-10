package jo.codeide.tooling.daemon

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.domain.SettingsRepository
import jo.codeide.core.domain.ToolchainLocator
import jo.codeide.tooling.client.GradleApiImpl
import jo.codeide.tooling.client.GradleSocketServer
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Singleton

/**
 * Assemblage Hilt du daemon tooling (G4) — agrégé dans le graphe final par
 * `:app` (`implementation(project(":tooling:daemon"))`, même mécanique que
 * tooling:client, ADR 0041 décision 9).
 *
 * Les dépendances Android (répertoires privés, AssetManager, version de
 * l'app) vivent ICI, pas dans [DaemonManager] : le cœur du daemon reste
 * constructible à la main dans les tests (fakes de
 * [NativeProcessLauncher]/[ToolchainLocator], hôte de socket factice).
 */
@Module
@InstallIn(SingletonComponent::class)
internal object ModuleDaemon {
    /** Sous-répertoire privé du socket de l'orchestrateur (§5.1). */
    private const val DOSSIER_SOCKET = "run"

    /** Sous-répertoire privé du JAR déployé et de son marqueur. */
    private const val DOSSIER_JAR = "tooling"

    /** Sous-répertoire privé du dépôt maven applog (mission Exécuter R2). */
    private const val DOSSIER_APPLOG = "applog-repo"

    /**
     * Hôte de l'écoute production : `GradleSocketServer` de tooling:client
     * sur `filesDir/run` (ADR 0041) — la version annoncée au handshake est
     * celle du paquet installé (informationnelle, jamais décisionnelle).
     */
    @Provides
    @Singleton
    internal fun hoteSocket(
        @ApplicationContext context: Context,
    ): HoteSocketTooling = HoteSocketAndroid(GradleSocketServer(dossierSocket(context), versionApp(context)))

    /** Source du JAR orchestrateur : les assets de l'app (ADR 0040). */
    @Provides
    @Singleton
    internal fun sourceJar(
        @ApplicationContext context: Context,
    ): SourceJarTooling = SourceJarAssets(context)

    /** Déployeur : JAR et marqueur sous `filesDir/tooling`. */
    @Provides
    @Singleton
    internal fun deployeur(
        @ApplicationContext context: Context,
        dispatchers: DispatcherProvider,
    ): JarDeployer = JarDeployer(sourceJar(context), dossierJar(context), dispatchers)

    /** Source du dépôt applog : les assets de l'app (ADR 0103). */
    @Provides
    @Singleton
    internal fun sourceAppLog(
        @ApplicationContext context: Context,
    ): SourceAppLog = SourceAppLogAssets(context)

    /**
     * Déployeur du dépôt maven applog (mission « Exécuter » R2,
     * ADR 0103) : AAR + POM des assets vers `filesDir/applog-repo`
     * en disposition maven — le dépôt local que le script d'init du
     * serveur ajoute aux réglages de résolution.
     */
    @Provides
    @Singleton
    internal fun deployeurAppLog(
        source: SourceAppLog,
        @ApplicationContext context: Context,
    ): DepotAppLogDeployer = DepotAppLogDeployer(source, File(context.filesDir, DOSSIER_APPLOG))

    /**
     * Le daemon lui-même — les coutures de test (fabrique de commande,
     * cadences) prennent leurs valeurs de production ici.
     *
     * Exemption detekt ciblée (règle 16) : LongParameterList — chaque
     * paramètre est une dépendance nommée du graphe Hilt (port du domaine
     * ou couture du module), les regrouper en un objet d'options ne ferait
     * que déplacer la liste.
     */
    @Suppress("LongParameterList")
    @Provides
    @Singleton
    internal fun daemon(
        lanceur: NativeProcessLauncher,
        outilchain: ToolchainLocator,
        api: GradleApiImpl,
        hote: HoteSocketTooling,
        deployeur: JarDeployer,
        deployeurAppLog: DepotAppLogDeployer,
        parametres: SettingsRepository,
        journal: AppLogger,
        dispatchers: DispatcherProvider,
    ): DaemonManager =
        DaemonManager(
            lanceur = lanceur,
            outilchain = outilchain,
            api = api,
            hote = hote,
            deployeur = deployeur,
            journal = journal,
            dispatchers = dispatchers,
            deployeurAppLog = deployeurAppLog,
            // Réglage utilisateur lu à CHAQUE lancement du process : un
            // changement s'applique à la prochaine vie de l'orchestrateur
            // (redémarrage de l'app ou de la chaîne d'outils) — dit tel
            // quel dans l'écran des réglages.
            lectureInjectionAppLog = { parametres.observeSettings().first().injectionAppLog },
        )

    /** Répertoire privé `filesDir/run` (socket, supprimé du cache). */
    private fun dossierSocket(context: Context): File = File(context.filesDir, DOSSIER_SOCKET)

    /** Répertoire privé `filesDir/tooling` (JAR + marqueur de version). */
    private fun dossierJar(context: Context): File = File(context.filesDir, DOSSIER_JAR)

    /** Version affichable du paquet installé (repli « inconnue »). */
    private fun versionApp(context: Context): String =
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "inconnue"
}

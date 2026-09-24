package jo.codeide.tooling.daemon

import jo.codeide.core.domain.EtatConnexion
import jo.codeide.core.domain.StatutBuild
import jo.codeide.core.testing.FakeAppLogger
import jo.codeide.core.testing.FakeToolchainLocator
import jo.codeide.tooling.client.GradleApiImpl
import jo.codeide.tooling.testing.FixturesGradle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * CHAOS RÉEL du tooling (§7.5, G6) : les pannes ne sont pas simulées sur des
 * fakes — elles frappent le VRAI orchestrateur (sous-processus `java` sur
 * VRAI socket Unix, harnais de [BoutEnBoutTest]) :
 *
 * 1. **process tué en plein build** (`kill -9`) : le build EN COURS se
 *    conclut côté client (état ECHOUE, canal fermé — jamais de suspension
 *    infinie), le daemon détecte la mort et RELANCE, la connexion remonte ;
 * 2. **socket perdue côté app** : le process voit l'EOF et sort SEUL
 *    (code 0) — aucun orphelin.
 *
 * Les deux autres scénarios §7.5 sont déjà prouvés ailleurs : version
 * incompatible (`HandshakeAppTest`, refus typé) et JDK introuvable
 * (`DaemonManagerTest`, DECONNECTEE sans boucle).
 */
class ChaosToolingTest {
    private val api = GradleApiImpl()
    private val journal = FakeAppLogger()
    private val temporaires = mutableListOf<File>()
    private var daemon: DaemonManager? = null
    private var portee: CoroutineScope? = null

    @org.junit.After
    fun nettoyer() {
        daemon?.arreter()
        portee?.cancel()
        portee = null
        api.fermerSession()
        temporaires.forEach { it.deleteRecursively() }
        temporaires.clear()
    }

    @Test
    fun `le process tue en plein build echoue proprement puis le daemon relance`() =
        runBlocking {
            val fixture = copierFixture("tache-longue")
            val hote = HoteSocketJvm(dossierTemporaire("chaos-socket"))
            val lanceur = LanceurProcessusReel()
            val daemonReel = monterDaemon(hote, lanceur)
            daemon = daemonReel
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            portee = scope
            daemonReel.demarrer(scope)
            attendreQue(DELAI_CONNEXION) { api.etatConnexion == EtatConnexion.CONNECTEE }

            // 1. Build LONG (tâche `endormir`, 10 s) : on le laisse
            //    démarrer, puis kill -9 du VRAI process, en plein milieu —
            //    le daemon Gradle (froid) prend au moins une quinzaine de
            //    secondes : tuer après 3 s tombe forcément AVANT la fin.
            val buildId = api.build(fixture, listOf("endormir"))
            delay(3_000)
            assertTrue("un process devait être lancé", lanceur.processus.isNotEmpty())
            lanceur.tuerDernierProcess(force = true)

            // 2. Le build EN COURS se CONCLUT : ECHOUE (connexion perdue),
            //    pas d'attente infinie — le collecteur de l'onglet Sortie
            //    draine puis complète (§7.5 : « jamais de suspension
            //    infinie », correctif G6).
            val etatFinal =
                withTimeout(DELAI_CONCLUSION) {
                    api.observeBuildState(buildId).first { it.statut != StatutBuild.EN_COURS }
                }
            assertEquals(StatutBuild.ECHOUE, etatFinal.statut)
            assertTrue(
                "le message d'échec devait porter la perte de connexion : ${etatFinal.messageEchec}",
                etatFinal.messageEchec.orEmpty().contains("perdue"),
            )
            withTimeout(DELAI_CONCLUSION) { api.observeBuildOutput(buildId).toList() }

            // 3. La connexion retombe (EOF), le daemon RELANCE (borné),
            //    la connexion remonte avec un secret neuf.
            attendreQue(DELAI_RELANCES) { api.etatConnexion == EtatConnexion.CONNECTEE }
            assertTrue(
                "le daemon devait avoir relancé un NOUVEAU process",
                lanceur.processus.size >= 2,
            )

            // 4. Arrêt propre : le process relancé est tué, aucun orphelin.
            daemonReel.arreter()
            attendreQue(DELAI_ARRET) { lanceur.processus.none { process -> process.isAlive } }
        }

    @Test
    fun `la perte du socket app fait sortir le process seul sans orphelin`() =
        runBlocking {
            val hote = HoteSocketJvm(dossierTemporaire("chaos-socket-2"))
            val lanceur = LanceurProcessusReel()
            val daemonReel = monterDaemon(hote, lanceur)
            daemon = daemonReel
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            portee = scope
            daemonReel.demarrer(scope)
            attendreQue(DELAI_CONNEXION) { api.etatConnexion == EtatConnexion.CONNECTEE }

            // Le socket de l'« app » disparaît (crash de l'app, mise à
            // mort) : le process doit voir l'EOF et sortir SEUL — le code
            // 0 documenté (fin de connexion normale), avant même que le
            // health check du daemon ne s'en mêle.
            val premier = lanceur.processus.single()
            hote.rompreDerniereSession()

            attendreQue(DELAI_MORT_PROCESS) { !premier.isAlive }
            assertEquals(
                "le process devait sortir SEUL (EOF = code 0) : ${journal.entries.take(
                    30,
                ).joinToString { "${it.tag}: ${it.message}" }}",
                0,
                premier.exitValue(),
            )

            // Le daemon (vivant, lui) relance : la connexion remonte.
            attendreQue(DELAI_RELANCES) { api.etatConnexion == EtatConnexion.CONNECTEE }

            daemonReel.arreter()
            attendreQue(DELAI_ARRET) { lanceur.processus.none { process -> process.isAlive } }
        }

    // ------------------------------------------------------------------
    // Harnais (montage identique au bout-en-bout, lanceur instrumenté).
    // ------------------------------------------------------------------

    /** Monte le daemon avec le lanceur instrumenté (process référencés). */
    private fun monterDaemon(
        hote: HoteSocketJvm,
        lanceur: LanceurProcessusReel,
    ): DaemonManager {
        val outils =
            FakeToolchainLocator().apply {
                jdk = File(javaMaison)
            }
        val deployeur =
            JarDeployer(
                source = SourceJarMemoire(ByteArray(128)),
                dossierCible = dossierTemporaire("chaos-jar"),
                dispatchers = DispatchersIoDirect(),
            )
        return DaemonManager(
            lanceur = lanceur,
            outilchain = outils,
            api = api,
            hote = hote,
            deployeur = deployeur,
            journal = journal,
            dispatchers = DispatchersIoDirect(),
            fabriqueCommande = { java, _, cheminSocket, secret ->
                listOf(
                    java.absolutePath,
                    "-Xmx256m",
                    "-cp",
                    System.getProperty("java.class.path"),
                    CLASSE_ORCHESTRATEUR,
                    "--socket",
                    cheminSocket.absolutePath,
                    "--secret",
                    secret,
                    "--log-level",
                    "INFO",
                    "--heap-intervalle-ms",
                    "0",
                )
            },
        )
    }

    // ------------------------------------------------------------------
    // Aides.
    // ------------------------------------------------------------------

    private fun copierFixture(nom: String): File {
        val dossier = dossierTemporaire("chaos-$nom")
        temporaires.add(dossier)
        return FixturesGradle.copier(nom, dossier.toPath()).toFile()
    }

    /** Attend une condition par sondage borné. */
    private fun attendreQue(
        delaiMs: Long,
        condition: () -> Boolean,
    ) {
        val debut = System.currentTimeMillis()
        while (!condition()) {
            if (System.currentTimeMillis() - debut > delaiMs) {
                throw AssertionError(
                    "condition non atteinte en $delaiMs ms — journal : " +
                        journal.entries.take(30).joinToString { "${it.tag}: ${it.message}" },
                )
            }
            Thread.sleep(50)
        }
    }

    private companion object {
        /** Classe principale du VRAI orchestrateur (sous-processus). */
        const val CLASSE_ORCHESTRATEUR = "jo.codeide.tooling.server.ServerMain"

        /** Racine du JDK courant (le test tourne SOUS un JDK, il l'exige). */
        val javaMaison: String = checkNotNull(System.getProperty("java.home")) { "java.home absent" }

        /** Connexion du sous-processus (lancement JVM + handshake). */
        const val DELAI_CONNEXION: Long = 30_000

        /** Conclusion d'un build orphelin après la mort (EOF immédiat). */
        const val DELAI_CONCLUSION: Long = 10_000

        /** Relance du daemon + reconnexion du nouveau process. */
        const val DELAI_RELANCES: Long = 60_000

        /** Sortie SEULE du process à l'EOF (avant tout health check). */
        const val DELAI_MORT_PROCESS: Long = 15_000

        /** Arrêt du process (kill + EOF). */
        const val DELAI_ARRET: Long = 15_000
    }
}

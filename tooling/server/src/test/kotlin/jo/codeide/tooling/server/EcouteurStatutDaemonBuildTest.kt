package jo.codeide.tooling.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests de l'[EcouteurStatutDaemonBuild] (v0.45.2 — fenêtre daemon visible,
 * retour de terrain : « BUILD SUCCESSFUL in 10 s » affiché au bout de
 * 200-300 s) : les statuts textuels du daemon traversent (filtrés,
 * dédupliqués), « Connecting to Gradle Daemon » conclut la fenêtre avec le
 * délai RÉEL mesuré depuis le départ du build, tout le reste est ignoré.
 */
class EcouteurStatutDaemonBuildTest {
    /** Événement textuel factice — l'interface legacy n'expose que la description. */
    private class EvenementTextuelle(
        private val libelle: String?,
    ) : org.gradle.tooling.ProgressEvent {
        override fun getDescription(): String? = libelle
    }

    /** Captures des lignes publiées + horloge pilotable. */
    private class Cage {
        val publiees = mutableListOf<String>()
        var horlogeMs = 0L
    }

    private fun cage(): Cage = Cage()

    private fun ecouteur(cage: Cage) =
        EcouteurStatutDaemonBuild(debutMs = 1_000L, horloge = { cage.horlogeMs }) { message ->
            cage.publiees.add(message)
        }

    @Test
    fun `le statut Starting Gradle Daemon traverse une seule fois`() {
        val cage = cage()
        val ecouteur = ecouteur(cage)
        ecouteur.statusChanged(EvenementTextuelle("Starting Gradle Daemon"))
        // La Tooling API répète le statut sur une reprise de spawn — la
        // console n'a pas besoin du doublon.
        ecouteur.statusChanged(EvenementTextuelle("Starting Gradle Daemon"))
        assertEquals(listOf("Starting Gradle Daemon"), cage.publiees)
    }

    @Test
    fun `le statut Connecting conclut la fenetre avec le delai REEL`() {
        val cage = cage()
        val ecouteur = ecouteur(cage)
        // Démarrage spawn : 3 000 - 1 000 = 2 000 ms de fenêtre daemon.
        cage.horlogeMs = 3_000L
        ecouteur.statusChanged(EvenementTextuelle("Starting Gradle Daemon"))
        cage.horlogeMs = 187_000L
        ecouteur.statusChanged(EvenementTextuelle("Connecting to Gradle Daemon"))
        assertEquals(
            listOf("Starting Gradle Daemon", "daemon Gradle connecté (186000 ms)"),
            cage.publiees,
        )
    }

    @Test
    fun `une seconde tentative de conclusion ne publie rien`() {
        val cage = cage()
        val ecouteur = ecouteur(cage)
        cage.horlogeMs = 2_000L
        ecouteur.statusChanged(EvenementTextuelle("Connecting to Gradle Daemon"))
        cage.horlogeMs = 9_000L
        ecouteur.statusChanged(EvenementTextuelle("Connecting to Gradle Daemon"))
        assertEquals(listOf("daemon Gradle connecté (1000 ms)"), cage.publiees)
    }

    @Test
    fun `les statuts hors daemon restent ignores`() {
        val cage = cage()
        val ecouteur = ecouteur(cage)
        // Le flot textuel complet d'un build (mesuré sur Gradle 9.7.1 :
        // ~25 statuts pour un projet minimal) — seuls ceux du daemon
        // concernent la fenêtre aveugle, les autres phases ont déjà leurs
        // événements typés.
        listOf(
            "Build",
            "Run build",
            "Evaluate settings",
            "Configure build",
            "Load projects",
            "Configure project :",
            "Resolve dependencies of classpath",
            "Calculate task graph",
            "Run main tasks",
            "Run tasks",
            "Task :help",
        ).forEach { statut ->
            ecouteur.statusChanged(EvenementTextuelle(statut))
        }
        assertTrue("aucun statut hors daemon ne devait traverser : ${cage.publiees}", cage.publiees.isEmpty())
    }

    @Test
    fun `une description vide ou nulle est ignored`() {
        val cage = cage()
        val ecouteur = ecouteur(cage)
        ecouteur.statusChanged(EvenementTextuelle(null))
        ecouteur.statusChanged(EvenementTextuelle(""))
        ecouteur.statusChanged(EvenementTextuelle("   "))
        assertTrue(cage.publiees.isEmpty())
    }

    @Test
    fun `un statut daemon tardif reste informatif`() {
        val cage = cage()
        val ecouteur = ecouteur(cage)
        cage.horlogeMs = 2_000L
        ecouteur.statusChanged(EvenementTextuelle("Connecting to Gradle Daemon"))
        // Fin de build : Gradle parle encore du daemon — l'information
        // traverse (dédupliquée), la conclusion elle ne se répète pas.
        ecouteur.statusChanged(EvenementTextuelle("Daemon will be stopped at the end of the build"))
        assertEquals(
            listOf(
                "daemon Gradle connecté (1000 ms)",
                "Daemon will be stopped at the end of the build",
            ),
            cage.publiees,
        )
    }
}

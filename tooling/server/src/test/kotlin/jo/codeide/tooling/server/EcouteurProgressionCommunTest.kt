package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.DetailTelechargement
import jo.codeide.tooling.protocol.ToolingEvent
import org.gradle.tooling.events.OperationDescriptor
import org.gradle.tooling.events.OperationResult
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationFinishEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationOperationDescriptor
import org.gradle.tooling.events.configuration.ProjectConfigurationOperationResult
import org.gradle.tooling.events.configuration.ProjectConfigurationProgressEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationStartEvent
import org.gradle.tooling.events.download.FileDownloadFinishEvent
import org.gradle.tooling.events.download.FileDownloadOperationDescriptor
import org.gradle.tooling.events.download.FileDownloadProgressEvent
import org.gradle.tooling.events.download.FileDownloadResult
import org.gradle.tooling.events.download.FileDownloadStartEvent
import org.gradle.tooling.model.BuildIdentifier
import org.gradle.tooling.model.ProjectIdentifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Écouteur de progression COMMUN (v4, §3.1/§6) : téléchargements de fichiers
 * et configuration des projets traduits en [DetailTelechargement] /
 * rappels de configuration — débit BORNÉ (les non terminaux d'un même
 * élément passent au plus 5/s, les terminaux passent TOUJOURS), compteur
 * n d'éléments terminés, nom d'artefact = dernier segment d'URI (jamais
 * une URL complète — règle 15).
 *
 * Faux évènements implémentant les interfaces réelles du JAR 9.7.1 (même
 * patron que [ProgressBridgeTest]).
 */
class EcouteurProgressionCommunTest {
    private val telechargements = CopyOnWriteArrayList<DetailTelechargement>()
    private val configurations = CopyOnWriteArrayList<ConfigurationRecue>()

    private data class ConfigurationRecue(
        val element: String,
        val terminee: Boolean,
        val compteur: Int,
    )

    private val ecouteur =
        EcouteurProgressionCommun(
            surTelechargement = { telechargements += it },
            surConfiguration = { element, terminee, compteur ->
                configurations += ConfigurationRecue(element, terminee, compteur)
            },
        )

    // ---- Faux évènements (JAR 9.7.1 : interfaces réelles) ---------------

    /** Descripteur de téléchargement : l'URI seule (vérifié par javap). */
    private class DescripteurTelechargement(
        private val uri: URI,
    ) : FileDownloadOperationDescriptor {
        override fun getUri(): URI = uri

        override fun getName(): String = uri.toString().substringAfterLast('/')

        override fun getDisplayName(): String = name

        override fun getParent(): OperationDescriptor? = null
    }

    /** Résultat de téléchargement terminé : les octets sont connus ICI. */
    private class ResultatTelechargement(
        private val octets: Long,
        private val debut: Long,
        private val fin: Long,
    ) : FileDownloadResult {
        override fun getBytesDownloaded(): Long = octets

        override fun getStartTime(): Long = debut

        override fun getEndTime(): Long = fin
    }

    private class DemarrageTelechargement(
        private val descripteur: DescripteurTelechargement,
    ) : FileDownloadStartEvent {
        override fun getDescriptor(): FileDownloadOperationDescriptor = descripteur

        override fun getEventTime(): Long = 0

        override fun getDisplayName(): String = "Download ${descripteur.name}"
    }

    private class FinTelechargement(
        private val descripteur: DescripteurTelechargement,
        private val resultat: FileDownloadResult,
    ) : FileDownloadFinishEvent {
        override fun getDescriptor(): FileDownloadOperationDescriptor = descripteur

        override fun getResult(): FileDownloadResult = resultat

        override fun getEventTime(): Long = 0

        override fun getDisplayName(): String = "Download ${descripteur.name}"
    }

    /** Identifiant de projet minimal (le chemin suffit au nom). */
    private class IdentifiantProjet(
        private val chemin: String,
    ) : ProjectIdentifier {
        override fun getProjectPath(): String = chemin

        override fun getBuildIdentifier(): BuildIdentifier =
            object : BuildIdentifier {
                override fun getRootDir() = java.io.File(".")
            }
    }

    private class DescripteurConfiguration(
        private val chemin: String,
    ) : ProjectConfigurationOperationDescriptor {
        override fun getProject(): ProjectIdentifier = IdentifiantProjet(chemin)

        override fun getName(): String = chemin

        override fun getDisplayName(): String = chemin

        override fun getParent(): OperationDescriptor? = null
    }

    private class DemarrageConfiguration(
        private val descripteur: ProjectConfigurationOperationDescriptor,
    ) : ProjectConfigurationStartEvent,
        ProjectConfigurationProgressEvent {
        override fun getDescriptor(): ProjectConfigurationOperationDescriptor = descripteur

        override fun getEventTime(): Long = 0

        override fun getDisplayName(): String = descripteur.name
    }

    private class ResultatConfiguration(
        private val debut: Long,
        private val fin: Long,
    ) : ProjectConfigurationOperationResult {
        override fun getStartTime(): Long = debut

        override fun getEndTime(): Long = fin

        override fun getPluginApplicationResults(): List<ProjectConfigurationOperationResult.PluginApplicationResult> =
            emptyList()
    }

    private class FinConfiguration(
        private val descripteur: ProjectConfigurationOperationDescriptor,
        private val resultat: ProjectConfigurationOperationResult,
    ) : ProjectConfigurationFinishEvent,
        ProjectConfigurationProgressEvent {
        override fun getDescriptor(): ProjectConfigurationOperationDescriptor = descripteur

        override fun getResult(): ProjectConfigurationOperationResult = resultat

        override fun getEventTime(): Long = 0

        override fun getDisplayName(): String = descripteur.name
    }

    // ---- Téléchargements -------------------------------------------------

    @Test
    fun `un telechargement se traduit en depart puis fin avec octets et compteur`() {
        val uri = URI("https://cache.example/rep/org/kotlin/kotlin-stdlib-2.2.10.jar")

        ecouteur.statusChanged(DemarrageTelechargement(DescripteurTelechargement(uri)))
        ecouteur.statusChanged(
            FinTelechargement(
                DescripteurTelechargement(uri),
                ResultatTelechargement(octets = 1_769_000, debut = 100, fin = 950),
            ),
        )

        assertEquals(2, telechargements.size)
        val depart = telechargements[0]
        assertEquals("kotlin-stdlib-2.2.10.jar", depart.element)
        assertEquals(0L, depart.octetsRecus)
        assertEquals(false, depart.termine)
        assertEquals(0, depart.compteur)

        val fin = telechargements[1]
        assertEquals("kotlin-stdlib-2.2.10.jar", fin.element)
        assertEquals(1_769_000L, fin.octetsRecus)
        assertEquals(1_769_000L, fin.octetsTotal)
        assertTrue(fin.termine)
        assertEquals(850L, fin.dureeMs)
        assertEquals(1, fin.compteur)
    }

    @Test
    fun `le nom d artefact est le dernier segment d uri - jamais l url entiere`() {
        val uri = URI("https://cache.example/rep/artefact-a.jar")

        ecouteur.statusChanged(DemarrageTelechargement(DescripteurTelechargement(uri)))

        assertEquals("artefact-a.jar", telechargements.single().element)
        assertTrue(
            "aucune URL complète ne doit fuiter (règle 15)",
            telechargements.single().element.length < uri.toString().length,
        )
    }

    @Test
    fun `le compteur d elements termines croit d un telechargement a l autre`() {
        val premiere = URI("https://cache.example/rep/a.jar")
        val seconde = URI("https://cache.example/rep/b.jar")
        val resultat = ResultatTelechargement(octets = 10, debut = 0, fin = 1)

        ecouteur.statusChanged(FinTelechargement(DescripteurTelechargement(premiere), resultat))
        ecouteur.statusChanged(FinTelechargement(DescripteurTelechargement(seconde), resultat))

        assertEquals(1, telechargements[0].compteur)
        assertEquals(2, telechargements[1].compteur)
    }

    // ---- Configuration des projets ---------------------------------------

    @Test
    fun `une configuration de projet se traduit en depart puis fin avec compteur`() {
        val descripteur = DescripteurConfiguration(":app")

        ecouteur.statusChanged(DemarrageConfiguration(descripteur))
        ecouteur.statusChanged(
            FinConfiguration(descripteur, ResultatConfiguration(debut = 200, fin = 1_400)),
        )

        assertEquals(2, configurations.size)
        assertEquals(ConfigurationRecue(":app", false, 0), configurations[0])
        assertEquals(ConfigurationRecue(":app", true, 1), configurations[1])
    }

    // ---- Évènements étrangers ---------------------------------------------

    @Test
    fun `un evenement inconnu reste silencieux - jamais d erreur`() {
        val etranger =
            object : ProgressEvent {
                override fun getEventTime(): Long = 0

                override fun getDisplayName(): String = "quelque chose d autre"

                override fun getDescriptor(): OperationDescriptor? = null
            }

        ecouteur.statusChanged(etranger)

        assertTrue(telechargements.isEmpty())
        assertTrue(configurations.isEmpty())
    }
}

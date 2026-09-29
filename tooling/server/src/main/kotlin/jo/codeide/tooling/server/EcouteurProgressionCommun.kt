package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.DetailTelechargement
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationFinishEvent
import org.gradle.tooling.events.configuration.ProjectConfigurationStartEvent
import org.gradle.tooling.events.download.FileDownloadFinishEvent
import org.gradle.tooling.events.download.FileDownloadResult
import org.gradle.tooling.events.download.FileDownloadStartEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Écouteur de progression COMMUN de la Tooling API (v4, §3.1 + addendum §6) :
 * les téléchargements de fichiers (`OperationType.FILE_DOWNLOAD`) et la
 * configuration des projets (`PROJECT_CONFIGURATION`) se voient pour TOUTE
 * action Gradle — sync, build, classpath, listage — le même traducteur.
 *
 * Appelé sur les FILS INTERNES de Gradle : thread-safe, sans allocation
 * débridée, débit BORNÉ (moins de 5 événements/s par élément — les
 * événements TERMINAUX passent toujours : un début/fin par élément est le
 * contrat minimal, la borne ne frappe que le flot intermédiaire).
 *
 * Vérifié sur le JAR 9.7.1 (`javap`) : les octets d'un téléchargement ne
 * sont connus qu'à SA FIN (`FileDownloadResult.getBytesDownloaded()`),
 * l'URI seule voyage pendant — la progression cumulée se construit donc
 * d'élément en élément, côté [ConteurPhasesSync].
 *
 * @param surTelechargement détaillé d'un téléchargement (début, fin avec
 *        octets/durée/compteur) — la sync l'agrège en phase DEPENDANCES,
 *        le build le publie en [jo.codeide.tooling.protocol.ProgressEvent].
 * @param surConfiguration une configuration de projet démarre ou se
 *        conclut (nom du projet, compteur n).
 */
internal class EcouteurProgressionCommun(
    private val surTelechargement: (DetailTelechargement) -> Unit,
    private val surConfiguration: (element: String, terminee: Boolean, compteur: Int) -> Unit,
) : org.gradle.tooling.events.ProgressListener {
    /** Dernière émission NON terminale par élément (borne de débit). */
    private val derniereEmission = ConcurrentHashMap<String, Long>()

    /** Éléments téléchargés terminés (compteur n). */
    private val elementsTermines = AtomicInteger()

    /** Configurations de projets terminées (compteur n). */
    private val configurationsTerminees = AtomicInteger()

    override fun statusChanged(evenement: ProgressEvent) {
        when (evenement) {
            is FileDownloadStartEvent -> surDepartTelechargement(evenement)
            is FileDownloadFinishEvent -> surFinTelechargement(evenement)
            is ProjectConfigurationStartEvent -> surDepartConfiguration(evenement)
            is ProjectConfigurationFinishEvent -> surFinConfiguration(evenement)
            else -> Unit
        }
    }

    // ---- Téléchargements de fichiers -----------------------------------

    private fun surDepartTelechargement(evenement: FileDownloadStartEvent) {
        val element = nomElement(evenement)
        if (!debitAccepte("dl:$element")) return
        surTelechargement(
            DetailTelechargement(
                element = element,
                octetsRecus = 0,
                octetsTotal = null,
                termine = false,
                compteur = elementsTermines.get(),
            ),
        )
    }

    private fun surFinTelechargement(evenement: FileDownloadFinishEvent) {
        val resultat: FileDownloadResult = evenement.result
        surTelechargement(
            DetailTelechargement(
                element = nomElement(evenement),
                octetsRecus = resultat.bytesDownloaded,
                octetsTotal = resultat.bytesDownloaded,
                termine = true,
                dureeMs = maxOf(0L, resultat.endTime - resultat.startTime),
                compteur = elementsTermines.incrementAndGet(),
            ),
        )
    }

    // ---- Configuration des projets --------------------------------------

    private fun surDepartConfiguration(evenement: ProjectConfigurationStartEvent) {
        val element = nomProjet(evenement)
        if (!debitAccepte("conf:$element")) return
        surConfiguration(element, false, configurationsTerminees.get())
    }

    private fun surFinConfiguration(evenement: ProjectConfigurationFinishEvent) {
        configurationsTerminees.incrementAndGet()
        surConfiguration(nomProjet(evenement), true, configurationsTerminees.get())
    }

    // ---- Aides -----------------------------------------------------------

    /** Dernier segment de l'URI : un NOM d'artefact, jamais un chemin local
     *  ni une URL complète (règle 15 — aucune donnée personnelle). */
    private fun nomElement(evenement: FileDownloadStartEvent): String =
        evenement.descriptor.uri
            .toString()
            .substringAfterLast('/')
            .ifBlank { "artefact" }

    private fun nomElement(evenement: FileDownloadFinishEvent): String =
        evenement.descriptor.uri
            .toString()
            .substringAfterLast('/')
            .ifBlank { "artefact" }

    private fun nomProjet(evenement: ProjectConfigurationStartEvent): String =
        evenement.descriptor.project.projectPath
            .ifBlank { "projet" }

    private fun nomProjet(evenement: ProjectConfigurationFinishEvent): String =
        evenement.descriptor.project.projectPath
            .ifBlank { "projet" }

    /** Borne de débit : au plus un événement NON terminal par élément et
     *  par [PERIODE_MINIMALE_MS] (5/s — exigence §3.1). */
    private fun debitAccepte(cle: String): Boolean {
        val maintenant = System.currentTimeMillis()
        val precedent = derniereEmission.put(cle, maintenant) ?: return true
        return maintenant - precedent >= PERIODE_MINIMALE_MS
    }

    private companion object {
        /** Borne de débit par élément : 200 ms (5 événements/s). */
        const val PERIODE_MINIMALE_MS = 200L
    }
}

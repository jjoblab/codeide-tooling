package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.BuildVariantInfo
import jo.codeide.tooling.protocol.BuildVariantsRequest
import jo.codeide.tooling.protocol.BuildVariantsResult
import jo.codeide.tooling.protocol.GradleProtocol
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Variantes de build (mission Projet P5, ADR 0095) : expose l'arbre
 * module × build type × product flavor connu d'AGP via la Tooling API.
 *
 * Le modèle exact `AndroidProject` d'AGP n'est PAS dans le classpath
 * du serveur de tooling (dépendance facultative qui alourdirait le JAR
 * de ~10 Mo). À la place, ce handler publie une réponse VIDE tant que
 * le modèle AGP n'est pas branché — l'app gère l'absence (message
 * « aucune variante »). Le branchement AGP TAPI est un livrable P6+
 * (besoin d'un `model(AndroidProject::class.java)` conditionnel sur
 * la présence d'AGP).
 */
@Suppress("TooManyFunctions")
internal class BuildVariantsHandler(
    private val bus: EventBus,
) {
    /**
     * Publie [BuildVariantsResult] : variantes connues du projet.
     *
     * Implémentation actuelle (P5) : réponse vide — voir KDoc de la
     * classe pour le branchement AGP TAPI à venir.
     */
    suspend fun variantes(requete: BuildVariantsRequest) {
        // Lecture du dossier juste pour vérifier qu'il existe — sinon
        // la réponse reste vide (l'app affiche « aucune variante »).
        withContext(Dispatchers.IO) {
            File(requete.projectDir).isDirectory
        }
        bus.publier(
            BuildVariantsResult(
                id = requete.id,
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = requete.projectDir,
                variants = emptyList(),
            ),
        )
    }
}

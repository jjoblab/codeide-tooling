package jo.codeide.tooling.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Régénérateur des fichiers dorés (outil de maintenance, v4) : réécrit les
 * fichiers JSON de `golden` (un par message) depuis [EchantillonsMessages].
 * Inactif par défaut — activé uniquement par la variable d'environnement
 * `REGENERER_DORES=1` (jamais en CI : le doré est un CONTRAT, on le
 * régénère volontairement, au moment d'une montée de version du protocole,
 * puis on relit le diff).
 *
 * Usage :
 * ```bash
 * REGENERER_DORES=1 ./gradlew :tooling:protocol:test
 * git diff tooling/protocol/src/test/resources/golden/  # relire AVANT commit
 * ```
 */
class RegenerateurDoresTest {
    @Test
    fun `regenere les fichiers dores depuis les echantillons`() {
        assumeTrue(System.getenv("REGENERER_DORES") == "1")

        // Répertoire SOURCE des dorés (pas la copie du classpath sous
        // build/resources : le contrat vit dans src/test/resources).
        val racine = File("src/test/resources/golden").canonicalFile
        check(racine.isDirectory) { "répertoire des dorés introuvable : ${racine.absolutePath}" }
        val messages = EchantillonsMessages.requetes + EchantillonsMessages.evenements
        var ecrits = 0

        messages.forEach { message ->
            val nom = nomDore(message)
            val json = ProtocolJson.encoder(message)
            File(racine, "$nom.json").writeText(json + "\n", Charsets.UTF_8)
            ecrits++
        }

        // Tout fichier doré sans échantillon est signalé : un message retiré
        // du catalogue doit aussi retirer son doré.
        val attendus = messages.map(::nomDore).toSet()
        racine
            .listFiles { fichier -> fichier.extension == "json" }!!
            .filter { it.nameWithoutExtension !in attendus }
            .forEach { orphelin ->
                error("fichier doré sans message au catalogue : ${orphelin.name} — à retirer")
            }
        println("dorés régénérés : $ecrits fichier(s) dans ${racine.absolutePath}")
    }

    /** Discriminant `type` d'un message encodé (son @SerialName). */
    private fun nomDore(message: ProtocolMessage): String =
        Json
            .parseToJsonElement(ProtocolJson.encoder(message))
            .jsonObject
            .getValue("type")
            .jsonPrimitive
            .content
}

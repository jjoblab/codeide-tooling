package jo.codeide.tooling.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tests de sortie de phase du protocole (§3/§7.1, bloquants avant toute
 * ligne de `tooling:server` ou `tooling:client`) : round-trip de
 * sérialisation pour **chaque** type de message, adéquation aux fichiers
 * dorés (fichiers de `golden`, un par message — tout écart de format y est vu), compatibilité
 * ascendante (`ignoreUnknownKeys`).
 */
class ProtocoleRoundTripTest {
    /** Contenu doré décodé en JsonElement (comparaison hors mise en forme). */
    private fun elementDore(nom: String): JsonElement = Json.parseToJsonElement(lireDore(nom))

    private fun lireDore(nom: String): String =
        javaClass
            .getResourceAsStream("/golden/$nom.json")!!
            .readBytes()
            .decodeToString()

    /**
     * Échantillons nommés par leur discriminant câble (`type` = le
     * `@SerialName`) — le nom EST le fichier doré, une seule source de
     * vérité.
     */
    private val echantillons: Map<String, ProtocolMessage> =
        (EchantillonsMessages.requetes + EchantillonsMessages.evenements).associateBy(::nomDore)

    /** Discriminant `type` d'un message encodé (son @SerialName). */
    private fun nomDore(message: ProtocolMessage): String =
        Json
            .parseToJsonElement(ProtocolJson.encoder(message))
            .jsonObject
            .getValue("type")
            .jsonPrimitive
            .content

    @Test
    fun `le catalogue couvre 25 messages - 9 requetes et 16 evenements`() {
        assertEquals(9, EchantillonsMessages.requetes.size)
        assertEquals(16, EchantillonsMessages.evenements.size)
        assertEquals(25, echantillons.size)
    }

    @Test
    fun `round-trip de chaque requete - encode puis decode identique`() {
        EchantillonsMessages.requetes.forEach { requete ->
            val relue = ProtocolJson.decoderRequete(ProtocolJson.encoder(requete))
            assertEquals("round-trip cassé : ${requete::class.simpleName}", requete, relue)
        }
    }

    @Test
    fun `round-trip de chaque evenement - encode puis decode identique`() {
        EchantillonsMessages.evenements.forEach { evenement ->
            val relu = ProtocolJson.decoderEvenement(ProtocolJson.encoder(evenement))
            assertEquals("round-trip cassé : ${evenement::class.simpleName}", evenement, relu)
        }
    }

    @Test
    fun `chaque message se decode depuis son fichier dore`() {
        echantillons.forEach { (nom, message) ->
            val decode: ProtocolMessage =
                when (message) {
                    is ToolingRequest -> ProtocolJson.decoderRequete(lireDore(nom))
                    is ToolingEvent -> ProtocolJson.decoderEvenement(lireDore(nom))
                }
            assertEquals("golden divergent du modèle : $nom", message, decode)
        }
    }

    @Test
    fun `l encodage reste semantiquement identique aux fichiers dores`() {
        // Comparaison JsonElement : l'ordre des champs et la mise en forme
        // ne comptent pas, le contenu oui — un champ renommé, retiré ou
        // au type changé fait échouer ce test (garde anti-dérive du
        // format câble).
        echantillons.forEach { (nom, message) ->
            val encode = Json.parseToJsonElement(ProtocolJson.encoder(message))
            assertEquals("format câble dérivé : $nom", elementDore(nom), encode)
        }
    }

    @Test
    fun `un champ inconnu ne casse pas le decodage - compatibilite ascendante`() {
        // Client v2.1 ↔ serveur v2.0 : le serveur ignore le champ nouveau
        // au lieu de planter (§3.3).
        val original = elementDore("build_request").jsonObject
        val enrichi =
            buildJsonObject {
                put("champDuFutur", "valeur")
                original.forEach { (cle, valeur) -> put(cle, valeur) }
            }

        val decode = ProtocolJson.decoderRequete(enrichi.toString())
        assertEquals(echantillons["build_request"], decode)
    }

    @Test
    fun `la negociation de version voyage dans le handshake`() {
        val salutation =
            HelloRequest(
                "req-1",
                protocolVersion = 3,
                clientVersion = "0.27.0",
                handshakeSecret = "s",
            )
        val relue = ProtocolJson.decoderRequete(ProtocolJson.encoder(salutation))
        assertEquals(3, relue.protocolVersion)
        assertEquals(salutation, relue)

        val reponse =
            ProtocolJson.decoderEvenement(
                ProtocolJson.encoder(
                    HelloResponse(
                        "evt-1",
                        3,
                        serverVersion = "0.26.0",
                        gradleToolingApiVersion = "9.7.1",
                        supportedFeatures = emptySet(),
                    ),
                ),
            )
        assertEquals(3, reponse.protocolVersion)
    }

    @Test
    fun `les erreurs portent un code type - jamais une chaine libre`() {
        val erreur =
            ErrorResponse(
                "evt-9",
                GradleProtocol.PROTOCOL_VERSION,
                requestId = "req-9",
                code = ErrorCode.PROTOCOL_VERSION_MISMATCH,
                message = "Version 3 requise",
            )
        val relue = ProtocolJson.decoderEvenement(ProtocolJson.encoder(erreur)) as ErrorResponse
        assertEquals(ErrorCode.PROTOCOL_VERSION_MISMATCH, relue.code)
        assertEquals("req-9", relue.requestId)
    }
}

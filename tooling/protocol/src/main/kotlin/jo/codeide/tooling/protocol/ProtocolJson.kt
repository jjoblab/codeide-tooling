package jo.codeide.tooling.protocol

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Sérialisation JSON du protocole (§3.2/§3.3) : discriminant de type
 * natif (`@SerialName` sur la hiérarchie scellée, `classDiscriminator`
 * nommé `type`) et compatibilité ascendante — `ignoreUnknownKeys` : un
 * client v2.1 doit dialoguer avec un serveur v2.0 sans planter sur un
 * champ ajouté depuis.
 */
public object ProtocolJson {
    private val json: Json =
        Json {
            classDiscriminator = "type"
            ignoreUnknownKeys = true
            encodeDefaults = true
        }

    /** Encode un message en payload JSON (chaîne). */
    public fun encoder(message: ProtocolMessage): String = json.encodeToString(message)

    /** Décode une requête (app → process JVM) depuis son payload JSON. */
    public fun decoderRequete(payload: String): ToolingRequest = json.decodeFromString<ToolingRequest>(payload)

    /** Décode un événement (process JVM → app) depuis son payload JSON. */
    public fun decoderEvenement(payload: String): ToolingEvent = json.decodeFromString<ToolingEvent>(payload)

    /** Décode un message quelconque (handshake des deux côtés, tests). */
    public fun decoderMessage(payload: String): ProtocolMessage = json.decodeFromString<ProtocolMessage>(payload)
}

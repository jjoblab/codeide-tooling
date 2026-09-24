package jo.codeide.tooling.client

import jo.codeide.tooling.protocol.ErrorCode
import jo.codeide.tooling.protocol.ErrorResponse
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.HelloRequest
import jo.codeide.tooling.protocol.HelloResponse
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Validation du handshake côté app (§4.4) : AUCUNE requête n'atteint un
 * handler avant validation du secret — le refus envoie l'erreur typée à
 * l'orchestrateur PUIS ferme (§1.6), jamais l'inverse.
 */
class HandshakeAppTest {
    private val protocole = GradleProtocol.PROTOCOL_VERSION

    private fun bonjour(
        secret: String = SECRET,
        version: Int = protocole,
    ) = HelloRequest(
        id = "hello-1",
        protocolVersion = version,
        clientVersion = "0.27.0",
        handshakeSecret = secret,
    )

    @Test
    fun `le bon secret recoit la reponse HelloResponse`() =
        runBlocking {
            val session = SessionFactice()
            HandshakeApp.valider(session, bonjour(SECRET), SECRET, "app-test")

            val reponse = session.messages.single()
            assertTrue(reponse is HelloResponse)
            reponse as HelloResponse
            assertEquals("hello-1", reponse.id)
            assertEquals(protocole, reponse.protocolVersion)
            assertEquals("app-test", reponse.serverVersion)
            assertTrue("build" in reponse.supportedFeatures)
            assertEquals(false, session.fermee)
        }

    @Test
    fun `un secret invalide est refuse par une erreur typée puis fermeture`() =
        runBlocking {
            val session = SessionFactice()
            try {
                HandshakeApp.valider(session, bonjour("mauvais-secret"), SECRET, "app-test")
                fail("le handshake devait être refusé")
            } catch (attendu: EchecHandshakeClient) {
                assertEquals(true, attendu.message!!.contains("secret"))
            }
            val reponse = session.messages.single()
            assertTrue(reponse is ErrorResponse)
            reponse as ErrorResponse
            assertEquals(ErrorCode.HANDSHAKE_FAILED, reponse.code)
            assertEquals("hello-1", reponse.requestId)
            assertEquals("la connexion doit être fermée après le refus", true, session.fermee)
        }

    @Test
    fun `une version incompatible est refusee avec un message clair`() =
        runBlocking {
            val session = SessionFactice()
            try {
                HandshakeApp.valider(session, bonjour(SECRET, version = protocole - 1), SECRET, "app-test")
                fail("le handshake devait être refusé")
            } catch (attendu: EchecHandshakeClient) {
                assertEquals(true, attendu.message!!.contains("version"))
            }
            val reponse = session.messages.single() as ErrorResponse
            assertEquals(ErrorCode.PROTOCOL_VERSION_MISMATCH, reponse.code)
            assertTrue(
                "le message doit parler de versions",
                reponse.message.contains("v$protocole"),
            )
            assertEquals(true, session.fermee)
        }

    private companion object {
        const val SECRET = "secret-de-test"
    }
}

package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.DiagnosticSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Tests du [ParseurDiagnostics] (G5) : formats javac et kotlinc, lignes de
 * contexte ignorées, identifiant porté.
 */
class ParseurDiagnosticsTest {
    @Test
    fun `une erreur javac avec colonne se lit entierement`() {
        val diagnostic =
            ParseurDiagnostics.analyser(
                "/projets/demo/src/main/java/demo/Casse.java:5:12: error: ';' expected",
                id = "d1",
            )
        assertEquals("d1", diagnostic?.id)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic?.severity)
        assertEquals("/projets/demo/src/main/java/demo/Casse.java", diagnostic?.file)
        assertEquals(5L, diagnostic?.line)
        assertEquals(12L, diagnostic?.column)
        assertEquals("';' expected", diagnostic?.message)
        assertEquals("javac", diagnostic?.source)
    }

    @Test
    fun `une erreur javac sans colonne prend la colonne un`() {
        val diagnostic =
            ParseurDiagnostics.analyser(
                "/projets/demo/src/Main.java:9: error: cannot find symbol",
            )
        assertEquals(9L, diagnostic?.line)
        assertEquals(1L, diagnostic?.column)
        assertEquals(DiagnosticSeverity.ERROR, diagnostic?.severity)
        assertEquals("cannot find symbol", diagnostic?.message)
    }

    @Test
    fun `un avertissement javac reste un avertissement`() {
        val diagnostic =
            ParseurDiagnostics.analyser(
                "/projets/demo/src/Main.java:3: warning: [deprecation] usage obsolete",
            )
        assertEquals(DiagnosticSeverity.WARNING, diagnostic?.severity)
        assertEquals("[deprecation] usage obsolete", diagnostic?.message)
    }

    @Test
    fun `une ligne kotlinc e se lit avec severite erreur`() {
        val diagnostic =
            ParseurDiagnostics.analyser(
                "e: file:///projets/demo/src/main/kotlin/Main.kt:7:5 unresolved reference: inexistant",
            )
        assertEquals(DiagnosticSeverity.ERROR, diagnostic?.severity)
        assertEquals("/projets/demo/src/main/kotlin/Main.kt", diagnostic?.file)
        assertEquals(7L, diagnostic?.line)
        assertEquals(5L, diagnostic?.column)
        assertEquals("unresolved reference: inexistant", diagnostic?.message)
        assertEquals("kotlinc", diagnostic?.source)
    }

    @Test
    fun `une ligne kotlinc w reste un avertissement`() {
        val diagnostic =
            ParseurDiagnostics.analyser(
                "w: file:///projets/demo/src/Main.kt:2:1 parametre jamais utilise",
            )
        assertEquals(DiagnosticSeverity.WARNING, diagnostic?.severity)
    }

    @Test
    fun `les lignes de contexte ne sont pas des diagnostics`() {
        assertNull(ParseurDiagnostics.analyser("Task :compileJava FAILED"))
        assertNull(ParseurDiagnostics.analyser("    symbol: variable Ceci"))
        assertNull(ParseurDiagnostics.analyser("      ^"))
        assertNull(ParseurDiagnostics.analyser("FAILURE: Build failed with an exception."))
        assertNull(ParseurDiagnostics.analyser("note: /projets/demo/autre.txt: usage de l'API brute"))
    }
}

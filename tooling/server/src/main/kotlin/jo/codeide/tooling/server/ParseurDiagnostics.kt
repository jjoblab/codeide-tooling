package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.Diagnostic
import jo.codeide.tooling.protocol.DiagnosticSeverity
import jo.codeide.tooling.protocol.GradleProtocol
import java.util.UUID

/**
 * Extraction des diagnostics de compilation depuis la sortie d'erreur des
 * builds (G5, §3.2 `Diagnostic`) : les compilateurs écrivent leurs
 * diagnostics en lignes de texte **sur stderr** — le format est stable et
 * documenté, la sortie arrive déjà ligne par ligne par
 * [StreamingOutputStream].
 *
 * Formats reconnus :
 * - **javac** : `/chemin/Fichier.java:12: error: message` (colonne
 *   optionnelle : `/chemin/F.java:12:3: error: message`, niveaux
 *   `error`/`warning`/`note`) ;
 * - **kotlinc** : `e: file:///chemin/Fichier.kt:12:3 message` (niveaux
 *   `e`/`w`).
 *
 * Tout ce qui ne correspond pas est ignoré (lignes de contexte, carets,
 * notes de tâches) : un diagnostic n'est émis que sur une ligne de
 * position complète — jamais de demi-renseignement (§1.6 : message clair,
 * jamais indéfini).
 */
internal object ParseurDiagnostics {
    /** Ligne javac : fichier:ligne[:colonne]: niveau: message. */
    private val MOTIF_JAVAC =
        Regex("""^(.+?\.(?:java)):(\d+):(?:(\d+):)?\s*(error|warning|note):\s*(.*)$""")

    /** Ligne kotlinc : niveau: file://fichier:ligne:colonne message. */
    private val MOTIF_KOTLINC =
        Regex("""^([ew]):\s*file://([^:]+):(\d+):(\d+)\s*(.*)$""")

    /**
     * Analyse UNE ligne de stderr ; `null` si ce n'est pas un diagnostic.
     *
     * @param ligne la ligne complète (sans terminaison).
     * @param id identifiant d'événement à porter par le message.
     */
    fun analyser(
        ligne: String,
        id: String = UUID.randomUUID().toString(),
    ): Diagnostic? =
        MOTIF_JAVAC.find(ligne)?.let { concordance -> diagnosticJavac(concordance, id) }
            ?: MOTIF_KOTLINC.find(ligne)?.let { concordance -> diagnosticKotlinc(concordance, id) }

    /** Construit le diagnostic d'une ligne javac. */
    private fun diagnosticJavac(
        concordance: MatchResult,
        id: String,
    ): Diagnostic =
        Diagnostic(
            id = id,
            protocolVersion = GradleProtocol.PROTOCOL_VERSION,
            severity =
                when (concordance.groupValues[4]) {
                    "error" -> DiagnosticSeverity.ERROR
                    "warning" -> DiagnosticSeverity.WARNING
                    else -> DiagnosticSeverity.INFO
                },
            file = concordance.groupValues[1],
            line = concordance.groupValues[2].toLong(),
            column = concordance.groupValues[3].ifEmpty { "1" }.toLong(),
            message = concordance.groupValues[5].trim(),
            source = "javac",
        )

    /** Construit le diagnostic d'une ligne kotlinc. */
    private fun diagnosticKotlinc(
        concordance: MatchResult,
        id: String,
    ): Diagnostic =
        Diagnostic(
            id = id,
            protocolVersion = GradleProtocol.PROTOCOL_VERSION,
            severity =
                if (concordance.groupValues[1] == "e") DiagnosticSeverity.ERROR else DiagnosticSeverity.WARNING,
            file = concordance.groupValues[2],
            line = concordance.groupValues[3].toLong(),
            column = concordance.groupValues[4].toLong(),
            message = concordance.groupValues[5].trim(),
            source = "kotlinc",
        )
}

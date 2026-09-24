package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.StreamKind
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Flux de sortie de build → événements (§4.3) : Gradle pompe sa sortie
 * standard/erreur depuis SES propres fils — chaque ligne complète devient
 * un [BuildOutput] publié sur l'[EventBus] (contre-pression = contrôle de
 * flux légitime, voir [EventBus.publier]).
 *
 * L'encodage est UTF-8 : les suites multi-octets coupées entre deux
 * `write()` sont réassemblées dans le tampon avant décodage — `String`
 * n'est construite qu'aux frontières de lignes, où une suite UTF-8 est
 * nécessairement complète ('\n' n'est jamais un octet de continuation).
 * Le '\r' final d'une fin Windows est retiré : la ligne est le contenu,
 * pas la terminaison.
 *
 * G5 : [observateur] reçoit chaque ligne décodée AVANT publication — le
 * [ParseurDiagnostics] y extrait les diagnostics de compilation (ils ne
 * sont PAS dans le message d'échec final, mais dans la sortie erreur du
 * compilateur).
 */
internal class StreamingOutputStream(
    private val buildId: String,
    private val flux: StreamKind,
    private val bus: EventBus,
    private val observateur: ((String) -> Unit)? = null,
) : OutputStream() {
    private val tampon = ByteArrayOutputStream()
    private val verrou = Any()

    override fun write(octet: Int) {
        synchronized(verrou) {
            if (octet == '\n'.code) {
                viderLigne()
            } else {
                tampon.write(octet)
            }
        }
    }

    override fun write(
        octets: ByteArray,
        depart: Int,
        longueur: Int,
    ) {
        if (longueur <= 0) return
        synchronized(verrou) {
            var index = depart
            val fin = depart + longueur
            while (index < fin) {
                val finLigne = chercherFinLigne(octets, index, fin)
                if (finLigne < fin) {
                    tampon.write(octets, index, finLigne - index)
                    index = finLigne + 1
                    viderLigne()
                } else {
                    tampon.write(octets, index, fin - index)
                    index = fin
                }
            }
        }
    }

    /** Dernière ligne sans terminaison (build fini, tampon non vide). */
    override fun close() {
        synchronized(verrou) {
            viderLigne()
        }
    }

    /** Publie le tampon courant comme ligne complète et le réinitialise. */
    private fun viderLigne() {
        if (tampon.size() == 0) return
        var octets = tampon.toByteArray()
        tampon.reset()
        if (octets.isNotEmpty() && octets.last() == '\r'.code.toByte()) {
            octets = octets.copyOf(octets.size - 1)
        }
        if (octets.isEmpty()) return
        val texte = octets.toString(Charsets.UTF_8)
        observateur?.invoke(texte)
        bus.publier(
            BuildOutput(
                id = nouvelId(),
                protocolVersion = jo.codeide.tooling.protocol.GradleProtocol.PROTOCOL_VERSION,
                buildId = buildId,
                stream = flux,
                line = texte,
                timestampMs = System.currentTimeMillis(),
            ),
        )
    }

    private fun chercherFinLigne(
        octets: ByteArray,
        depart: Int,
        fin: Int,
    ): Int {
        val saut = '\n'.code.toByte()
        for (i in depart until fin) {
            if (octets[i] == saut) return i
        }
        return fin
    }
}

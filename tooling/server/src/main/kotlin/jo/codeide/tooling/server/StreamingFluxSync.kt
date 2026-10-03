package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.StreamKind
import jo.codeide.tooling.protocol.SyncOutput
import java.io.ByteArrayOutputStream
import java.io.OutputStream

/**
 * Flux de sortie de SYNC → événements (v0.48.0, ADR 0079) : miroir de
 * [StreamingOutputStream] pour le canal Sync — Gradle pompe la sortie
 * standard/erreur de l'action de synchronisation depuis ses propres fils,
 * chaque ligne complète devient un [SyncOutput] publié sur l'[EventBus]
 * (contre-pression = contrôle de flux légitime, voir [EventBus.publier]).
 *
 * Pourquoi une classe à part et pas un `buildId` de plus : la capture
 * v0.41.1 utilisait l'identifiant de la REQUÊTE sync comme `buildId`, or le
 * client n'ouvrait un canal de sortie QUE pour les identifiants de BUILD —
 * chaque ligne était publiée puis JETÉE à la réception (publication morte,
 * retirée en v0.45.1). Le canal Sync a désormais SON message (`type` =
 * `sync_output`, dossier du projet à la clé) et SON côté client
 * (`observeSortiesSync` du port) : la publication ne peut plus être jetée
 * par construction.
 *
 * L'encodage est UTF-8 : les suites multi-octets coupées entre deux
 * `write()` sont réassemblées dans le tampon avant décodage — `String`
 * n'est construite qu'aux frontières de lignes, où une suite UTF-8 est
 * nécessairement complète ('\n' n'est jamais un octet de continuation).
 * Le '\r' final d'une fin Windows est retiré : la ligne est le contenu,
 * pas la terminaison.
 */
internal class StreamingFluxSync(
    private val projectDir: String,
    private val flux: StreamKind,
    private val bus: EventBus,
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

    /** Dernière ligne sans terminaison (sync finie, tampon non vide). */
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
        bus.publier(
            SyncOutput(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                projectDir = projectDir,
                stream = flux,
                line = octets.toString(Charsets.UTF_8),
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

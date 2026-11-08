package jo.codeide.tooling.protocol

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Frame trop grande : la longueur annoncée dépasse
 * [GradleProtocol.MAX_FRAME_SIZE] (garde DoS mémoire, §3.1) — rejetée
 * **avant** toute allocation.
 */
public class FrameTropGrandeException(
    tailleAnnoncee: Long,
) : IOException("Frame annoncée de $tailleAnnoncee octets (max ${GradleProtocol.MAX_FRAME_SIZE})")

/**
 * Frame tronquée ou corrompue : la longueur annoncée ne correspond pas
 * aux octets réellement lisibles (pair mort en cours d'envoi, flux
 * coupé).
 */
public class FrameTronqueeException(
    tailleAnnoncee: Long,
    octetsLus: Int,
) : IOException("Frame tronquée : $octetsLus octets lus sur $tailleAnnoncee annoncés")

/** Longueur annoncée invalide (négative ou nulle). */
public class FrameInvalideException(
    tailleAnnoncee: Long,
) : IOException("Longueur de frame invalide : $tailleAnnoncee")

/**
 * Codage et décodage du framing du protocole (§3.1) :
 *
 * ```
 * ┌──────────────┬─────────────────────────────────┐
 * │  4 octets     │  N octets                        │
 * │ (Int, BE)     │  payload JSON                     │
 * │ longueur N    │  {"type":"...", ...}              │
 * └──────────────┴─────────────────────────────────┘
 * ```
 *
 * `FrameCodec` ne connaît que des [ByteArray] — agnostique du format de
 * contenu, pour permettre un format binaire partiel plus tard sans
 * toucher au framing. Utilisable des deux côtés : `OutputStream`/
 * `InputStream` suffisent (socket NIO côté JVM, `LocalSocket` côté
 * Android).
 *
 * Le **timeout de lecture par frame** (§3.1 : un pair qui annonce une
 * longueur puis n'envoie rien) relève du socket (SO_TIMEOUT côté JVM,
 * opérations bornées côté Android) — pas du codec.
 */
public object FrameCodec {
    /** Taille de l'en-tête de longueur (Int gros-boutiste). */
    public const val TAILLE_ENTETE: Int = 4

    /**
     * Écrit une frame complète : 4 octets de longueur (gros-boutiste)
     * puis le payload. Un payload vide est refusé — un message JSON fait
     * au moins deux octets (`{}`).
     *
     * T-F1 (Phase 2) : l'en-tête et le payload partent en UN seul appel
     * `write` (tampon réuni) — les octets du fil sont IDENTIQUES, seuls
     * le nombre d'appels système et de réveils du pair changent (le
     * verdict du 2026-11-08 a désigné le coût par frame ; l'écriture
     * groupée en est le correctif serveur).
     *
     * Exemption detekt ciblée (règle 16) : MagicNumber — les décalages
     * 24/16/8 et le masque 0xFF **sont** l'encodage gros-boutiste
     * canonique, les nommer contredirait leur évidence.
     */
    @Suppress("MagicNumber")
    public fun writeFrame(
        output: OutputStream,
        payload: ByteArray,
    ) {
        require(payload.isNotEmpty()) { "Payload vide : un message JSON fait au moins {}" }
        require(payload.size <= GradleProtocol.MAX_FRAME_SIZE) {
            "Payload de ${payload.size} octets au-delà de MAX_FRAME_SIZE"
        }
        val taille = payload.size
        // En-tête 4 octets gros-boutiste réuni au payload : un SEUL appel
        // `write` par frame (contre quatre + payload avant T-F1).
        val frame = ByteArray(TAILLE_ENTETE + taille)
        frame[0] = ((taille ushr 24) and 0xFF).toByte()
        frame[1] = ((taille ushr 16) and 0xFF).toByte()
        frame[2] = ((taille ushr 8) and 0xFF).toByte()
        frame[3] = (taille and 0xFF).toByte()
        payload.copyInto(frame, TAILLE_ENTETE)
        output.write(frame)
        output.flush()
    }

    /**
     * Écrit un GROUPE de frames en un seul appel `write` (T-F3, Phase 2 :
     * vidange groupée) : chaque payload garde son en-tête propre — les
     * octets du fil sont la CONCATÉNATION exacte de ce que
     * [writeFrame] aurait produit, frame après frame. Le groupe part en
     * un appel système : sous une rafale de sortie de build, le pair se
     * réveille UNE fois pour N frames au lieu de N fois.
     *
     * Le PLAFOND de rétention vit chez l'APPELANT (il ne retient jamais
     * une ligne pour grossir le groupe — il ne groupe que ce qui est
     * déjà en file) : ce codec reste pur et sans politique.
     *
     * Exemption detekt ciblée (règle 16) : MagicNumber — même encodage
     * gros-boutiste canonique que [writeFrame].
     */
    @Suppress("MagicNumber")
    public fun writeFrames(
        output: OutputStream,
        payloads: List<ByteArray>,
    ) {
        require(payloads.isNotEmpty()) { "Groupe vide : rien à écrire" }
        var total = 0
        for (payload in payloads) {
            require(payload.isNotEmpty()) { "Payload vide : un message JSON fait au moins {}" }
            require(payload.size <= GradleProtocol.MAX_FRAME_SIZE) {
                "Payload de ${payload.size} octets au-delà de MAX_FRAME_SIZE"
            }
            total += TAILLE_ENTETE + payload.size
        }
        val groupe = ByteArray(total)
        var curseur = 0
        for (payload in payloads) {
            val taille = payload.size
            groupe[curseur] = ((taille ushr 24) and 0xFF).toByte()
            groupe[curseur + 1] = ((taille ushr 16) and 0xFF).toByte()
            groupe[curseur + 2] = ((taille ushr 8) and 0xFF).toByte()
            groupe[curseur + 3] = (taille and 0xFF).toByte()
            payload.copyInto(groupe, curseur + TAILLE_ENTETE)
            curseur += TAILLE_ENTETE + taille
        }
        output.write(groupe)
        output.flush()
    }

    /**
     * Lit une frame complète : décode l'en-tête, borne la longueur
     * annoncée contre [GradleProtocol.MAX_FRAME_SIZE] (rejet avant
     * allocation), puis lit exactement N octets — un flux qui se coupe
     * avant la fin lève [FrameTronqueeException].
     *
     * Exemption detekt ciblée (règle 16) : ThrowsCount — chaque `throw`
     * est une **issue typée** du contrat de framing (longueur invalide,
     * garde DoS, troncature en plein payload) ; les fusionnerait en une
     * exception générique ferait perdre au dispatcher (§4.5) la
     * distinction corruption/déconnexion.
     */
    @Suppress("ThrowsCount")
    public fun readFrame(input: InputStream): ByteArray {
        val tailleAnnoncee = lireEntete(input)

        if (tailleAnnoncee <= 0 || tailleAnnoncee > Int.MAX_VALUE) {
            throw FrameInvalideException(tailleAnnoncee)
        }
        if (tailleAnnoncee > GradleProtocol.MAX_FRAME_SIZE) {
            throw FrameTropGrandeException(tailleAnnoncee)
        }

        val taille = tailleAnnoncee.toInt()
        val payload = ByteArray(taille)
        var lus = 0
        while (lus < taille) {
            val n = input.read(payload, lus, taille - lus)
            if (n < 0) {
                throw FrameTronqueeException(tailleAnnoncee, lus)
            }
            lus += n
        }
        return payload
    }

    /**
     * Décode l'en-tête de 4 octets en longueur non négative.
     *
     * Une fin de flux **dès le premier octet** est une fin de connexion
     * propre ([EOFException] — le dispatcher la traite comme une
     * déconnexion, §4.5) ; une coupure en plein en-tête est une frame
     * tronquée ([FrameTronqueeException]).
     *
     * T-F2 (Phase 2) : les 4 octets se lisent en UN appel `read` sur un
     * tampon (boucle jusqu'à complète) — plus un appel système PAR
     * OCTET d'en-tête. La sémantique d'erreur est inchangée octet pour
     * octet : EOF au premier octet, troncature ensuite, valeur partielle
     * décodée sur les octets réellement lus.
     *
     * Exemption detekt ciblée (règle 16) : MagicNumber — le décalage 8
     * est la lecture gros-boutiste octet par octet, le nommer serait du
     * bruit (même justification que [writeFrame]).
     */
    @Suppress("MagicNumber")
    private fun lireEntete(input: InputStream): Long {
        val octets = ByteArray(TAILLE_ENTETE)
        var lus = 0
        while (lus < TAILLE_ENTETE) {
            val n = input.read(octets, lus, TAILLE_ENTETE - lus)
            if (n < 0) {
                if (lus == 0) {
                    throw EOFException("Fin de flux avant toute frame")
                }
                throw FrameTronqueeException(decodePartiel(octets, lus), lus)
            }
            lus += n
        }
        var entete = 0L
        for (octet in octets) {
            entete = (entete shl 8) or (octet.toLong() and 0xFF)
        }
        return entete
    }

    /** Valeur partielle d'un en-tête coupé en cours de lecture (les
     *  [lus] premiers octets, gros-boutistes) — mêmes chiffres que
     *  l'ancienne lecture octet par octet dans [FrameTronqueeException]. */
    @Suppress("MagicNumber")
    private fun decodePartiel(
        octets: ByteArray,
        lus: Int,
    ): Long {
        var entete = 0L
        for (i in 0 until lus) {
            entete = (entete shl 8) or (octets[i].toLong() and 0xFF)
        }
        return entete
    }
}

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
        // En-tête 4 octets gros-boutiste — octet par octet :
        // OutputStream.write(Int) n'écrit que l'octet de poids faible.
        output.write((taille ushr 24) and 0xFF)
        output.write((taille ushr 16) and 0xFF)
        output.write((taille ushr 8) and 0xFF)
        output.write(taille and 0xFF)
        output.write(payload)
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

    /** Décode l'en-tête de 4 octets en longueur non négative.
     *
     * Une fin de flux **dès le premier octet** est une fin de connexion
     * propre ([EOFException] — le dispatcher la traite comme une
     * déconnexion, §4.5) ; une coupure en plein en-tête est une frame
     * tronquée ([FrameTronqueeException]).
     *
     * Exemption detekt ciblée (règle 16) : MagicNumber — le décalage 8
     * est la lecture gros-boutiste octet par octet, le nommer serait du
     * bruit (même justification que [writeFrame]).
     */
    @Suppress("MagicNumber")
    private fun lireEntete(input: InputStream): Long {
        var entete = 0L
        for (octet in 0 until TAILLE_ENTETE) {
            val valeur = input.read()
            if (valeur < 0) {
                if (octet == 0) {
                    throw EOFException("Fin de flux avant toute frame")
                }
                throw FrameTronqueeException(entete, octet)
            }
            entete = (entete shl 8) or valeur.toLong()
        }
        return entete
    }
}

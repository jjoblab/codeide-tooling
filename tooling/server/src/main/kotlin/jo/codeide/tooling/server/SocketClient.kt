package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.FrameCodec
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.channels.Channels
import java.nio.channels.SocketChannel
import java.nio.file.Path
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Client du socket Unix de l'app (§4.1) : l'orchestrateur EST le client —
 * l'app a ouvert l'écoute AVANT de le lancer, il s'y connecte dès son
 * démarrage (aucun fichier de découverte, aucun polling).
 *
 * JDK 16+ : `UnixDomainSocketAddress`/`SocketChannel` standards, aucune
 * dépendance tierce (§4.1) — c'est aussi ce qui permet de tester ce module
 * sans Android (§7.2). Les canaux NIO implémentent `InterruptibleChannel` :
 * la connexion bloquante s'interrompt proprement à l'annulation de la
 * coroutine (`runInterruptible`), le délai est porté par [connecter].
 *
 * Écritures (v0.49.0, ADR 0080) : UN fil écrivain unique consomme une file
 * FIFO bornée — plus JAMAIS d'écriture directe concurrente une fois
 * [demarrerEcrivain] lancé. Avant ce démarrage (handshake), les écritures
 * restent directes sous verrou : elles viennent du fil principal, seules
 * et séquentielles. Le PONG dispose d'une file PRIORITAIRE distincte
 * (débit nul : un ping toutes les 5 s) : la réponse de santé ne peut plus
 * être ensevelie sous des frames d'événements, NI attendre un verrou tenu
 * par une écriture bloquée (le correctif v0.37.3 écrivait le pong hors bus
 * mais TOUJOURS sous le moniteur partagé de [envoyer] : une écriture
 * d'événement bloquée sur un tampon de réception plein de l'app gardait le
 * moniteur et ensevelissait le pong quand même — « orchestrateur muet »
 * en plein build, retour terrain v0.48.0). L'ordre RELATIF pong/événements
 * peut s'inverser, sans conséquence (le pong ne porte aucune relation
 * d'ordre, cf. v0.37.3).
 *
 * Phase 1 (angle mort n°3) : la file normale porte des [FrameFile] horodatées
 * à l'enfilement — la profondeur maximale ET l'âge de la plus ancienne frame
 * se mesurent ENFIN (l'alerte historique ne se déclenchait qu'à 512 frames,
 * hors d'atteinte du scénario du propriétaire : ≈ 60 lignes). La règle
 * d'alerte devient relative et temporelle : profondeur ≥ 32 OU âge ≥ 1 s.
 * L'[InstrumentationTransport] (T1b/T2b) complète ces mesures quand le
 * drapeau `--instru-transport` est actif.
 *
 * Exemption detekt ciblée (règle 16) : TooManyFunctions — chaque fonction
 * est une pièce du contrat (écrire, lire, fermer) OU du diagnostic Phase 1
 * (profondeur, statistiques, identifiant du fil) ; les regrouper en une
 * classe « stats » séparée exigerait de partager les états internes de la
 * file, précisément ce que l'unicité d'écrivain interdit.
 */
@Suppress("TooManyFunctions")
internal class SocketClient private constructor(
    private val canal: SocketChannel,
) {
    /** Nombre d'appels `write` observés par le compteur (T2b), 0 sinon. */
    private val appelsEcriture = AtomicLong(0L)

    /** Datation du dernier avertissement de profondeur (bornage du débit). */
    private val dernierAvertissementMs = AtomicLong(0L)

    /** Profondeur maximale de la file normale depuis le dernier relevé. */
    private val profondeurMax = AtomicInteger(0)

    /** Âge maximal (ms) d'une frame au moment de son écriture. */
    private val ageMaxMs = AtomicLong(0L)

    private val entree: InputStream = Channels.newInputStream(canal)

    /**
     * Sortie brute du canal, ÉVENTUELLEMENT enveloppée d'un compteur
     * d'appels système (T2b — actif avec `--instru-transport` uniquement) :
     * [FrameCodec.writeFrame] écrit 4 octets d'en-tête + payload + flush ;
     * le compteur PROUVE le nombre d'appels `write` par frame au lieu de le
     * supposer.
     */
    private val sortie: OutputStream =
        if (InstrumentationTransport.active) {
            CompteurAppels(Channels.newOutputStream(canal), appelsEcriture)
        } else {
            Channels.newOutputStream(canal)
        }

    /**
     * File FIFO des frames normales (événements, réponses d'erreur) :
     * bornée, [envoyer] BLOQUE sous contre-pression — la sémantique
     * sans perte de l'[EventBus] est conservée de bout en bout. Chaque
     * entrée porte son instant d'enfilement (angle mort n°3 : l'âge de la
     * plus ancienne frame ne se devine pas, il se mesure).
     */
    private val fileNormale = ArrayBlockingQueue<FrameFile>(CAPACITE_FILE)

    /**
     * File prioritaire des frames de SANTÉ (pong) : non bornée par
     * construction (débit nul — un ping toutes les 5 s au plus), elle ne
     * peut ni déborder la mémoire ni retarder le pong.
     */
    private val filePrioritaire = ConcurrentLinkedDeque<ByteArray>()

    @Volatile
    private var ecrivain: Thread? = null

    @Volatile
    private var actif = false

    /** Écrit une frame complète (en-tête + payload). */
    fun envoyer(payload: ByteArray) {
        if (ecrivain == null) {
            // Phase de handshake : fil principal, écritures séquentielles.
            synchronized(this) { FrameSocket.ecrireFrame(sortie, payload) }
        } else {
            // put() bloquant : contre-pression vers l'émetteur, aucune perte.
            fileNormale.put(FrameFile(payload, System.currentTimeMillis()))
            InstrumentationTransport.enregistrerProfondeurFileEcriture(fileNormale.size, enfilement = true)
        }
    }

    /**
     * Écrit une frame de santé (pong) : TOUJOURS prioritaire sur les
     * frames normales — elle ne patiente JAMAIS derrière la file
     * d'événements, même sous rafale de sortie de build.
     */
    fun envoyerPrioritaire(payload: ByteArray) {
        if (ecrivain == null) {
            synchronized(this) { FrameSocket.ecrireFrame(sortie, payload) }
        } else {
            filePrioritaire.addLast(payload)
        }
    }

    /**
     * Démarre le fil écrivain unique (idempotent) — appelé par
     * [ServerMain] une fois le handshake négocié : à partir de là, toute
     * frame passe par sa file, plus aucune écriture directe.
     */
    fun demarrerEcrivain() {
        if (ecrivain?.isAlive == true) return
        actif = true
        ecrivain =
            thread(isDaemon = true, name = "gradle-server-ecrivain") {
                try {
                    while (actif || fileNormale.isNotEmpty() || filePrioritaire.isNotEmpty()) {
                        ecrireProchaineFrame()
                    }
                } catch (interruption: InterruptedException) {
                    // arrêt demandé : vider ce qui reste, au mieux — la file
                    // prioritaire (ByteArray) et la file normale (FrameFile)
                    // se vident séparément, chacune dans l'ordre.
                    var framePrioritaire = filePrioritaire.pollFirst()
                    while (framePrioritaire != null) {
                        runCatching { FrameSocket.ecrireFrame(sortie, framePrioritaire) }
                        framePrioritaire = filePrioritaire.pollFirst()
                    }
                    while (true) {
                        val frame = fileNormale.poll() ?: break
                        runCatching { FrameSocket.ecrireFrame(sortie, frame.octets) }
                    }
                } catch (t: Throwable) {
                    Journal.error("écrivain du socket interrompu : ${t.message}", t)
                }
            }
    }

    /**
     * Écrit la prochaine frame en file — priorité santé d'abord (un pong en
     * attente passe devant TOUT événement), sinon la plus ancienne frame
     * normale, en mesurant ce qui doit l'être (Phase 1).
     */
    private fun ecrireProchaineFrame() {
        val framePrioritaire = filePrioritaire.pollFirst()
        if (framePrioritaire != null) {
            FrameSocket.ecrireFrame(sortie, framePrioritaire)
            return
        }
        val frame = fileNormale.poll(INTERVALLE_MS, TimeUnit.MILLISECONDS) ?: return
        ecrireEnMesurant(frame)
        // Phase 1 (angle mort n°3) : la profondeur de la file normale nomme
        // le retard d'écriture vers l'app — et désormais son ÂGE aussi
        // (règle relative/temporelle).
        avertirSiRetard(frame)
    }

    /**
     * Écrit UNE frame normale en mesurant ce qui doit l'être (Phase 1) :
     * durée (T2b), appels système (T2b, via [CompteurAppels]) et âge à
     * l'écriture (T1b — différence entre l'enfilement et maintenant).
     */
    private fun ecrireEnMesurant(frame: FrameFile) {
        val avant = appelsEcriture.get()
        val depart = System.currentTimeMillis()
        FrameSocket.ecrireFrame(sortie, frame.octets)
        val dureeMs = System.currentTimeMillis() - depart
        InstrumentationTransport.enregistrerEcritureFrame(dureeMs, appelsEcriture.get() - avant)
        val age = System.currentTimeMillis() - frame.enfileeMs
        if (age > ageMaxMs.get()) ageMaxMs.updateAndGet { actuel -> maxOf(actuel, age) }
        InstrumentationTransport.enregistrerAgeFrame(age)
    }

    /**
     * Avertit (borné en débit) quand le retard d'écriture se FORME : la
     * règle historique (profondeur ≥ 512) ne se déclenchait JAMAIS sur le
     * scénario du propriétaire (≈ 60 lignes) — un retard de 20 s avec une
     * file à 3 frames restait muet. Nouvelle règle (§3.2) : profondeur
     * ≥ 32 OU âge de la frame la plus ancienne ≥ 1 s.
     */
    private fun avertirSiRetard(frame: FrameFile) {
        val profondeur = fileNormale.size
        if (profondeur > profondeurMax.get()) profondeurMax.updateAndGet { actuel -> maxOf(actuel, profondeur) }
        val age = System.currentTimeMillis() - frame.enfileeMs
        if (profondeur < SEUIL_ALERTE_PROFONDEUR && age < SEUIL_ALERTE_AGE_MS) return
        val maintenant = System.currentTimeMillis()
        val dernier = dernierAvertissementMs.get()
        if (maintenant - dernier >= INTERVALLE_ALERTE_MS &&
            dernierAvertissementMs.compareAndSet(dernier, maintenant)
        ) {
            Journal.warn(
                "file d'écriture socket à $profondeur frame(s), plus ancienne âgée de $age ms — l'app lit en retard",
            )
        }
    }

    /** Profondeur courante de la file d'écriture (conclusion de build, §3.2). */
    fun profondeurFile(): Int = fileNormale.size

    /** Identifiant du fil écrivain (T2b/T3b), -1 s'il n'a jamais démarré. */
    fun idFilEcrivain(): Long = ecrivain?.threadId() ?: -1L

    /**
     * Relevé diagnostique de la file d'écriture pour la ligne de conclusion
     * d'un build : profondeur finale, maximale atteinte et âge maximal.
     */
    fun statistiquesFile(): String =
        "ecriture: ${fileNormale.size} (max ${profondeurMax.get()}, age max ${ageMaxMs.get()} ms)"

    /**
     * Lit une frame complète, bloquant. Lève [java.io.EOFException] sur une
     * fin de connexion propre (le dispatcher la distingue de la corruption,
     * §4.5) — les frames tronquées/corrompues lèvent leurs exceptions typées.
     */
    fun lireFrame(): ByteArray = FrameSocket.lireFrame(entree)

    /**
     * Referme le canal (idempotent) — l'écrivain reçoit d'abord une fenêtre
     * bornée pour vider les files (aucune perte évitable), puis le canal se
     * ferme : ce qui n'a pas passé est perdu avec la connexion, comme
     * toujours à la rupture.
     */
    fun fermer() {
        arreterEcrivain()
        runCatching { canal.close() }
    }

    /** Fenêtre bornée de vidange de l'écrivain (idempotent). */
    private fun arreterEcrivain() {
        actif = false
        ecrivain?.interrupt()
        ecrivain?.join(ATTENTE_JOINTURE_MS)
        ecrivain = null
    }

    companion object {
        /**
         * Capacité de la file normale (v0.49.0) : reprend la capacité de
         * l'EventBus (8192) — la contre-pression naît au même ordre de
         * grandeur qu'avant le correctif, jamais plus tôt.
         */
        private const val CAPACITE_FILE = 8192

        /** Attente max d'une frame avant de réévaluer l'état d'activité. */
        private const val INTERVALLE_MS = 100L

        /** Attente max de fin de l'écrivain à la fermeture. */
        private const val ATTENTE_JOINTURE_MS = 2_000L

        /** Profondeur au-delà de laquelle la file normale est signalée (§3.2). */
        private const val SEUIL_ALERTE_PROFONDEUR = 32

        /** Âge de la plus ancienne frame au-delà duquel elle est signalée (§3.2). */
        private const val SEUIL_ALERTE_AGE_MS = 1_000L

        /** Bornage du débit des avertissements de retard (5 s). */
        private const val INTERVALLE_ALERTE_MS = 5_000L

        /**
         * Connecte au socket [chemin] dans les [delaiMs] (§3.4 :
         * `CONNECT_TIMEOUT_MS`). Au délai, le canal est fermé — un canal NIO
         * interrompu/fermé débloque le thread de connexion.
         */
        suspend fun connecter(
            chemin: Path,
            delaiMs: Long,
        ): SocketClient {
            val canal = SocketChannel.open(StandardProtocolFamily.UNIX)
            try {
                withTimeout(delaiMs) {
                    runInterruptible {
                        canal.configureBlocking(true)
                        canal.connect(UnixDomainSocketAddress.of(chemin))
                    }
                }
                return SocketClient(canal)
            } catch (t: Throwable) {
                runCatching { canal.close() }
                throw IOException("Connexion impossible à $chemin dans les $delaiMs ms : ${t.message}", t)
            }
        }
    }
}

/**
 * Frame en file d'écriture, avec son instant d'enfilement (Phase 1, angle
 * mort n°3) : l'âge de la plus ancienne frame devient mesurable au passage
 * vers le socket — c'est LUI qui distingue « l'écrivain est lent » de
 * « l'app ne lit pas ».
 */
internal class FrameFile(
    val octets: ByteArray,
    val enfileeMs: Long,
)

/**
 * Enveloppe comptant les appels `write` sur le flux de sortie (T2b) :
 * prouve le nombre d'appels système par frame au lieu de le supposer
 * (hypothèse H-T8 — 4 octets d'en-tête + payload = 5 appels attendus).
 * Thread-safe par construction (un seul fil écrivain) ; le compteur vit
 * dans [SocketClient] pour être lu sans contention.
 */
private class CompteurAppels(
    private val flux: OutputStream,
    private val compteur: AtomicLong,
) : OutputStream() {
    override fun write(octet: Int) {
        flux.write(octet)
        compteur.incrementAndGet()
    }

    override fun write(
        octets: ByteArray,
        depart: Int,
        longueur: Int,
    ) {
        flux.write(octets, depart, longueur)
        compteur.incrementAndGet()
    }
}

/** Framing (§3.1) vu du serveur : délègue au [FrameCodec] du protocole. */
internal object FrameSocket {
    fun ecrireFrame(
        sortie: OutputStream,
        payload: ByteArray,
    ) {
        FrameCodec.writeFrame(sortie, payload)
    }

    fun lireFrame(entree: InputStream): ByteArray = FrameCodec.readFrame(entree)
}

package jo.codeide.tooling.daemon

import jo.codeide.core.domain.AppLogger
import jo.codeide.core.domain.DispatcherProvider
import jo.codeide.core.domain.ManagedProcess
import jo.codeide.core.domain.NativeProcessLauncher
import jo.codeide.core.domain.ToolchainLocator
import jo.codeide.tooling.client.EchecHandshakeClient
import jo.codeide.tooling.client.GradleApiImpl
import jo.codeide.tooling.client.SessionTooling
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.PingMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Daemon du tooling Gradle (§5.4) : cycle de vie complet de l'orchestrateur
 * JVM — déploiement du JAR, écoute du socket **avant** le lancement (§5.1),
 * lancement via le port [NativeProcessLauncher] (**jamais redéfini** :
 * l'implémentation `core:bootstrap` construit l'environnement canonique),
 * health check ping/pong, redémarrage borné à
 * [GradleProtocol.MAX_RECONNECT_ATTEMPTS], réinjection du stderr du process
 * dans le journal applicatif (tag `gradle-server`, ADR 0040).
 *
 * Machine d'états (observable via [GradleApiImpl.observeConnectionState]) :
 * `DECONNECTEE` → `EN_CONNEXION` → `CONNECTEE` → (perte → `EN_CONNEXION` →
 * …) → `ECHOUEE` après épuisement des tentatives. JDK absent : retour à
 * `DECONNECTEE` sans aucun lancement (le tooling exige le bootstrap de
 * l'écran Terminal — signalé au journal, jamais de boucle de tentatives).
 *
 * Sécurité (§4.4) : secret de handshake FRAIS par tentative
 * ([GenerateurSecret]), passé AU process par argument de commande — jamais
 * écrit, jamais journalisé ; le répertoire du socket est privé (`0700`,
 * géré par l'hôte).
 *
 * Nettoyage SYNCHRONE dans les terminaisons (leçon T2 : tout appel
 * suspendu depuis une coroutine annulée ne revient pas) : `kill`,
 * `fermer`, `fermerSession` ne suspendent jamais.
 *
 * @param fabriqueCommande construit la ligne de commande du process —
 * couture du bout-en-bout §7.4 (le test relance le VRAI orchestrateur
 * par classpath plutôt que par JAR déployé) ; la production lance
 * `java -Xmx… -jar gradle-server.jar --socket … --secret …`.
 * @param intervalleSanteMs période des pings du health check (§5.4 : 5 s).
 * @param delaiSanteMs délai d'expiration d'un pong (§5.4 : 15 s).
 * @param delaiRelanceMs attente entre deux tentatives (repli exponentiel
 * léger : doublée à chaque échec, bornée).
 */
@Suppress("LongParameterList")
class DaemonManager
    internal constructor(
        private val lanceur: NativeProcessLauncher,
        private val outilchain: ToolchainLocator,
        private val api: GradleApiImpl,
        private val hote: HoteSocketTooling,
        private val deployeur: JarDeployer,
        private val journal: AppLogger,
        private val dispatchers: DispatcherProvider,
        private val fabriqueCommande: (java: File, jar: File, cheminSocket: File, secret: String) -> List<String> =
            commandeParDefaut(),
        private val intervalleSanteMs: Long = GradleProtocol.HEARTBEAT_INTERVAL_MS,
        private val delaiSanteMs: Long = GradleProtocol.HEARTBEAT_TIMEOUT_MS,
        private val delaiRelanceMs: Long = DELAI_RELANC_E_MS,
    ) {
        /** Surveillance en cours (null = daemon arrêté). */
        private var surveillance: Job? = null

        /** Arrêt demandé par [arreter] — coupe la boucle de relances. */
        @Volatile
        private var arretDemande = false

        /**
         * Démarre (ou redémarre après [arreter]) le daemon — idempotent
         * tant qu'une surveillance tourne.
         *
         * @param portee portée hôte (application ou test) : le daemon y
         * lance sa surveillance ; l'annulation de la portée arrête tout.
         */
        @Synchronized
        fun demarrer(portee: CoroutineScope) {
            if (surveillance?.isActive == true) return
            arretDemande = false
            surveillance =
                portee.launch(dispatchers.default) {
                    boucleDeVie()
                }
        }

        /**
         * Arrête le daemon : coupe la surveillance, tue le process,
         * referme l'écoute et la session — SYNCHRONE (jamais suspendu),
         * idempotent. La connexion retombe à `DECONNECTEE` : un arrêt
         * demandé n'est pas un échec.
         */
        @Synchronized
        fun arreter() {
            arretDemande = true
            surveillance?.cancel()
            surveillance = null
        }

        // ------------------------------------------------------------------
        // Boucle de vie.
        // ------------------------------------------------------------------

        /**
         * Boucle de tentatives bornées. Échecs DÉFINITIFS (sans relance) :
         * handshake refusé ([EchecHandshakeClient] — version incompatible
         * ou secret refusé), arguments invalides du process (code 2 : notre
         * bug), JAR indisponible. Tout le reste (process mort, connexion
         * perdue, lancement impossible) consomme une tentative.
         *
         * Exemptions detekt ciblées (règle 16) : ReturnCount et
         * LoopWithTooManyJumpStatements — chaque sortie de boucle porte une
         * DÉCISION distincte (échec définitif, arrêt demandé, épuisement,
         * nouvelle tentative) ; même justification que les codes de sortie
         * de ServerMain, factoriser ces gardes en états intermédiaires
         * masquerait la machine d'états.
         */
        @Suppress("ReturnCount", "LoopWithTooManyJumpStatements")
        private suspend fun boucleDeVie() {
            val java = javaExecutable()
            if (java == null) {
                journal.w(TAG) { "JDK introuvable — le tooling Gradle exige le bootstrap de l'écran Terminal" }
                // Pas un échec : un état. DECONNECTEE, aucun lancement, la
                // prochaine demande de démarrage re-testera (l'installation
                // du bootstrap peut intervenir entre-temps).
                return
            }

            var tentatives = 0
            var relance = delaiRelanceMs
            api.marquerEnConnexion()
            while (!arretDemande && tentatives < GradleProtocol.MAX_RECONNECT_ATTEMPTS) {
                try {
                    val definitive = tentative(java)
                    if (definitive) {
                        api.marquerEchouee()
                        return
                    }
                    // Tentative complète qui se termine SANS échec définitif
                    // (process mort ou connexion perdue) : consomme une
                    // tentative, sauf arrêt demandé (terminaison propre).
                    if (arretDemande) return
                    tentatives++
                    if (tentatives >= GradleProtocol.MAX_RECONNECT_ATTEMPTS) break
                    journal.w(TAG) {
                        "orchestrateur perdu — relance $tentatives/${GradleProtocol.MAX_RECONNECT_ATTEMPTS} " +
                            "dans $relance ms"
                    }
                    api.marquerEnConnexion()
                } catch (refus: EchecHandshakeClient) {
                    journal.e(TAG) { "handshake refusé, échec définitif : ${refus.message}" }
                    api.marquerEchouee()
                    return
                } catch (impossible: IOException) {
                    tentatives++
                    if (tentatives >= GradleProtocol.MAX_RECONNECT_ATTEMPTS) break
                    journal.w(TAG) {
                        "lancement impossible (${impossible.message}) — " +
                            "relance $tentatives/${GradleProtocol.MAX_RECONNECT_ATTEMPTS}"
                    }
                    api.marquerEnConnexion()
                }
                delay(relance)
                relance = (relance * 2).coerceAtMost(DELAI_RELANC_E_MAX_MS)
            }

            journal.e(TAG) {
                "tentatives épuisées (${GradleProtocol.MAX_RECONNECT_ATTEMPTS}) — orchestrateur indisponible"
            }
            api.marquerEchouee()
        }

        /**
         * Une tentative complète : déploie, écoute, lance, accepte,
         * surveille (santé + sorties) jusqu'à la mort du process.
         *
         * @return `true` pour un échec DÉFINITIF (sans nouvelle tentative).
         *
         * Exemption detekt ciblée (règle 16) : ThrowsCount — chaque `throw`
         * distingue une cause DIFFÉRENTE (délai de connexion, annulation de
         * la surveillance, refus de handshake, échec non typé) avec son
         * traitement propre ; même justification que le Handshake de G2.
         */
        @Suppress("ThrowsCount")
        private suspend fun tentative(java: File): Boolean {
            val jar =
                try {
                    deployeur.deployer()
                } catch (indisponible: IOException) {
                    // Asset absent ou disque plein : re-essayer ne changera
                    // rien avant une réinstallation — définitif.
                    journal.e(TAG) { "JAR orchestrateur indisponible : ${indisponible.message}" }
                    return true
                }

            val secret = GenerateurSecret.nouveau()
            hote.ouvrir()
            val process =
                lanceur.launch(
                    command = fabriqueCommande(java, jar, hote.cheminSocket, secret),
                    extraEnv = emptyMap(),
                    workingDir = null,
                )
            journal.d(TAG) { "orchestrateur lancé (pid ${process.pid}) sur ${hote.cheminSocket}" }

            val porteeTentative = CoroutineScope(SupervisorJob() + dispatchers.default)
            val session: SessionTooling
            try {
                session = hote.accepterUneFois(secret, GradleProtocol.CONNECT_TIMEOUT_MS)
            } catch (delai: TimeoutCancellationException) {
                // L'orchestrateur n'a jamais joint l'écoute dans le délai :
                // tentative consommable (IOException), le process mort seul
                // est nettoyé tout de suite.
                nettoyerApresConnexionManquee(process, hote, porteeTentative)
                throw IOException(
                    "orchestrateur silencieux à la connexion (délai de ${GradleProtocol.CONNECT_TIMEOUT_MS} ms)",
                    delai,
                )
            } catch (refus: EchecHandshakeClient) {
                // Secret ou version refusés : définitif — remonte tel quel à
                // la boucle (qui épuisera sans relancer).
                nettoyerApresConnexionManquee(process, hote, porteeTentative)
                throw refus
            } catch (annulation: CancellationException) {
                // Annulation de la surveillance elle-même (arreter/portée
                // hôte morte) : TOUJOURS relancée telle quelle (règle 6).
                nettoyerApresConnexionManquee(process, hote, porteeTentative)
                throw annulation
            } catch (perdu: Throwable) {
                nettoyerApresConnexionManquee(process, hote, porteeTentative)
                throw IOException("aucune connexion de l'orchestrateur : ${perdu.message}", perdu)
            }

            api.ouvrirSession(session)
            journal.i(TAG) { "orchestrateur connecté (jar ${jar.name})" }

            val surveillanceSante = brancherSurveillance(process, session, porteeTentative)
            try {
                val code = process.awaitExit()
                journal.i(TAG) { "orchestrateur arrêté (code $code)" }
                // Code 2 = arguments invalides : NOTRE ligne de commande est
                // fausse — re-essayer identique est vain. Les autres codes
                // (0 fin de connexion, 3 connexion, 4 refus de l'app) sont
                // des états de l'environnement : la boucle décide.
                return code == CODE_ARGUMENTS_INVALIDES
            } finally {
                surveillanceSante.cancel()
                // Nettoyage SYNCHRONE (leçon T2) : kill/fermer/fermerSession
                // ne suspendent jamais — la coroutine peut être annulée.
                process.kill(force = true)
                hote.fermer()
                api.fermerSession()
                porteeTentative.cancel()
            }
        }

        /**
         * Branche les sorties du process au journal applicatif et démarre
         * la surveillance de santé — retourne le job de santé (annulable).
         *
         * Sorties du process = son journal (règle 14, ADR 0040) : stderr
         * porte les lignes de l'orchestrateur, stdout les rares messages
         * JVM (démarrage impossible…) — les deux rejoignent le journal
         * applicatif sous le tag dédié.
         */
        private fun brancherSurveillance(
            process: ManagedProcess,
            session: SessionTooling,
            portee: CoroutineScope,
        ): Job {
            process
                .stderrLines()
                .onEach { ligne -> journal.w(TAG_PROCESSUS) { ligne } }
                .launchIn(portee)
            process
                .stdoutLines()
                .onEach { ligne -> journal.i(TAG_PROCESSUS) { ligne } }
                .launchIn(portee)
            return portee.launch { sonderSante(process, session) }
        }

        /** Nettoyage synchrone d'une tentative sans connexion établie. */
        private fun nettoyerApresConnexionManquee(
            process: ManagedProcess,
            hote: HoteSocketTooling,
            portee: CoroutineScope,
        ) {
            process.kill(force = true)
            hote.fermer()
            portee.cancel()
        }

        /**
         * Health check ping/pong (§5.4) : un ping toutes les
         * [intervalleSanteMs], l'orchestrateur est déclaré muet si le
         * dernier pong ([GradleApiImpl.dernierPongMs]) vieillit au-delà de
         * [delaiSanteMs] ou si l'envoi échoue — l'arrêt forcé du process
         * déclenche la relance par la boucle (`awaitExit` rend la main).
         */
        private suspend fun sonderSante(
            process: ManagedProcess,
            session: SessionTooling,
        ) {
            while (true) {
                delay(intervalleSanteMs)
                if (System.currentTimeMillis() - api.dernierPongMs.value > delaiSanteMs) {
                    journal.w(TAG) { "orchestrateur muet (aucun pong en $delaiSanteMs ms) — arrêt forcé" }
                    process.kill(force = true)
                    return
                }
                try {
                    session.envoyer(
                        PingMessage(
                            id = UUID.randomUUID().toString(),
                            protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                        ),
                    )
                } catch (perdue: IOException) {
                    journal.w(TAG) { "ping impossible (${perdue.message}) — arrêt forcé" }
                    process.kill(force = true)
                    return
                }
            }
        }

        /**
         * Binaire java du JDK localisé — `null` si le JDK n'est pas installé.
         *
         * La VALIDITÉ du JDK est celle du port [ToolchainLocator]
         * (`isJdkInstalled` vérifie bin/java ET bin/javac dans
         * l'implémentation core:bootstrap, ADR 0032) : le daemon ne la
         * redouble pas — un double contrôle désynchronisé du port serait un
         * bug de plus à maintenir.
         */
        private fun javaExecutable(): File? =
            if (outilchain.isJdkInstalled()) {
                outilchain.javaHome()?.let { racine -> File(racine, "bin/java") }
            } else {
                null
            }

        private companion object {
            /** Tag du journal applicatif pour le daemon lui-même. */
            const val TAG = "ToolingDaemon"

            /** Tag du journal pour les lignes du process orchestrateur. */
            const val TAG_PROCESSUS = "gradle-server"

            /** Arguments invalides du process (ServerMain.CODE_ARGUMENTS). */
            const val CODE_ARGUMENTS_INVALIDES = 2

            /** Attente initiale entre deux relances (1 s). */
            const val DELAI_RELANC_E_MS = 1_000L

            /** Bornage du repli exponentiel des relances (10 s). */
            const val DELAI_RELANC_E_MAX_MS = 10_000L
        }
    }

/**
 * Commande de lancement production : `java` avec tas borné, JAR déployé,
 * chemin du socket et secret de handshake.
 *
 * Le tas est borné (256 Mio) : l'orchestrateur ne FAIT pas les builds (ils
 * vivent dans le daemon Gradle), il orchestre la Tooling API et route le
 * protocole — 256 Mio couvrent largement, et un process invasif sur un
 * téléphone reste inacceptable.
 */
internal fun commandeParDefaut(): (java: File, jar: File, cheminSocket: File, secret: String) -> List<String> =
    { java: File, jar: File, cheminSocket: File, secret: String ->
        listOf(
            java.absolutePath,
            "-Xmx$TAS_MO",
            "-jar",
            jar.absolutePath,
            "--socket",
            cheminSocket.absolutePath,
            "--secret",
            secret,
            "--log-level",
            "INFO",
        )
    }

/** Tas maximum du process orchestrateur (Mio). */
internal const val TAS_MO = 256

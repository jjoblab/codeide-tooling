package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.GradleProtocol
import kotlinx.coroutines.runBlocking
import java.nio.file.Path
import kotlin.system.exitProcess

/**
 * Point d'entrée de l'orchestrateur (§4.1) : analyse les arguments,
 * connecte au socket de l'app (écoute ouverte AVANT le lancement — aucun
 * fichier de découverte, aucun polling), négocie le handshake, puis boucle
 * de réception sur ce fil jusqu'à la fin de connexion.
 *
 * [executer] retourne un code de sortie et N'appelle jamais `exitProcess` :
 * les tests d'intégration (§7.2) l'invoquent DANS la JVM de test — c'est
 * [main] seul qui convertit un code non nul en arrêt du process.
 *
 * Codes de sortie : 0 fin de connexion normale · 2 arguments invalides ·
 * 3 connexion impossible · 4 handshake refusé ou version incompatible.
 *
 * Exemption detekt ciblée (règle 16) : ReturnCount — chaque `return` porte
 * un CODE DE SORTIE distinct (contrat opérateur documenté ci-dessus), les
 * factoriser en une variable temporaire nuirait à la lisibilité du flot
 * d'erreurs.
 */
@Suppress("ReturnCount")
public object ServerMain {
    /**
     * Exécute l'orchestrateur et retourne le code de sortie (sans arrêter
     * la JVM — voir [main]).
     */
    public suspend fun executer(args: Array<String>): Int {
        val config =
            try {
                ServerConfig.analyser(args)
            } catch (invalide: IllegalArgumentException) {
                System.err.println("[gradle-server] [ERROR] arguments invalides : ${invalide.message}")
                System.err.println(ServerConfig.USAGE)
                return CODE_ARGUMENTS
            }
        Journal.niveau = config.niveauJournal
        positionnerInstrumentation(config)

        val socket =
            try {
                SocketClient.connecter(Path.of(config.cheminSocket), GradleProtocol.CONNECT_TIMEOUT_MS)
            } catch (impossible: Exception) {
                Journal.error("connexion au socket impossible : ${impossible.message}")
                return CODE_CONNEXION
            }

        val pool = GradleConnectorPool()
        val bus = EventBusSocket(socket)
        val metronome = genererMetronome(config)
        val dispatcher =
            MessageDispatcher(
                socket,
                pool,
                bus,
                config.intervalleTasMs,
                genererScriptAppLog(config)?.absolutePath,
                metronome,
            )

        Runtime.getRuntime().addShutdownHook(
            Thread {
                dispatcher.arreter()
                bus.arreter()
                pool.close()
                socket.fermer()
            },
        )

        try {
            Handshake.negocier(socket, config.secret)
            // v0.49.0 (ADR 0080) : à partir d'ici, TOUTE frame passe par le
            // fil écrivain unique (file FIFO + priorité pong) — le handshake
            // seul écrit en direct, avant ce démarrage.
            socket.demarrerEcrivain()
            bus.demarrer()
            // Phase 1 (T2b/T3b) : les fils mesurés existent désormais —
            // l'instrumentation connaît leurs identifiants (CPU, schedstat).
            InstrumentationTransport.brancherFils(socket.idFilEcrivain(), bus.idFilConsommateur())
            dispatcher.demarrerSurveillance()
            // Phase 1 (§3.1b) : mode BANC — émission de la séquence AVANT la
            // boucle de réception (qui reprend ensuite : le pong de santé
            // répond, l'app reste connectée et sa console affiche le cycle).
            config.banc?.let { banc -> BancSocketSeul.executer(bus, banc) }
            dispatcher.boucle()
            return 0
        } catch (echec: EchecHandshake) {
            return echec.codeSortie
        } catch (t: Throwable) {
            Journal.error("arrêt sur erreur : ${t.message}", t)
            return CODE_CONNEXION
        } finally {
            dispatcher.arreter()
            bus.arreter()
            pool.close()
            socket.fermer()
        }
    }

    /**
     * Phase 1 (règle 10) : le drapeau d'instrumentation se positionne AVANT
     * la construction du [SocketClient] (son flux compteur d'appels système,
     * T2b, s'enveloppe à la création) et avant tout handler.
     */
    private fun positionnerInstrumentation(config: ServerConfig) {
        InstrumentationTransport.active = config.instrumentationTransport
        Journal.info("orchestrateur ${ServerVersion.CURRENT} — socket ${config.cheminSocket}")
        if (config.instrumentationTransport) {
            Journal.info("instru: instrumentation transport ACTIVEE (--instru-transport)")
        }
    }

    /**
     * Script d'init de l'injection applog (mission « Exécuter » R2,
     * ADR 0103) : généré une fois par vie du serveur, à côté du socket
     * (répertoire privé de l'app) — `null` si le réglage utilisateur
     * coupe l'injection (aucun `--applog-repo` passé au serveur).
     */
    private fun genererScriptAppLog(config: ServerConfig): java.io.File? =
        config.depotAppLog?.let { depot ->
            val cible =
                java.io.File(config.cheminSocket).parentFile?.let { parent ->
                    java.io.File(parent, jo.codeide.tooling.protocol.ApplogCoordonnees.NOM_SCRIPT_INIT)
                }
            cible?.also {
                GenerateurScriptAppLog.ecrire(
                    it,
                    java.io.File(depot),
                    jo.codeide.tooling.protocol.ApplogCoordonnees.COORDONNEE,
                )
            }
        }

    /**
     * Métronome de diagnostic (Phase 1, §3.1c) : généré une fois par vie du
     * serveur QUAND l'instrumentation est active — script d'init passé aux
     * builds par `-I` (comme l'applog) et fichier d'arrêt que le fil du
     * daemon surveille. `null` sinon : aucun fichier écrit, aucun argument.
     */
    private fun genererMetronome(config: ServerConfig): MetronomeBuild? {
        if (!config.instrumentationTransport) return null
        val parent = java.io.File(config.cheminSocket).parentFile ?: return null
        val script = java.io.File(parent, CoordonneesMetronome.NOM_SCRIPT_INIT)
        val arret = java.io.File(parent, CoordonneesMetronome.NOM_FICHIER_ARRET)
        GenerateurScriptMetronome.ecrire(script, arret)
        // Arrêt posé d'emblée : le métronome ne tourne qu'ENTRE le début et
        // la conclusion d'un build (BuildHandler le relance/supprime).
        runCatching {
            arret.parentFile?.mkdirs()
            arret.writeText("arret\n")
        }
        return MetronomeBuild(scriptInit = script.absolutePath, fichierArret = arret)
    }

    /** Point d'entrée process : convertit le code de [executer] en arrêt. */
    @JvmStatic
    public fun main(args: Array<String>) {
        val code = runBlocking { executer(args) }
        if (code != 0) exitProcess(code)
    }

    /** Arguments invalides (usage imprimé sur la sortie d'erreur). */
    public const val CODE_ARGUMENTS: Int = 2

    /** Socket introuvable ou connexion perdue brutalement. */
    public const val CODE_CONNEXION: Int = 3
}

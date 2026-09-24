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
        Journal.info("orchestrateur ${ServerVersion.CURRENT} — socket ${config.cheminSocket}")

        val socket =
            try {
                SocketClient.connecter(Path.of(config.cheminSocket), GradleProtocol.CONNECT_TIMEOUT_MS)
            } catch (impossible: Exception) {
                Journal.error("connexion au socket impossible : ${impossible.message}")
                return CODE_CONNEXION
            }

        val pool = GradleConnectorPool()
        val bus = EventBusSocket(socket)
        val dispatcher = MessageDispatcher(socket, pool, bus, config.intervalleTasMs)

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
            bus.demarrer()
            dispatcher.demarrerSurveillance()
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

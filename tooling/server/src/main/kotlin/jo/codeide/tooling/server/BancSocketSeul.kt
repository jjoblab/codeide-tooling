package jo.codeide.tooling.server

import jo.codeide.tooling.protocol.BuildFinished
import jo.codeide.tooling.protocol.BuildOutput
import jo.codeide.tooling.protocol.BuildStarted
import jo.codeide.tooling.protocol.GradleProtocol
import jo.codeide.tooling.protocol.StreamKind
import java.util.Locale

/**
 * Banc « socket seul » (Phase 1, §3.1b) : émet une séquence complète
 * `BuildStarted` → N [BuildOutput] → `BuildFinished` à travers la VRAIE pile
 * serveur ([EventBus] → [SocketClient] → FrameCodec) SANS Gradle, à cadence
 * réglable — il isole le coût par frame du transport de tout ce qui touche
 * Gradle (daemon, Tooling API, parseurs).
 *
 * Il vit dans :tooling:server (et non :tooling:testing) parce qu'il a
 * besoin des internes de la pile ([EventBusSocket], [SocketClient]) — le
 * module testing ne peut les voir ; c'est la même discipline que
 * `ServeurIntegrationTest`, dont il partage le harnais JVM pour son test.
 *
 * Le cycle complet est émis POUR DE VRAI : le lecteur Android (ou le miroir
 * JVM du test) voit un build ordinaire — la console de l'app affiche les
 * lignes, la sonde `ConsoleLatence` du client mesure la latence de transport
 * de CHAQUE frame, le résumé arrive au `BuildFinished`. C'est le banc des
 * expériences X1 à X5 de la matrice §3.3.
 *
 * Après émission, le serveur reprend sa boucle normale (pong de santé) :
 * l'app reste connectée, le propriétaire lit console et journal, puis
 * désarme le mode banc (redémarrage du serveur de build côté app).
 */
internal object BancSocketSeul {
    /** Identifiant de build du banc — lisible dans les journaux des deux côtés. */
    private const val BUILD_ID = "banc-socket-seul"

    /** Préfixe des tâches annoncées par le BuildStarted du banc. */
    private const val TACHES_BANC = "banc"

    /**
     * Émet la séquence du banc sur [bus] selon [parametres].
     *
     * Le cadencement utilise `Thread.sleep` (et non une coroutine) : le banc
     * DOIT émettre depuis un contexte qui ne partage aucun ordonnanceur avec
     * la pile mesurée — le fil appelant est celui de la requête (portée
     * bornée du dispatcher), jamais le consommateur ni l'écrivain.
     */
    fun executer(
        bus: EventBus,
        parametres: ServerConfig.ParametresBanc,
    ) {
        val depart = System.currentTimeMillis()
        InstrumentationTransport.demarrerBuild(BUILD_ID)
        Journal.info(
            "banc: demarrage — ${parametres.lignes} lignes, cadence ${parametres.cadenceMs} ms, " +
                "longueur ${parametres.longueurLigne}, contenu ${parametres.contenu.name.lowercase(Locale.ROOT)}",
        )
        bus.publier(
            BuildStarted(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                buildId = BUILD_ID,
                tasks = listOf(TACHES_BANC),
            ),
        )
        for (indice in 0 until parametres.lignes) {
            bus.publier(ligne(indice, parametres))
            if (parametres.cadenceMs > 0 && indice < parametres.lignes - 1) {
                try {
                    Thread.sleep(parametres.cadenceMs)
                } catch (interruption: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        val dureeMs = System.currentTimeMillis() - depart
        bus.publier(
            BuildFinished(
                id = nouvelId(),
                protocolVersion = GradleProtocol.PROTOCOL_VERSION,
                buildId = BUILD_ID,
                succeeded = true,
                durationMs = dureeMs,
            ),
        )
        InstrumentationTransport.conclureBuild(BUILD_ID)?.let { Journal.info(it) }
        Journal.info("banc: conclusion — ${parametres.lignes} lignes emises en $dureeMs ms")
    }

    /** Une ligne du banc : alternance stdout/stderr, contenu selon [parametres]. */
    private fun ligne(
        indice: Int,
        parametres: ServerConfig.ParametresBanc,
    ): BuildOutput {
        val flux = if (indice % 2 == 0) StreamKind.STDOUT else StreamKind.STDERR
        return BuildOutput(
            id = nouvelId(),
            protocolVersion = GradleProtocol.PROTOCOL_VERSION,
            buildId = BUILD_ID,
            stream = flux,
            line = contenu(indice, parametres),
            timestampMs = System.currentTimeMillis(),
        )
    }

    /**
     * Contenu de la ligne [indice] — les quatre natures du banc déluge
     * (§3.1a) : simple, ANSI, UTF-8 multi-octets, trace d'exception
     * multi-lignes (le banc émet les lignes d'une trace UNE PAR UNE, comme
     * Gradle les écrit).
     *
     * Exemption detekt ciblée (règle 16) : MagicNumber — les modulos et
     * divisions dérivent la répartition du banc (alternance de flux, cycle
     * d'une trace sur quatre lignes) ; les nommer masquerait l'arithmétique
     * DU banc lui-même.
     */
    @Suppress("MagicNumber")
    private fun contenu(
        indice: Int,
        parametres: ServerConfig.ParametresBanc,
    ): String {
        val numero = "%06d".format(indice)
        return when (parametres.contenu) {
            ServerConfig.ContenuLigneBanc.PLAIN -> {
                remplir("banc $numero ", 'x', parametres.longueurLigne)
            }

            ServerConfig.ContenuLigneBanc.ANSI -> {
                "\u001B[31mbanc\u001B[0m $numero " + remplir("", 'a', (parametres.longueurLigne / 2).coerceAtLeast(1))
            }

            ServerConfig.ContenuLigneBanc.UTF8 -> {
                "banc $numero café赵漢字" +
                    remplir("", 'é', parametres.longueurLigne / 4)
            }

            ServerConfig.ContenuLigneBanc.MULTI -> {
                when (indice % 4) {
                    0 -> "java.lang.IllegalStateException: banc $numero"
                    1 -> "\tat jo.codeide.banc.Essai.ligne$numero(Essai.kt:${indice + 1})"
                    2 -> "\tat jo.codeide.banc.Autre.appel$numero(Autre.kt:${indice + 1})"
                    else -> "Caused by: java.lang.RuntimeException: cause du banc $numero"
                }
            }
        }
    }

    /** Complète [depart] avec [remplissage] jusqu'à [longueur] caractères. */
    private fun remplir(
        depart: String,
        remplissage: Char,
        longueur: Int,
    ): String {
        if (longueur <= depart.length) return depart
        return depart + remplissage.toString().repeat(longueur - depart.length)
    }
}

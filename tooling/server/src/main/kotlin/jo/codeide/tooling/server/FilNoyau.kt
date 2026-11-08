package jo.codeide.tooling.server

import java.nio.file.Files
import java.nio.file.Path

/**
 * TID NOYAU du fil courant (T3b) — ce que `/proc/self/task/<tid>/…`
 * désigne, à ne pas confondre avec l'identifiant séquentiel JVM de
 * `Thread.getId()` : `ThreadMXBean` accepte le second, schedstat exige le
 * premier.
 *
 * La Phase 1 passait l'identifiant JVM à [Schedstat] : le fichier
 * `/proc/self/task/<id-jvm>/schedstat` n'existe JAMAIS (vérifié sur JDK 21 :
 * ids JVM 1, 19… contre tids noyau 10529…) — la sonde T3b était muette
 * partout (bureau, CI, appareil) sans que les tests ne le voient : leur
 * branche « carte vide » passait pour un noyau sans `CONFIG_SCHEDSTATS`.
 *
 * `/proc/thread-self` (noyau ≥ 3.17) est un lien magique vers
 * `/proc/<pid>/task/<tid>` du fil QUI LE LIT : chaque fil mesuré
 * s'auto-déclare à son démarrage. Le `comm` noyau étant tronqué à 15
 * caractères, les deux fils du transport (« gradle-server-e… ») y sont
 * indiscernables — l'auto-déclaration est le seul raccourci fiable, sans
 * JNI ni parcours de `/proc/self/task`.
 *
 * Indisponible (non-Linux, restrictions) : `-1` — la sonde le dit
 * honnêtement (« T3b schedstat : indisponible »).
 */
internal object FilNoyau {
    /** TID noyau du fil courant, -1 si indisponible. */
    fun tidCourant(): Long =
        runCatching {
            Files
                .readSymbolicLink(CHEMIN_THREAD_SELF)
                .fileName
                .toString()
                .toLongOrNull()
        }.getOrNull() ?: -1L

    /** Lien magique du fil courant (noyau ≥ 3.17). */
    private val CHEMIN_THREAD_SELF: Path = Path.of("/proc/thread-self")
}

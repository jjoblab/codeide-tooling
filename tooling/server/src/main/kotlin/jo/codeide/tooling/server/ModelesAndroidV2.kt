package jo.codeide.tooling.server

import com.android.builder.model.v2.ide.AndroidArtifact
import com.android.builder.model.v2.ide.ArtifactDependencies
import com.android.builder.model.v2.ide.GraphItem
import com.android.builder.model.v2.ide.Library
import com.android.builder.model.v2.ide.LibraryType
import com.android.builder.model.v2.models.AndroidProject
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.ModelBuilderParameter
import com.android.builder.model.v2.models.VariantDependencies
import org.gradle.api.Action
import org.gradle.tooling.BuildController
import org.gradle.tooling.model.gradle.BasicGradleProject

/**
 * Collecte des modèles **v2 d'AGP** pour chaque projet du build (mission
 * « LSP Java » étape 1, ADR 0113) : la source du classpath des modules
 * ANDROID. IdeaProject reste VIDE pour eux (AGP ne publie pas ses
 * configurations dans le modèle idea — constat §2.1 de l'étude, mesuré
 * sur le dépôt de référence : 26 modules Android à ZÉRO racine source et
 * ZÉRO dépendance côté IdeaProject).
 *
 * Modèles résolus PAR PROJET via la Tooling API (validés empiriquement
 * par la sonde `SondeModeleAndroid` du banc, AGP 9.4.1 / Gradle 9.7.1) :
 *
 * - [BasicAndroidProject] (non paramétré) : `bootClasspath` (`android.jar`
 *   du compileSdk), sourcesets main/unitTest (racines java ET kotlin —
 *   même liste côté AGP), buildFolder, variantes de base ;
 * - [AndroidProject] (non paramétré) : par variante, `generatedSourceFolders`
 *   (R, BuildConfig, KSP/kapt, ViewBinding) et `classesFolders` (sorties
 *   javac/kotlin + R.jar du module) ;
 * - [VariantDependencies] (PARAMÉTRÉ par [ModelBuilderParameter]) : le
 *   graphe du classpath de compilation (principal et test) + la carte des
 *   bibliothèques. Tous les drapeaux `dontBuild*` sont posés : seules les
 *   dépendances de COMPILATION sont résolues (le runtime ne regarde pas
 *   un LSP), et le paramètre doit être COMPLET — un getter booléen non
 *   initialisé du proxy Tooling API renvoie null et fait échouer AGP en
 *   NPE au déballage (constat de la sonde).
 *
 * NORMALISATION des AAR : AGP livre déjà les jars extraits —
 * `androidLibraryData.compileJarFiles` pointe dans le cache de transforms
 * du GRADLE USER HOME (jars/classes.jar d'un AAR explosé), mesuré sur le
 * jeu « lourd » : 191 AAR, tous avec leur classes.jar extrait. Le serveur
 * n'extrait RIEN : il livre le jar extrait en entrée [ClasspathKind.JAR]
 * et porte l'AAR d'origine en provenance (`aar`). Les AAR bruts du modèle
 * idea (périphériques : JVM pur dépendant d'un AAR local) restent livrés
 * en [ClasspathKind.AAR] — l'ancien contrat ADR 0058.
 *
 * Les entrées RELOCATED et NO_ARTIFACT_FILE sont écartées : la première
 * désigne une dépendance DÉPLACÉE (le vrai artefact figure ailleurs dans
 * le graphe résolu), la seconde une plateforme/BOM sans fichier.
 *
 * Toute méthode AGP est protégée : un projet sans AGP, ou avec un AGP trop
 * ancien pour les modèles v2, renvoie null/leverait — le module retombe
 * sur sa conversion IdeaProject (le repli est total, jamais fatal).
 */
internal object ModelesAndroidV2 {
    /**
     * Collecte les modules Android du build passé par [controleur] —
     * exécuté DANS le démon Gradle (côté action Tooling API).
     */
    fun collecter(controleur: BuildController): List<ModuleClasspathDto> {
        val modules = mutableListOf<ModuleClasspathDto>()
        for (projet in controleur.getBuildModel().projects.all) {
            val basic = trouverModele(projet) { controleur.findModel(it, BasicAndroidProject::class.java) }
            if (basic == null) {
                continue // pas un module Android (ou AGP sans modèles v2)
            }
            val variante = choisirVariante(basic.variants.map { it.name })
            val android = trouverModele(projet) { controleur.findModel(it, AndroidProject::class.java) }
            val deps = if (variante == null) null else trouverDependencies(projet, variante, controleur)
            modules +=
                convertir(
                    chemin = projet.path,
                    basic = basic,
                    android = android,
                    deps = deps,
                    variante = variante,
                )
        }
        return modules
    }

    /**
     * Variante retenue pour le classpath : `debug` si elle existe (la
     * variante de développement par défaut, même choix qu'Android Studio
     * à la première sync), sinon la première contenant `debug`, sinon la
     * première. `null` si le module n'a aucune variante.
     */
    internal fun choisirVariante(variantes: List<String>): String? =
        when {
            variantes.isEmpty() -> null
            "debug" in variantes -> "debug"
            else -> variantes.firstOrNull { it.lowercase().contains("debug") } ?: variantes.first()
        }

    /**
     * Conversion PURE d'un module Android vers son DTO — testable avec des
     * faux implémentant les interfaces v2 (aucun démon requis).
     *
     * @param chemin chemin Gradle (`:app`).
     * @param basic modèle de base (bootClasspath, sourcesets, variantes).
     * @param android modèle riche (générés/sorties par variante), `null`
     *        si indisponible.
     * @param deps dépendances de la variante retenue, `null` si
     *        indisponible.
     * @param variante variante retenue, `null` si aucune.
     */
    internal fun convertir(
        chemin: String,
        basic: BasicAndroidProject,
        android: AndroidProject?,
        deps: VariantDependencies?,
        variante: String?,
    ): ModuleClasspathDto {
        val racines = mutableListOf<String>()
        val racinesTest = mutableListOf<String>()
        basic.mainSourceSet?.sourceProvider?.let { fournisseur ->
            racines += fournisseur.javaDirectories.map { it.absolutePath }
            racines += fournisseur.kotlinDirectories.map { it.absolutePath }
        }
        // Sonde empirique (AGP 9.4.1, projet Compose réel) : les cartes
        // hostTestSourceProviders/hostTestArtifacts sont VIDES — le test
        // unitaire classique vit encore dans unitTestSourceProvider /
        // unitTestArtifact, dépréciés mais peuplés. On lit les dépréciés
        // (revisités à l'étape 2 si AGP les retire).
        @Suppress("DEPRECATION")
        basic.mainSourceSet?.unitTestSourceProvider?.let { fournisseur ->
            racinesTest += fournisseur.javaDirectories.map { it.absolutePath }
            racinesTest += fournisseur.kotlinDirectories.map { it.absolutePath }
        }

        // Généres et sorties de la VARIANTE retenue (R.jar, classes javac
        // et kotlin, ap_generated_sources) — l'existence n'est pas exigée
        // (avant le premier build, elles n'existent pas : le LSP dégrade
        // proprement, la prééminence des sources est la règle ADR 0111).
        val fichiersGeneres = mutableListOf<String>()
        val sortiesModule = mutableListOf<EntreeClasspathDto>()
        val varianteAndroid = android?.variants?.firstOrNull { it.name == variante }
        varianteAndroid?.mainArtifact?.let { principal: AndroidArtifact ->
            fichiersGeneres += principal.generatedSourceFolders.map { it.absolutePath }
            principal.classesFolders.forEach { sortie ->
                sortiesModule +=
                    EntreeClasspathDto(
                        path = sortie.absolutePath,
                        kind = natureSortie(sortie),
                        scope = null,
                        sources = null,
                    )
            }
        }
        @Suppress("DEPRECATION")
        varianteAndroid?.unitTestArtifact?.generatedSourceFolders?.forEach { generes ->
            fichiersGeneres += generes.absolutePath
        }

        val avertissements = mutableListOf<String>()
        val entrees = mutableListOf<EntreeClasspathDto>()
        entrees += sortiesModule
        if (deps != null) {
            entrees += entrerGraph(deps.mainArtifact, deps.libraries, "compile", avertissements)
            @Suppress("DEPRECATION")
            deps.unitTestArtifact?.let { test ->
                entrees += entrerGraph(test, deps.libraries, "test", avertissements)
            }
        }

        return ModuleClasspathDto(
            name = chemin,
            sourceDirs = racines.distinct(),
            sourceDirsTest = racinesTest.distinct(),
            entries = entrees,
            varianteAndroid = variante,
            fichiersGeneres = fichiersGeneres.distinct(),
            androidJar = basic.bootClasspath.firstOrNull()?.absolutePath,
            avertissements = avertissements,
        )
    }

    /**
     * Traduit le graphe des dépendances de COMPILATION d'un artefact en
     * entrées de classpath : parcours en profondeur des [GraphItem]
     * (clés dédupliquées, ordre de résolution préservé), chaque clé est
     * résolue dans la carte des bibliothèques.
     */
    private fun entrerGraph(
        artefact: ArtifactDependencies?,
        bibliotheques: Map<String, Library>,
        portee: String,
        avertissements: MutableList<String>,
    ): List<EntreeClasspathDto> {
        if (artefact == null) {
            return emptyList()
        }
        artefact.unresolvedDependencies.forEach { nonResolue ->
            avertissements += "dépendance non résolue : ${nonResolue.name} — ${nonResolue.cause}"
        }
        val cles = LinkedHashSet<String>()

        fun parcourir(item: GraphItem) {
            if (cles.add(item.key)) {
                item.dependencies.forEach { enfant -> parcourir(enfant) }
            }
        }
        artefact.compileDependencies.forEach { parcourir(it) }

        return cles
            .mapNotNull { cle -> bibliotheques[cle] }
            .flatMap { lib -> entrerBibliotheque(lib, portee) }
    }

    /**
     * Une bibliothèque du graphe v2 en entrées de classpath — selon son
     * [LibraryType] : ANDROID_LIBRARY → NORMALISÉE ([entrerAar]) ;
     * JAVA_LIBRARY → le jar du cache avec son jar de sources attaché ;
     * PROJECT → entrée MODULE par le CHEMIN du module frère (les sorties
     * compilées du frère vivent dans SON snapshot, pas ici) ; RELOCATED →
     * la dépendance a déménagé, le VRAI artefact figure ailleurs dans le
     * graphe résolu — rien à livrer ; NO_ARTIFACT_FILE → plateforme/BOM
     * sans fichier — rien à livrer. Aucun de ces écarts n'est un échec :
     * le classpath résolu est complet.
     */
    private fun entrerBibliotheque(
        lib: Library,
        portee: String,
    ): List<EntreeClasspathDto> =
        when (lib.type) {
            LibraryType.ANDROID_LIBRARY -> {
                entrerAar(lib, portee)
            }

            LibraryType.JAVA_LIBRARY -> {
                val fichier = lib.artifact ?: return emptyList()
                listOf(
                    EntreeClasspathDto(
                        path = fichier.absolutePath,
                        kind = "JAR",
                        scope = portee,
                        sources = sourcesAttachees(lib),
                    ),
                )
            }

            LibraryType.PROJECT -> {
                val cible = lib.projectInfo?.projectPath ?: return emptyList()
                listOf(
                    EntreeClasspathDto(
                        path = cible,
                        kind = "MODULE",
                        scope = portee,
                        sources = null,
                    ),
                )
            }

            LibraryType.RELOCATED,
            LibraryType.NO_ARTIFACT_FILE,
            -> {
                emptyList()
            }
        }

    /**
     * Une bibliothèque AAR : livrée NORMALISÉE — chaque jar extrait du
     * cache de transforms (classes.jar et jars du dossier `libs`) devient
     * une entrée JAR, l'AAR d'origine voyage en provenance. Repli (AAR
     * sans jars extraits, ne devrait plus arriver avec AGP 8+) : l'AAR
     * brut en entrée AAR — l'ancien contrat ADR 0058. Retours anticipés :
     * chaque repli a sa propre garde.
     */
    @Suppress("ReturnCount")
    private fun entrerAar(
        lib: Library,
        portee: String,
    ): List<EntreeClasspathDto> {
        val jars = lib.androidLibraryData?.compileJarFiles.orEmpty()
        if (jars.isEmpty()) {
            val aar = lib.artifact ?: return emptyList()
            return listOf(
                EntreeClasspathDto(
                    path = aar.absolutePath,
                    kind = "AAR",
                    scope = portee,
                    sources = sourcesAttachees(lib),
                ),
            )
        }
        val aar = lib.artifact?.absolutePath
        return jars.map { jar ->
            EntreeClasspathDto(
                path = jar.absolutePath,
                kind = "JAR",
                scope = portee,
                sources = sourcesAttachees(lib),
                aar = aar,
            )
        }
    }

    /** Jar de sources attaché à une bibliothèque, `null` si aucun
     *  ([Library.getSrcJars] — le singulier est déprécié). */
    private fun sourcesAttachees(lib: Library): String? = lib.srcJars.firstOrNull()?.absolutePath

    /** Nature d'une sortie de module par son nom (jar ou dossier). */
    private fun natureSortie(sortie: java.io.File): String = if (sortie.name.endsWith(".jar")) "JAR" else "DOSSIER"

    /**
     * Résolution protégée d'un modèle non paramétré : AGP absent/ancien →
     * null (repli IdeaProject), jamais d'échec du classpath.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun <T> trouverModele(
        projet: BasicGradleProject,
        resoudre: (BasicGradleProject) -> T,
    ): T? =
        try {
            resoudre(projet)
        } catch (e: Exception) {
            null
        }

    /**
     * Résolution de [VariantDependencies] pour [variante] — le paramètre
     * est TOUJOURS complet (variante + tous les drapeaux `dontBuild*` +
     * artefacts additionnels fermés) : un getter booléen non initialisé
     * du proxy Tooling API renvoie null et AGP échoue en NPE (sonde).
     * L'échec (AGP trop ancien, projet sans graphe) est AVALE et retourne
     * null : le module retombe sur sa conversion IdeaProject — jamais
     * d'échec du classpath (règle 14 : le serveur n'a pas d'AppLogger).
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun trouverDependencies(
        projet: BasicGradleProject,
        variante: String,
        controleur: BuildController,
    ): VariantDependencies? =
        try {
            controleur.findModel(
                projet,
                VariantDependencies::class.java,
                ModelBuilderParameter::class.java,
                Action<ModelBuilderParameter> { parametre ->
                    parametre.variantName = variante
                    parametre.dontBuildRuntimeClasspath = true
                    parametre.dontBuildUnitTestRuntimeClasspath = true
                    parametre.dontBuildAndroidTestRuntimeClasspath = true
                    parametre.dontBuildScreenshotTestRuntimeClasspath = true
                    parametre.dontBuildTestFixtureRuntimeClasspath = true
                    parametre.dontBuildHostTestRuntimeClasspath = emptyMap()
                    parametre.additionalArtifactsInModel = false
                },
            )
        } catch (e: Exception) {
            null
        }
}

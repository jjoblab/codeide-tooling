package jo.codeide.tooling.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Conversion du modèle v2 AGP vers le DTO de classpath (ADR 0113) —
 * testée avec des FAUX implémentant les interfaces v2 : le convertisseur
 * est pur par conception, le démon Gradle n'intervient pas (les chemins
 * du collecteur sont couverts par le test d'intégration AGP réel).
 */
class ModelesAndroidV2Test {
    private val convertisseur = ModelesAndroidV2

    // ------------------------------------------------------------------
    // Choix de la variante.
    // ------------------------------------------------------------------

    @Test
    fun `choisirVariante - debug d abord, sinon la premiere contenant debug, sinon la premiere`() {
        assertEquals("debug", convertisseur.choisirVariante(listOf("release", "debug")))
        assertEquals("paidDebug", convertisseur.choisirVariante(listOf("release", "paidDebug")))
        assertEquals("release", convertisseur.choisirVariante(listOf("release")))
        assertNull(convertisseur.choisirVariante(emptyList()))
    }

    // ------------------------------------------------------------------
    // Sources, plateforme, générés, sorties.
    // ------------------------------------------------------------------

    @Test
    fun `convertir - sources main et test par role, androidJar, generes et sorties`() {
        val dto =
            convertisseur.convertir(
                chemin = ":app",
                basic = FauxBasicAndroidProject(),
                android = FauxAndroidProject(),
                deps = null,
                variante = "debug",
            )

        assertEquals(":app", dto.name)
        assertEquals(listOf("/p/app/src/main/java", "/p/app/src/main/kotlin"), dto.sourceDirs)
        assertEquals(listOf("/p/app/src/test/java", "/p/app/src/test/kotlin"), dto.sourceDirsTest)
        assertEquals("/sdk/platforms/android-37/android.jar", dto.androidJar)
        assertEquals("debug", dto.varianteAndroid)
        assertEquals(
            listOf("/p/app/build/generated/ap_generated_sources/debug/out"),
            dto.fichiersGeneres,
        )
        // Sorties du module (classes + R.jar) : JAR par extension, DOSSIER sinon.
        assertTrue(dto.entries.any { it.path.endsWith("R.jar") && it.kind == "JAR" })
        assertTrue(dto.entries.any { it.path.endsWith("javac/debug/classes") && it.kind == "DOSSIER" })
    }

    @Test
    fun `convertir - sans modeles secondaires ni dependances, le module reste livrable`() {
        val dto =
            convertisseur.convertir(
                chemin = ":lib",
                basic = FauxBasicAndroidProject(path = ":lib"),
                android = null,
                deps = null,
                variante = null,
            )

        assertEquals(":lib", dto.name)
        assertNull(dto.varianteAndroid)
        assertTrue(dto.entries.isEmpty())
        assertTrue(dto.avertissements.isEmpty())
    }

    // ------------------------------------------------------------------
    // Graphe de dépendances : bibliothèques, frères, AAR normalisés.
    // ------------------------------------------------------------------

    @Test
    fun `convertir - AAR normalise en jars extraits avec l aar d origine en provenance`() {
        val jarExtrait = File("/gradle/caches/9.7.1/transforms/h1/transformed/material-1.14.0/jars/classes.jar")
        val aar = File("/gradle/caches/modules-2/files-2.1/material/1.14.0/a1/material-1.14.0.aar")
        val deps =
            FauxVariantDependencies(
                mainArtifact =
                    FauxArtifactDependencies(
                        compileDependencies = listOf(FauxGraphItem("cle-android-library")),
                    ),
                libraries =
                    mapOf(
                        "cle-android-library" to
                            FauxLibrary(
                                type = com.android.builder.model.v2.ide.LibraryType.ANDROID_LIBRARY,
                                artifact = aar,
                                androidLibraryData = FauxAndroidLibraryData(compileJarFiles = listOf(jarExtrait)),
                            ),
                    ),
            )

        val dto = convertisseur.convertir(":app", FauxBasicAndroidProject(), FauxAndroidProject(), deps, "debug")

        val entree = dto.entries.first { it.kind == "JAR" && it.path.contains("material") }
        assertEquals(jarExtrait.absolutePath, entree.path)
        assertEquals(aar.absolutePath, entree.aar)
        assertEquals("compile", entree.scope)
    }

    @Test
    fun `convertir - AAR sans jars extraits, repli brut de l ancien contrat ADR 0058`() {
        val aar = File("/cache/brute.aar")
        val deps =
            FauxVariantDependencies(
                mainArtifact =
                    FauxArtifactDependencies(
                        compileDependencies = listOf(FauxGraphItem("cle-aar-brut")),
                    ),
                libraries =
                    mapOf(
                        "cle-aar-brut" to
                            FauxLibrary(
                                type = com.android.builder.model.v2.ide.LibraryType.ANDROID_LIBRARY,
                                artifact = aar,
                            ),
                    ),
            )

        val dto = convertisseur.convertir(":app", FauxBasicAndroidProject(), FauxAndroidProject(), deps, "debug")

        val entree = dto.entries.first { it.kind == "AAR" }

        assertEquals(aar.absolutePath, entree.path)
    }

    @Test
    fun `convertir - bibliotheque java avec jar de sources attache et dependance frere par chemin`() {
        val jar = File("/gradle/caches/modules-2/files-2.1/g/commons-io/2.20.0/h1/commons-io-2.20.0.jar")
        val sources = File("/gradle/caches/modules-2/files-2.1/g/commons-io/2.20.0/h1/commons-io-2.20.0-sources.jar")
        val deps =
            FauxVariantDependencies(
                mainArtifact =
                    FauxArtifactDependencies(
                        compileDependencies =
                            listOf(
                                FauxGraphItem("cle-java"),
                                FauxGraphItem("cle-projet"),
                            ),
                    ),
                libraries =
                    mapOf(
                        "cle-java" to
                            FauxLibrary(
                                type = com.android.builder.model.v2.ide.LibraryType.JAVA_LIBRARY,
                                artifact = jar,
                                srcJars = listOf(sources),
                            ),
                        "cle-projet" to
                            FauxLibrary(
                                type = com.android.builder.model.v2.ide.LibraryType.PROJECT,
                                projectInfo = FauxProjectInfo(projectPath = ":lib"),
                            ),
                    ),
            )

        val dto = convertisseur.convertir(":app", FauxBasicAndroidProject(), FauxAndroidProject(), deps, "debug")

        val jarEntree = dto.entries.first { it.path == jar.absolutePath }
        assertEquals(sources.absolutePath, jarEntree.sources)
        val module = dto.entries.first { it.kind == "MODULE" }
        assertEquals(":lib", module.path)
    }

    @Test
    fun `convertir - dependances relogees et sans fichier sont ecartees sans echec`() {
        val deps =
            FauxVariantDependencies(
                mainArtifact =
                    FauxArtifactDependencies(
                        compileDependencies =
                            listOf(
                                FauxGraphItem("cle-relogee"),
                                FauxGraphItem("cle-bom"),
                            ),
                    ),
                libraries =
                    mapOf(
                        "cle-relogee" to FauxLibrary(type = com.android.builder.model.v2.ide.LibraryType.RELOCATED),
                        "cle-bom" to FauxLibrary(type = com.android.builder.model.v2.ide.LibraryType.NO_ARTIFACT_FILE),
                    ),
            )

        val dto = convertisseur.convertir(":app", FauxBasicAndroidProject(), FauxAndroidProject(), deps, "debug")

        // Seules les sorties du module subsistent — pas d'entrée de graphe.
        assertTrue(dto.entries.none { it.path == "cle-relogee" || it.path == "cle-bom" })
    }

    @Test
    fun `convertir - graphe de test porte la portee test et dependance non resolue signalee`() {
        val jarTest = File("/gradle/caches/modules-2/files-2.1/junit/junit/4.13.2/h1/junit-4.13.2.jar")
        val deps =
            FauxVariantDependencies(
                mainArtifact = FauxArtifactDependencies(),
                unitTestArtifact =
                    FauxArtifactDependencies(
                        compileDependencies = listOf(FauxGraphItem("cle-junit")),
                        unresolvedDependencies = listOf(FauxUnresolvedDependency()),
                    ),
                libraries =
                    mapOf(
                        "cle-junit" to
                            FauxLibrary(
                                type = com.android.builder.model.v2.ide.LibraryType.JAVA_LIBRARY,
                                artifact = jarTest,
                            ),
                    ),
            )

        val dto = convertisseur.convertir(":app", FauxBasicAndroidProject(), FauxAndroidProject(), deps, "debug")

        val entreeTest = dto.entries.first { it.path == jarTest.absolutePath }
        assertEquals("test", entreeTest.scope)
        assertTrue(dto.avertissements.any { it.contains("com.exemple:absente") && it.contains("introuvable") })
    }

    // ------------------------------------------------------------------
    // Graphe : transitives dédupliquées, ordre de résolution préservé.
    // ------------------------------------------------------------------

    @Test
    fun `convertir - les transitives du graphe sont dedupliquees et toutes livrees`() {
        val jarDirect = File("/cache/direct.jar")
        val jarTransitive = File("/cache/transitive.jar")
        val deps =
            FauxVariantDependencies(
                mainArtifact =
                    FauxArtifactDependencies(
                        compileDependencies =
                            listOf(
                                FauxGraphItem("cle-direct", dependencies = listOf(FauxGraphItem("cle-transitive"))),
                                // La transitive réapparaît par un second chemin.
                                FauxGraphItem("cle-autre", dependencies = listOf(FauxGraphItem("cle-transitive"))),
                            ),
                    ),
                libraries =
                    mapOf(
                        "cle-direct" to
                            FauxLibrary(
                                type = com.android.builder.model.v2.ide.LibraryType.JAVA_LIBRARY,
                                artifact = jarDirect,
                            ),
                        "cle-autre" to
                            FauxLibrary(
                                type = com.android.builder.model.v2.ide.LibraryType.JAVA_LIBRARY,
                                artifact = File("/cache/autre.jar"),
                            ),
                        "cle-transitive" to
                            FauxLibrary(
                                type = com.android.builder.model.v2.ide.LibraryType.JAVA_LIBRARY,
                                artifact = jarTransitive,
                            ),
                    ),
            )

        val dto = convertisseur.convertir(":app", FauxBasicAndroidProject(), FauxAndroidProject(), deps, "debug")

        val jars = dto.entries.filter { it.kind == "JAR" && it.scope == "compile" }
        assertEquals("la transitive n'est livrée qu'une fois", 3, jars.size)
        assertEquals(jarDirect.absolutePath, jars.first().path)
    }
}

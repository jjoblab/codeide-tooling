@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")

package jo.codeide.tooling.server

import com.android.builder.model.v2.ide.AndroidArtifact
import com.android.builder.model.v2.ide.AndroidLibraryData
import com.android.builder.model.v2.ide.ApiVersion
import com.android.builder.model.v2.ide.ArtifactDependencies
import com.android.builder.model.v2.ide.BasicArtifact
import com.android.builder.model.v2.ide.BasicVariant
import com.android.builder.model.v2.ide.BundleInfo
import com.android.builder.model.v2.ide.CodeShrinker
import com.android.builder.model.v2.ide.GraphItem
import com.android.builder.model.v2.ide.JavaArtifact
import com.android.builder.model.v2.ide.Library
import com.android.builder.model.v2.ide.LibraryInfo
import com.android.builder.model.v2.ide.LibraryType
import com.android.builder.model.v2.ide.ProjectInfo
import com.android.builder.model.v2.ide.ProjectType
import com.android.builder.model.v2.ide.SourceProvider
import com.android.builder.model.v2.ide.SourceSetContainer
import com.android.builder.model.v2.ide.TestInfo
import com.android.builder.model.v2.ide.UnresolvedDependency
import com.android.builder.model.v2.ide.Variant
import com.android.builder.model.v2.models.AndroidProject
import com.android.builder.model.v2.models.BasicAndroidProject
import com.android.builder.model.v2.models.VariantDependencies
import java.io.File

/**
 * Faux du modèle v2 d'AGP pour tester [ModelesAndroidV2.convertir] et
 * [ModelesAndroidV2.choisirVariante] SANS démon Gradle (le convertisseur
 * est pur par conception — les seules méthodes réellement lues portent
 * des valeurs, le reste retourne des défauts honnêtes).
 */
internal class FauxBasicAndroidProject(
    override val path: String = ":app",
    override val bootClasspath: List<File> = listOf(File("/sdk/platforms/android-37/android.jar")),
    override val variants: Collection<BasicVariant> = listOf(FauxBasicVariant("debug"), FauxBasicVariant("release")),
    override val mainSourceSet: SourceSetContainer? = FauxSourceSetContainer(),
    override val buildFolder: File = File("/p/app/build"),
) : BasicAndroidProject {
    override val buildName: String get() = "app"
    override val projectType: ProjectType get() = ProjectType.APPLICATION
    override val buildTypeSourceSets: Collection<SourceSetContainer> get() = emptyList()
    override val productFlavorSourceSets: Collection<SourceSetContainer> get() = emptyList()
    override val testSuites: Collection<com.android.builder.model.v2.models.BasicTestSuite> get() = emptyList()
}

@Suppress("DEPRECATION")
internal class FauxBasicVariant(
    override val name: String,
) : BasicVariant {
    override val mainArtifact: BasicArtifact get() = error("non lu par le convertisseur")
    override val buildType: String get() = name
    override val productFlavors: List<String> get() = emptyList()
    override val androidTestArtifact: BasicArtifact? get() = null
    override val deviceTestArtifacts: Map<String, BasicArtifact> get() = emptyMap()
    override val unitTestArtifact: BasicArtifact? get() = null
    override val hostTestArtifacts: Map<String, BasicArtifact> get() = emptyMap()
    override val testSuiteArtifacts:
        Map<String, com.android.builder.model.v2.ide.BasicTestSuiteArtifact>
        get() = emptyMap()
    override val testFixturesArtifact: BasicArtifact? get() = null
}

@Suppress("DEPRECATION")
internal class FauxSourceSetContainer(
    override val sourceProvider: SourceProvider? = FauxSourceProvider(principal = true),
    override val unitTestSourceProvider: SourceProvider? = FauxSourceProvider(principal = false),
) : SourceSetContainer {
    override val deviceTestSourceProviders: Map<String, SourceProvider> get() = emptyMap()
    override val androidTestSourceProvider: SourceProvider? get() = null
    override val hostTestSourceProviders: Map<String, SourceProvider> get() = emptyMap()
    override val testFixturesSourceProvider: SourceProvider? get() = null
}

internal class FauxSourceProvider(
    principal: Boolean,
) : SourceProvider {
    private val base = if (principal) "/p/app/src/main" else "/p/app/src/test"
    private val estPrincipal: Boolean = principal

    override val name: String get() = if (estPrincipal) "main" else "test"

    override val javaDirectories: Collection<File> get() = listOf(File("$base/java"))

    override val kotlinDirectories: Collection<File> get() = listOf(File("$base/kotlin"))

    override val manifestFile: File get() = File("$base/AndroidManifest.xml")
    override val resourcesDirectories: Collection<File> get() = emptyList()
    override val aidlDirectories: Collection<File> get() = emptyList()
    override val renderscriptDirectories: Collection<File> get() = emptyList()
    override val baselineProfileDirectories: Collection<File> get() = emptyList()
    override val keepRulesDirectories: Collection<File> get() = emptyList()
    override val aarKeepRulesDirectories: Collection<File> get() = emptyList()
    override val resDirectories: Collection<File> get() = emptyList()
    override val assetsDirectories: Collection<File> get() = emptyList()
    override val jniLibsDirectories: Collection<File> get() = emptyList()
    override val shadersDirectories: Collection<File> get() = emptyList()
    override val mlModelsDirectories: Collection<File> get() = emptyList()
    override val customDirectories: Collection<com.android.builder.model.v2.CustomSourceDirectory>
        get() = emptyList()
}

internal class FauxAndroidProject(
    override val variants: Collection<Variant> = listOf(FauxVariant("debug")),
    override val javaCompileOptions: com.android.builder.model.v2.ide.JavaCompileOptions = FauxJavaCompileOptions(),
) : AndroidProject {
    override val namespace: String get() = "com.exemple"
    override val androidTestNamespace: String? get() = null
    override val testFixturesNamespace: String? get() = null
    override val testSuites: Collection<com.android.builder.model.v2.ide.TestSuite> get() = emptyList()

    override val resourcePrefix: String? get() = null
    override val dynamicFeatures: Collection<String> get() = emptyList()
    override val viewBindingOptions: com.android.builder.model.v2.ide.ViewBindingOptions? get() = null
    override val flags: com.android.builder.model.v2.ide.AndroidGradlePluginProjectFlags
        get() = error("non lu par le convertisseur")

    override val lintChecksJars: List<File> get() = emptyList()
    override val desugarLibConfig: List<File> get() = emptyList()
    override val lintJar: File? get() = null
}

/**
 * Options de compilation Java du modèle v2 (S1-a) — les niveaux du
 * module Android, tels qu'AGP les livre (chaîne « 17 », « 1.8 »…).
 */
internal class FauxJavaCompileOptions(
    override val sourceCompatibility: String = "17",
    override val targetCompatibility: String = "17",
    override val encoding: String = "UTF-8",
) : com.android.builder.model.v2.ide.JavaCompileOptions {
    override val isCoreLibraryDesugaringEnabled: Boolean get() = false
}

@Suppress("DEPRECATION")
internal class FauxVariant(
    override val name: String,
    override val mainArtifact: AndroidArtifact = FauxAndroidArtifact(),
    override val unitTestArtifact: JavaArtifact? = null,
) : Variant {
    override val displayName: String get() = name
    override val androidTestArtifact: AndroidArtifact? get() = null
    override val deviceTestArtifacts: Map<String, AndroidArtifact> get() = emptyMap()
    override val hostTestArtifacts: Map<String, JavaArtifact> get() = emptyMap()
    override val testSuiteArtifacts: Map<String, com.android.builder.model.v2.ide.TestSuiteArtifact> get() = emptyMap()
    override val testFixturesArtifact: AndroidArtifact? get() = null
    override val testedTargetVariant: com.android.builder.model.v2.ide.TestedTargetVariant? get() = null
    override val runTestInSeparateProcess: Boolean get() = false
    override val isInstantAppCompatible: Boolean get() = false
    override val desugaredMethods: List<File> get() = emptyList()
    override val experimentalProperties: Map<String, String> get() = emptyMap()
}

internal class FauxAndroidArtifact(
    override val generatedSourceFolders: Collection<File> =
        listOf(File("/p/app/build/generated/ap_generated_sources/debug/out")),
) : AndroidArtifact {
    override val classesFolders: Set<File>
        get() =
            setOf(
                File("/p/app/build/intermediates/javac/debug/classes"),
                File("/p/app/build/intermediates/compile_r_class_jar/debug/generateDebugRFile/R.jar"),
            )

    override val minSdkVersion: ApiVersion get() = ApiVersionImpl()
    override val targetSdkVersionOverride: ApiVersion? get() = null
    override val maxSdkVersion: Int? get() = null
    override val isSigned: Boolean get() = false
    override val signingConfigName: String? get() = null
    override val applicationId: String? get() = null
    override val sourceGenTaskName: String get() = "generateDebugSources"
    override val resGenTaskName: String get() = "generateDebugResources"
    override val generatedResourceFolders: Collection<File> get() = emptyList()
    override val abiFilters: Set<String> get() = emptySet()
    override val assembleTaskOutputListingFile: File? get() = null
    override val testInfo: TestInfo? get() = null
    override val bundleInfo: BundleInfo? get() = null
    override val codeShrinker: CodeShrinker? get() = null
    override val desugaredMethodsFiles: Collection<File> get() = emptyList()
    override val generatedAssetsFolders: Collection<File> get() = emptyList()
    override val mappingR8TextFile: File? get() = null
    override val mappingR8PartitionFile: File? get() = null
    override val compileTaskName: String get() = "compileDebugJavaWithJavac"
    override val assembleTaskName: String get() = "assembleDebug"
    override val ideSetupTaskNames: Set<String> get() = emptySet()
    override val modelSyncFiles: Collection<Void> get() = emptyList()
    override val generatedClassPaths: Map<String, File> get() = emptyMap()
    override val bytecodeTransformations: Collection<com.android.builder.model.v2.ide.BytecodeTransformation>
        get() = emptyList()
}

internal class FauxJavaArtifact(
    override val generatedSourceFolders: Collection<File> = emptyList(),
) : JavaArtifact {
    override val mockablePlatformJar: File? get() = null
    override val runtimeResourceFolder: File? get() = null
    override val compileTaskName: String get() = "compileDebugUnitTestJavaWithJavac"
    override val assembleTaskName: String get() = "assembleDebugUnitTest"
    override val classesFolders: Set<File> get() = emptySet()
    override val ideSetupTaskNames: Set<String> get() = emptySet()
    override val modelSyncFiles: Collection<Void> get() = emptyList()
    override val generatedClassPaths: Map<String, File> get() = emptyMap()
    override val bytecodeTransformations: Collection<com.android.builder.model.v2.ide.BytecodeTransformation>
        get() = emptyList()
}

/** ApiVersion minimale (non lue par le convertisseur). */
internal class ApiVersionImpl : ApiVersion {
    override val apiLevel: Int get() = 26
    override val codename: String? get() = null
}

internal class FauxVariantDependencies(
    override val mainArtifact: ArtifactDependencies = FauxArtifactDependencies(),
    override val unitTestArtifact: ArtifactDependencies? = null,
    override val libraries: Map<String, Library> = emptyMap(),
) : VariantDependencies {
    override val name: String get() = "debug"
    override val deviceTestArtifacts: Map<String, ArtifactDependencies> get() = emptyMap()
    override val androidTestArtifact: ArtifactDependencies? get() = null
    override val hostTestArtifacts: Map<String, ArtifactDependencies> get() = emptyMap()
    override val testSuiteArtifacts: Map<String, com.android.builder.model.v2.models.TestSuiteDependencies>
        get() = emptyMap()

    override val testFixturesArtifact: ArtifactDependencies? get() = null
}

internal class FauxArtifactDependencies(
    override val compileDependencies: List<GraphItem> = emptyList(),
    override val unresolvedDependencies: List<UnresolvedDependency> = emptyList(),
) : ArtifactDependencies {
    override val runtimeDependencies: List<GraphItem> get() = emptyList()
}

internal class FauxGraphItem(
    override val key: String,
    override val dependencies: List<GraphItem> = emptyList(),
) : GraphItem {
    override val requestedCoordinates: String? get() = null
}

internal class FauxUnresolvedDependency(
    override val name: String = "com.exemple:absente",
    override val cause: String = "introuvable",
) : UnresolvedDependency

internal class FauxLibrary(
    override val type: LibraryType,
    override val artifact: File? = null,
    override val srcJars: List<File> = emptyList(),
    override val androidLibraryData: AndroidLibraryData? = null,
    override val projectInfo: ProjectInfo? = null,
) : Library {
    override val key: String get() = "cle-${type.name.lowercase()}"
    override val libraryInfo: LibraryInfo? get() = null
    override val lintJar: File? get() = null

    @Suppress("DEPRECATION")
    override val srcJar: File? get() = null
    override val docJar: File? get() = null
    override val samplesJar: File? get() = null
}

internal class FauxAndroidLibraryData(
    override val compileJarFiles: List<File> = emptyList(),
) : AndroidLibraryData {
    override val manifest: File get() = File("/cache/AndroidManifest.xml")
    override val runtimeJarFiles: List<File> get() = emptyList()
    override val resFolder: File get() = File("/cache/res")
    override val resStaticLibrary: File get() = File("/cache/res.dat")
    override val assetsFolder: File get() = File("/cache/assets")
    override val jniFolder: File get() = File("/cache/jni")
    override val aidlFolder: File get() = File("/cache/aidl")
    override val renderscriptFolder: File get() = File("/cache/rs")
    override val proguardRules: File get() = File("/cache/proguard.txt")
    override val externalAnnotations: File get() = File("/cache/annotations.zip")
    override val publicResources: File get() = File("/cache/public.txt")
    override val symbolFile: File get() = File("/cache/R.txt")
}

internal class FauxProjectInfo(
    override val projectPath: String = ":lib",
) : ProjectInfo {
    override val buildId: String get() = ":"
    override val buildType: String? get() = null
    override val productFlavors: Map<String, String> get() = emptyMap()
    override val attributes: Map<String, String> get() = emptyMap()
    override val capabilities: List<String> get() = emptyList()
    override val isTestFixtures: Boolean get() = false
}

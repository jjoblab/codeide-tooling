package jo.codeide.buildlogic

import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.configure

/**
 * Convention `codeide.android.room` — persistance Room via KSP.
 *
 * Les schémas sont exportés dans `<module>/schemas` (section 6 : « schémas
 * exportés, migrations testables ») : ils sont versionnés et servent de
 * référence pour les tests de migration.
 */
class AndroidRoomConventionPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        with(project) {
            pluginManager.apply("com.google.devtools.ksp")

            extensions.configure<KspExtension> {
                arg("room.schemaLocation", project.layout.projectDirectory.dir("schemas").asFile.path)
            }

            dependencies.apply {
                add("implementation", libs.findLibrary("room-runtime").get())
                add("implementation", libs.findLibrary("room-ktx").get())
                add("ksp", libs.findLibrary("room-compiler").get())
            }
        }
    }
}

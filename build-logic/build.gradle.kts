// build-logic — enregistre les convention plugins CodeIDE (section 6 du prompt maître).

plugins {
    `kotlin-dsl`
}

gradlePlugin {
    plugins {
        register("kotlinLibrary") {
            id = "codeide.kotlin.library"
            implementationClass = "jo.codeide.buildlogic.KotlinLibraryConventionPlugin"
        }
        register("androidApplication") {
            id = "codeide.android.application"
            implementationClass = "jo.codeide.buildlogic.AndroidApplicationConventionPlugin"
        }
        register("androidLibrary") {
            id = "codeide.android.library"
            implementationClass = "jo.codeide.buildlogic.AndroidLibraryConventionPlugin"
        }
        register("androidFeature") {
            id = "codeide.android.feature"
            implementationClass = "jo.codeide.buildlogic.AndroidFeatureConventionPlugin"
        }
        register("androidHilt") {
            id = "codeide.android.hilt"
            implementationClass = "jo.codeide.buildlogic.AndroidHiltConventionPlugin"
        }
        register("androidRoom") {
            id = "codeide.android.room"
            implementationClass = "jo.codeide.buildlogic.AndroidRoomConventionPlugin"
        }
        register("moduleRules") {
            id = "codeide.module-rules"
            implementationClass = "jo.codeide.buildlogic.ModuleRulesPlugin"
        }
    }
}

dependencies {
    compileOnly(libs.android.gradlePlugin)
    compileOnly(libs.kotlin.gradlePlugin)
    compileOnly(libs.ksp.gradlePlugin)
    compileOnly(libs.hilt.gradlePlugin)
    compileOnly(libs.detekt.gradlePlugin)
    compileOnly(libs.spotless.gradlePlugin)
    compileOnly(libs.kover.gradlePlugin)
}

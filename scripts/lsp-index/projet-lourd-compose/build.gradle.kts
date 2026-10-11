// Banc « lourd » (recette versionnée — docs/LSP_JAVA.md §12) : projet
// Compose synthétique résolu par Gradle (BOM 2025.09.00, material3,
// navigation, room, hilt, coil, retrofit, camera, media3, work,
// datastore, mlkit, firebase, commons, jackson) — représentatif d'une
// vraie app lourde (250 entrées dont ~76 % d'AAR normalisés).
plugins {
    java
}

repositories {
    google()
    mavenCentral()
}

// Les artefacts Compose/AndroidX publient des variantes jvm ET androidJvm :
// un consommateur Java seul doit DIRE laquelle (androidJvm = les AAR
// réellement consommés sur appareil, comme l'étude §12).
configurations.getByName("compileClasspath") {
    attributes {
        attribute(
            org.gradle.api.attributes.Attribute.of("org.jetbrains.kotlin.platform.type", String::class.java),
            "androidJvm",
        )
    }
}

dependencies {
    // Chaîne androidx de base par BOM Compose.
    implementation(platform("androidx.compose:compose-bom:2025.09.00"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.runtime:runtime")

    // Architecture.
    implementation("androidx.navigation:navigation-compose:2.9.0")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    implementation("com.google.dagger:hilt-android:2.60.1")

    // Réseau et images.
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-jackson:2.11.0")

    // Multimédia et capteurs.
    implementation("androidx.camera:camera-camera2:1.5.0")
    implementation("androidx.camera:camera-lifecycle:1.5.0")
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-ui:1.8.0")

    // Planification et persistance légère.
    implementation("androidx.work:work-runtime:2.11.0")
    implementation("androidx.datastore:datastore:1.2.1")

    // ML et Firebase.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    implementation("com.google.firebase:firebase-analytics:22.4.0")

    // Bibliothèques courantes.
    implementation("org.apache.commons:commons-lang3:3.17.0")
    implementation("commons-io:commons-io:2.17.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.2")
    implementation("com.google.guava:guava:33.4.0-jre")
}

// Résolution imprimée en un manifeste de chemins absolus (une par ligne).
tasks.register("manifesteClasspath") {
    doLast {
        val sortie = File(project.rootDir.resolve("../../../build/lsp-index/provision"), "lourd-chemins.txt")
        sortie.parentFile.mkdirs()
        sortie.writeText(
            configurations.getByName("compileClasspath")
                .resolve()
                .filter { it.isFile && it.extension == "jar" }
                .joinToString("\n") { it.absolutePath },
        )
    }
}

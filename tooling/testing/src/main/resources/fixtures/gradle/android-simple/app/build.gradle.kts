plugins { id("com.android.application") }

android {
    namespace = "com.exemple.essai"
    compileSdk = 37
    defaultConfig { minSdk = 26 }
}

dependencies {
    implementation(project(":lib"))
    implementation("commons-io:commons-io:2.20.0")
    testImplementation("junit:junit:4.13.2")
}

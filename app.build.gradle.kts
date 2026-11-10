plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.fp10ble"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.fp10ctrl"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    // Clé de signature stable (fichier debug.keystore à la racine du dépôt, s'il est présent) :
    // permet de mettre l'app à jour sans la désinstaller, donc sans perdre la banque de boutons.
    val ks = file("$rootDir/debug.keystore")
    if (ks.exists()) {
        signingConfigs.getByName("debug") {
            storeFile = ks
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

// index.html (à la racine du dépôt) est copié dans les assets avant chaque build
val copyWeb = tasks.register<Copy>("copyWeb") {
    from("$rootDir/index.html")
    into("$projectDir/src/main/assets")
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(copyWeb) }

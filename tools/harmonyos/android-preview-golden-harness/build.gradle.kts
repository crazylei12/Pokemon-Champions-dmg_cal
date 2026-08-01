plugins {
    id("com.android.application") version "9.2.1"
}

val productionRoot = providers.gradleProperty("androidProductionRoot").get()
val sampleRoot = providers.gradleProperty("teamPreviewSampleRoot").get()
val productionPackage = file(
    "$productionRoot/android-app/app/src/main/java/com/crazylei12/pokemonchampionsassistant",
)
val generatedProductionSources = layout.buildDirectory.dir("generated/productionSources")
val generatedRecognitionAssets = layout.buildDirectory.dir("generated/recognitionAssets")

android {
    namespace = "com.crazylei12.pokemonchampionsassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.crazylei12.pokemonchampionsassistant.golden"
        minSdk = 33
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters += "x86_64"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main").kotlin.directories.add(generatedProductionSources.get().asFile.absolutePath)
    sourceSets.getByName("main").assets.directories.add(generatedRecognitionAssets.get().asFile.absolutePath)
    sourceSets.getByName("androidTest").assets.directories.add(file(sampleRoot).absolutePath)

    packaging {
        jniLibs.useLegacyPackaging = true
    }
}

val syncProductionSources by tasks.registering(Sync::class) {
    from(productionPackage) {
        include("TeamPreviewRecognition.kt")
        include("TeamPreviewViewportMapping.kt")
        include("CloseSafeSerialExecutor.kt")
        include("AppStorage.kt")
    }
    into(generatedProductionSources.map {
        it.dir("com/crazylei12/pokemonchampionsassistant")
    })
}

val syncRecognitionAssets by tasks.registering(Sync::class) {
    from(file("$productionRoot/src/data/recognition/team-preview.safe-zone-roi.zh-Hans.v2.json"))
    from(file("$productionRoot/src/data/recognition/android/team-preview-templates-v2.bin"))
    into(layout.buildDirectory.dir("generated/recognitionAssets/recognition"))
}

tasks.named("preBuild").configure {
    dependsOn(syncProductionSources)
    dependsOn(syncRecognitionAssets)
}

dependencies {
    implementation("org.opencv:opencv:4.13.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

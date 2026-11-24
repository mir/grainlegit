plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.grainlegit.editor"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "dev.grainlegit.editor"
        minSdk = 36
        targetSdk = 36
        versionCode = 7
        versionName = "0.7"
        ndk.abiFilters.add("arm64-v8a")
    }

    // Debug builds install beside the signed release instead of replacing it.
    buildTypes.getByName("debug").applicationIdSuffix = ".dev"

    buildFeatures.compose = true
    // LiteRT memory-maps models straight from the APK.
    androidResources.noCompress += "tflite"
    // The Hexagon loader reads the NPU runtime from the extracted native library directory.
    packaging.jniLibs.useLegacyPackaging = true
    // Keep only the Snapdragon 8 Elite Gen 5 (Hexagon v81) NPU runtime from the QAIRT package.
    packaging.jniLibs.excludes += listOf(
        "**/libQnnDsp*.so", "**/libQnnGpu.so", "**/libQnnHtpV6*", "**/libQnnHtpV7*",
    )
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}

android.compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.10.00"))
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.ai.edge.litert:litert:2.4.0")
    implementation("com.google.ai.edge.litert:litert-gpu:2.4.0")
    implementation("com.google.ai.edge.litert:litert-npu-runtime-qualcomm:2.4.0")
    // LiteRT 2.4.0 needs QNN System API 1.16 or newer (QAIRT 2.52).
    implementation("com.qualcomm.qti:qnn-runtime:2.52.0")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.29.0")
    implementation("com.qualcomm.qti:onnxruntime-android-qnn:2.8.0")
}

abstract class BuildRustRenderer : Exec() {
    @get:OutputDirectory
    abstract val jniOutput: DirectoryProperty
}

val repo = rootProject.layout.projectDirectory.dir("..")
val renderer = repo.dir("renderer")
val nativeScript = repo.file("scripts/build-android-native")
val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
val nativeNdkVersion = android.ndkVersion

androidComponents.onVariants { variant ->
    val name = variant.name.replaceFirstChar(Char::uppercaseChar)
    val nativeBuild = tasks.register<BuildRustRenderer>("build${name}RustRenderer") {
        jniOutput.set(layout.buildDirectory.dir("generated/jniLibs/${variant.name}"))
        inputs.files(
            nativeScript,
            renderer.files("Cargo.toml", "Cargo.lock", "rust-toolchain.toml"),
            repo.files("Cargo.toml", "Cargo.lock"),
            renderer.dir("src"), repo.dir("src"), repo.dir("profiles"),
        )
        inputs.property("ndkVersion", nativeNdkVersion)
        doFirst {
            commandLine(
                "bash", nativeScript.asFile.absolutePath,
                sdkDirectory.get().asFile.absolutePath, nativeNdkVersion,
                jniOutput.get().asFile.absolutePath,
            )
        }
    }
    variant.sources.jniLibs?.addGeneratedSourceDirectory(nativeBuild, BuildRustRenderer::jniOutput)
}

// Models are large binaries kept outside git; scripts/ai-models prepares them.
abstract class CopyModels : DefaultTask() {
    @get:InputFiles abstract val models: ConfigurableFileCollection
    @get:InputDirectory
    @get:Optional
    abstract val sm8850Models: DirectoryProperty
    @get:OutputDirectory abstract val assets: DirectoryProperty
    @get:Inject abstract val files: FileSystemOperations

    @TaskAction fun copy() {
        files.sync {
            from(models) { into("models") }
            sm8850Models.orNull?.let { directory ->
                from(directory) {
                    include("*.tflite")
                    into("models/sm8850")
                }
            }
            into(assets)
        }
    }
}

androidComponents.onVariants { variant ->
    val name = variant.name.replaceFirstChar(Char::uppercaseChar)
    val copyModels = tasks.register<CopyModels>("copy${name}Models") {
        models.from(repo.dir("local/ai-models/app"))
        if (providers.gradleProperty("sm8850Models").orNull.toBoolean()) {
            sm8850Models.set(repo.dir("local/ai-models/aot/sm8850"))
        }
        assets.set(layout.buildDirectory.dir("generated/modelAssets/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(copyModels, CopyModels::assets)
}

tasks.register("runtimeLicenseInventory") {
    doLast {
        val runtime = configurations.getByName("releaseRuntimeClasspath")
        val modules = runtime.incoming.resolutionResult.allComponents.mapNotNull {
            it.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
        }.sortedBy { "${it.group}:${it.module}" }
        val artifacts = dependencies.createArtifactResolutionQuery().forComponents(modules)
            .withArtifacts(org.gradle.maven.MavenModule::class.java, org.gradle.maven.MavenPomArtifact::class.java)
            .execute()
        for (module in modules) {
            val pom = artifacts.resolvedComponents.firstOrNull { it.id == module }
                ?.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java)
                ?.filterIsInstance<org.gradle.api.artifacts.result.ResolvedArtifactResult>()?.firstOrNull()
            println("${module.group}:${module.module}:${module.version}${'\t'}${pom?.file?.absolutePath.orEmpty()}")
        }
    }
}

package buildsrc.convention

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.RegularFile
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.TaskProvider
import org.gradle.internal.os.OperatingSystem
import org.gradle.kotlin.dsl.named
import org.gradle.kotlin.dsl.register
import org.gradle.language.jvm.tasks.ProcessResources
import java.io.File

/**
 * Canonical list of every classifier we publish. Single source of truth for
 * Kotlin/Gradle-side iteration (publication wiring, host detection sanity).
 *
 * The Nix side has its own copy in `flake.nix` (`hostClassifiers`) and
 * `nix/core-bridge.nix` (`classifierConfigs`) — keep all three in sync when
 * adding or removing a target.
 */
val allClassifiers: List<String> =
    listOf(
        "linux-x86_64-gnu",
        "linux-aarch64-gnu",
        "macos-aarch64",
        "windows-x86_64",
    )

/**
 * The classifier matching the current build host. NativeLoader.kt uses the same
 * mapping at runtime to find the right resource subdirectory.
 */
val currentClassifier: String =
    run {
        val os = OperatingSystem.current()
        val arch = System.getProperty("os.arch")
        val detected =
            when {
                os.isMacOsX && arch == "aarch64" -> "macos-aarch64"
                os.isLinux && arch == "aarch64" -> "linux-aarch64-gnu"
                os.isLinux -> "linux-x86_64-gnu"
                os.isWindows -> "windows-x86_64"
                else -> throw GradleException("Unsupported platform: ${os.name} / $arch")
            }
        check(detected in allClassifiers) {
            "Host classifier '$detected' is not in allClassifiers — list drift between host detection and publication."
        }
        detected
    }

/**
 * Resolve an explicit `-PcoreBridgeJar.<classifier>=<path>` override to a File,
 * or null if the property is not set. Used by CI to inject pre-built JARs from
 * Nix outside of Gradle (so `gradle publish` doesn't shell out to Nix itself).
 */
fun Project.coreBridgeClassifierJar(classifier: String): File? {
    val path = findProperty("coreBridgeJar.$classifier") as? String ?: return null
    return rootProject.file(path)
}

private const val NIX_BUILD_TASK = "nixBuildCoreBridgeJar"
private const val CORE_BRIDGE_PATH = ":core-bridge"

/**
 * Where the auto-invoked nix-build task drops its output JAR. A single fixed
 * path so consumers across projects can wire the same file without coordinating
 * task references across project boundaries.
 */
private fun Project.coreBridgeNixJarFile(classifier: String): RegularFile =
    rootProject
        .layout
        .projectDirectory
        .file("core-bridge/build/nix-core-bridge/core-bridge-$classifier.jar")

/**
 * Register a single task on `:core-bridge` that runs `nix build` for the host's
 * classifier. Idempotent — calling repeatedly returns the existing TaskProvider.
 */
private fun Project.registerNixBuildOnCoreBridge(classifier: String): TaskProvider<Exec> {
    require(path == CORE_BRIDGE_PATH) {
        "registerNixBuildOnCoreBridge must be called on :core-bridge (got $path)"
    }

    if (NIX_BUILD_TASK in tasks.names) {
        return tasks.named<Exec>(NIX_BUILD_TASK)
    }

    val outDir = layout.buildDirectory.dir("nix-core-bridge")
    val resultLink: Provider<RegularFile> = outDir.map { it.file("result") }
    val outJar: Provider<RegularFile> = outDir.map { it.file("core-bridge-$classifier.jar") }

    return tasks.register<Exec>(NIX_BUILD_TASK) {
        description = "Build core-bridge classifier JAR via Nix (host: $classifier)"
        group = "build"

        workingDir = rootProject.projectDir

        // Inputs: anything that affects the Nix build's output. Coarse-grained.
        // Excludes target/ and .git so cargo's local target dir doesn't churn the cache.
        inputs
            .files(
                rootProject.file("flake.nix"),
                rootProject.file("flake.lock"),
                rootProject.fileTree("nix"),
                rootProject.file("core-bridge/rust/Cargo.toml"),
                rootProject.file("core-bridge/rust/Cargo.lock"),
                rootProject.file("core-bridge/rust/rust-toolchain.toml"),
                rootProject.fileTree("core-bridge/rust/sdk-core") {
                    include("**/*.rs", "**/*.proto", "**/*.h", "**/Cargo.toml")
                    exclude("**/target/**", "**/.git/**")
                },
            ).withPropertyName("nix-source")

        outputs.file(outJar).withPropertyName("classifier-jar")

        doFirst {
            outDir.get().asFile.mkdirs()
        }

        commandLine =
            listOf(
                "nix",
                "build",
                ".#core-bridge-jar-$classifier",
                "--out-link",
                resultLink.get().asFile.absolutePath,
                "--no-warn-dirty",
            )

        doLast {
            // The result symlink points at /nix/store/...-core-bridge-<classifier>.jar/
            // which is a directory containing the actual JAR file. Copy that JAR to a
            // stable build path so consumers can declare it as a regular file dependency.
            val link = resultLink.get().asFile
            val nixStorePath = link.canonicalFile
            val producedJar =
                nixStorePath.listFiles { _, name -> name.endsWith(".jar") }?.firstOrNull()
                    ?: throw GradleException("nix build produced no JAR in $nixStorePath")
            producedJar.copyTo(outJar.get().asFile, overwrite = true)
        }
    }
}

/**
 * Wire the host's core-bridge classifier JAR into a ProcessResources task.
 *
 * Resolution order:
 *   1. If `-PcoreBridgeJar.<classifier>=<path>` is set, use that file directly.
 *      (Used by CI to inject pre-built JARs without invoking Nix from Gradle.)
 *   2. Otherwise, depend on the `:core-bridge:nixBuildCoreBridgeJar` task and
 *      consume its output. The task is registered once on `:core-bridge` (the
 *      first call site triggers registration); every other consumer just
 *      depends on it. Requires `nix` on PATH.
 */
fun Project.consumeCoreBridgeNative(processResourcesTaskName: String) {
    val classifier = currentClassifier
    val explicit = coreBridgeClassifierJar(classifier)

    if (explicit != null) {
        tasks.named<ProcessResources>(processResourcesTaskName) {
            from(zipTree(explicit))
        }
        return
    }

    val nixJar = coreBridgeNixJarFile(classifier)

    if (path == CORE_BRIDGE_PATH) {
        val nixTask = registerNixBuildOnCoreBridge(classifier)
        tasks.named<ProcessResources>(processResourcesTaskName) {
            dependsOn(nixTask)
            from(zipTree(nixTask.map { it.outputs.files.singleFile }))
        }
    } else {
        tasks.named<ProcessResources>(processResourcesTaskName) {
            // Cross-project task path resolution is lazy — :core-bridge will
            // have registered the task by the time the execution graph is built.
            dependsOn("$CORE_BRIDGE_PATH:$NIX_BUILD_TASK")
            from(zipTree(nixJar))
        }
    }
}

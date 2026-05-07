import buildsrc.convention.allClassifiers
import buildsrc.convention.consumeCoreBridgeNative
import buildsrc.convention.coreBridgeClassifierJar

plugins {
    id("buildsrc.convention.kotlin-jvm")
    id("buildsrc.convention.maven-publish")
    alias(libs.plugins.protobuf)
    id("com.github.gmazzo.buildconfig")
}

dependencies {
    api(project(":core-common"))
    implementation(libs.protobufJava)
    implementation(libs.protobufKotlin)
    implementation(libs.kotlinxCoroutines)
    implementation(libs.slf4jApi)
    compileOnly(libs.opentelemetryApi)

    testImplementation(kotlin("test"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobuf.get()}"
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("kotlin")
            }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir("rust/sdk-core/crates/common/protos/local")
            srcDir("rust/sdk-core/crates/common/protos/api_upstream")
            srcDir("rust/sdk-core/crates/common/protos/testsrv_upstream")
            // Exclude google protobuf well-known types — use runtime versions to
            // prevent version conflicts with protobuf-java.
            exclude("**/google/protobuf/**")
        }
    }
}

consumeCoreBridgeNative("processTestResources")

// Enable native access for FFM API to suppress warnings
tasks.withType<Test> {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}

// Generate BuildConfig with version constants
val temporalCliVersion: String by project

buildConfig {
    packageName("com.surrealdev.temporal.core")
    documentation.set("Build-time configuration constants.")

    buildConfigField("TEMPORAL_CLI_VERSION", temporalCliVersion)
    buildConfigField("SDK_VERSION", project.version.toString())
}

// Configure Dokka to exclude generated code to prevent OOM
dokka {
    dokkaSourceSets.configureEach {
        // Exclude generated protobuf code from documentation
        suppressedFiles.from(
            fileTree("${layout.buildDirectory.get()}/generated/source/proto"),
        )
    }
}

mavenPublishing {
    coordinates(artifactId = "core-bridge")

    pom {
        name.set("Temporal KT Core Bridge")
        description.set("Kotlin FFM Bridge to Temporal Core SDK")
    }
}

// Register each Nix-built classifier JAR as a Maven artifact on the main publication.
// Classifier JARs not provided via -PcoreBridgeJar.<classifier> are silently skipped;
// the publish CI job ensures all four are passed in.
afterEvaluate {
    publishing {
        publications {
            named<MavenPublication>("maven") {
                allClassifiers.forEach { classifier ->
                    coreBridgeClassifierJar(classifier)?.let { jar ->
                        artifact(jar) {
                            this.classifier = classifier
                            extension = "jar"
                        }
                    }
                }
            }
        }
    }
}

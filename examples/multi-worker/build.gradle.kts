import buildsrc.convention.consumeCoreBridgeNative

plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    application
}

dependencies {
    implementation(project(":core"))
}

application {
    mainClass.set("com.example.multiworker.MainKt")
}

consumeCoreBridgeNative("processResources")

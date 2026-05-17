plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.protobuf)
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDir("../../WorkshopOnAndroid/steam-protocol/src/main/kotlin")
        proto.srcDir("../../WorkshopOnAndroid/steam-protocol/src/main/proto")
    }
}

dependencies {
    api("com.google.protobuf:protobuf-java:${libs.versions.protobufLite.get()}")
    implementation(platform(libs.okhttpBom))
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:${libs.versions.protobufLite.get()}"
    }
    generateProtoTasks {
        all().configureEach {
            builtins {
                maybeCreate("java").apply { option("lite") }
            }
        }
    }
}

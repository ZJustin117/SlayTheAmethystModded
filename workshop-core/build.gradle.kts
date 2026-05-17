plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(21)
}

sourceSets {
    main {
        kotlin.srcDir("../../WorkshopOnAndroid/workshop-core/src/main/kotlin")
    }
}

dependencies {
    implementation(project(":steam-protocol"))
    implementation(platform(libs.okhttpBom))
    implementation(libs.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.okio)
    implementation(libs.tukaani.xz)
}

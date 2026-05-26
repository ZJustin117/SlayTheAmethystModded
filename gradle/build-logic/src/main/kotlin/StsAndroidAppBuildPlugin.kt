import com.android.build.api.artifact.SingleArtifact
import com.android.build.api.dsl.ApplicationExtension
import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import com.android.build.gradle.AppExtension
import com.android.build.gradle.api.ApkVariantOutput
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.file.Directory
import org.gradle.api.file.DuplicatesStrategy
import org.gradle.api.provider.Provider
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.TaskProvider
import org.gradle.api.tasks.bundling.Zip
import org.gradle.api.tasks.compile.JavaCompile
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.create
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.register
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

@Suppress("unused")
class StsAndroidAppBuildPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.withId("com.android.application") {
            project.configureStsAndroidAppBuild()
        }
    }
}

private fun Project.configureStsAndroidAppBuild() {
    val packageName = readGradleProperty("application.id")
    val appVersionName = readGradleProperty("application.version.name")
    val generatedRuntimeAssetsDir = layout.buildDirectory.dir("generated/runtime-assets")
    val generatedAndroidCallbackBridgeDir = layout.buildDirectory.dir("generated/source/callbackBridge/android")

    configureGeneratedAndroidSources(generatedRuntimeAssetsDir, generatedAndroidCallbackBridgeDir)
    configureApkOutput(appVersionName)

    val assetTasks = registerRuntimeAssetTasks(
        generatedRuntimeAssetsDir = generatedRuntimeAssetsDir,
        generatedAndroidCallbackBridgeDir = generatedAndroidCallbackBridgeDir
    )
    val adb = androidComponents().sdkComponents.adb.map { it.asFile.absolutePath }
    registerAdbTasks(adb, packageName)

    tasks.named("preBuild").configure {
        dependsOn(assetTasks)
    }
}

private fun Project.configureGeneratedAndroidSources(
    generatedRuntimeAssetsDir: Provider<Directory>,
    generatedAndroidCallbackBridgeDir: Provider<Directory>
) {
    extensions.configure<ApplicationExtension> {
        sourceSets.getByName("main") {
            assets.srcDir(generatedRuntimeAssetsDir)
            java.srcDir(generatedAndroidCallbackBridgeDir)
        }
    }
}

private fun Project.configureApkOutput(appVersionName: String) {
    @Suppress("DEPRECATION")
    extensions.configure<AppExtension> {
        @Suppress("DEPRECATION")
        applicationVariants.all {
            outputs.all {
                @Suppress("DEPRECATION")
                if (this is ApkVariantOutput) {
                    outputFileName = "SlayTheAmethyst-stable-$appVersionName.apk"
                }
            }
        }
    }

    androidComponents().onVariants { variant ->
        val assembleTaskName = "assemble${variant.name.replaceFirstChar { it.uppercaseChar() }}"
        tasks.matching { it.name == assembleTaskName }.configureEach {
            doLast {
                val apkDir = variant.artifacts.get(SingleArtifact.APK).get().asFile
                logger.lifecycle("APK output directory: ${apkDir.absolutePath}")
            }
        }
    }
}

private fun Project.registerRuntimeAssetTasks(
    generatedRuntimeAssetsDir: Provider<Directory>,
    generatedAndroidCallbackBridgeDir: Provider<Directory>
): List<TaskProvider<out Task>> {
    val callbackBridgeTemplatesDir = rootProject.layout.projectDirectory.dir("gradle/callback-bridge/templates")
    val callbackBridgeBaseJar = rootProject.layout.projectDirectory.file("gradle/callback-bridge/lwjgl-glfw-classes-base.jar")
    val generatedJvmCallbackBridgeSourceDir = layout.buildDirectory.dir("generated/source/callbackBridge/jvm")
    val generatedJvmCallbackBridgeClassesDir = layout.buildDirectory.dir("generated/classes/callbackBridge/jvm")
    val generatedJvmCallbackBridgeJarContentsDir = layout.buildDirectory.dir("generated/tmp/callbackBridge/jar-contents")
    val packagedLwjglBridgeJarDir = layout.buildDirectory.dir("generated/callbackBridgeRuntimeJar")
    val generatedLwjglBridgeAssetDir = generatedRuntimeAssetsDir.map { it.dir("components/lwjgl3") }
    val runtimePackZip = rootProject.layout.projectDirectory.file("runtime-pack/jre8-pojav.zip")
    val log4jRuntimeComponents = configurations.create("log4jRuntimeComponents") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isVisible = false
    }

    val libs = extensions.getByType<VersionCatalogsExtension>().named("libs")
    dependencies.add(log4jRuntimeComponents.name, libs.findLibrary("log4j-api").get())
    dependencies.add(log4jRuntimeComponents.name, libs.findLibrary("log4j-core").get())

    fun renderCallbackBridge(target: CallbackBridgeTarget): String {
        val templateFile = when (target) {
            CallbackBridgeTarget.ANDROID -> callbackBridgeTemplatesDir.file("android/CallbackBridge.java.tmpl").asFile
            CallbackBridgeTarget.JVM -> callbackBridgeTemplatesDir.file("jvm/CallbackBridge.java.tmpl").asFile
        }
        return CallbackBridgeCodegen.renderTemplate(
            templateFile.readText(StandardCharsets.UTF_8),
            target
        )
    }

    val installBootBridgeJar = tasks.register<Copy>("installBootBridgeJar") {
        dependsOn(":boot-bridge:jar")
        from(project(":boot-bridge").layout.buildDirectory.file("libs/boot-bridge.jar"))
        into(generatedRuntimeAssetsDir.map { it.dir("components/boot_bridge") })
    }

    val generateAndroidCallbackBridgeSource = tasks.register<DefaultTask>("generateAndroidCallbackBridgeSource") {
        val templateFile = callbackBridgeTemplatesDir.file("android/CallbackBridge.java.tmpl")
        inputs.file(templateFile)
        inputs.property("callbackBridgeContractHash", CallbackBridgeCodegen.contractHash)
        outputs.dir(generatedAndroidCallbackBridgeDir)
        doLast {
            val outputDir = generatedAndroidCallbackBridgeDir.get().asFile.resolve("org/lwjgl/glfw")
            outputDir.mkdirs()
            File(outputDir, "CallbackBridge.java").writeText(
                renderCallbackBridge(CallbackBridgeTarget.ANDROID),
                StandardCharsets.UTF_8
            )
        }
    }

    val generateJvmCallbackBridgeSource = tasks.register<DefaultTask>("generateJvmCallbackBridgeSource") {
        val templateFile = callbackBridgeTemplatesDir.file("jvm/CallbackBridge.java.tmpl")
        inputs.file(templateFile)
        inputs.property("callbackBridgeContractHash", CallbackBridgeCodegen.contractHash)
        outputs.dir(generatedJvmCallbackBridgeSourceDir)
        doLast {
            val outputDir = generatedJvmCallbackBridgeSourceDir.get().asFile.resolve("org/lwjgl/glfw")
            outputDir.mkdirs()
            File(outputDir, "CallbackBridge.java").writeText(
                renderCallbackBridge(CallbackBridgeTarget.JVM),
                StandardCharsets.UTF_8
            )
        }
    }

    val compileJvmCallbackBridge = tasks.register<JavaCompile>("compileJvmCallbackBridge") {
        dependsOn(generateJvmCallbackBridgeSource)
        source(generatedJvmCallbackBridgeSourceDir)
        destinationDirectory.set(generatedJvmCallbackBridgeClassesDir)
        sourceCompatibility = JavaVersion.VERSION_1_8.toString()
        targetCompatibility = JavaVersion.VERSION_1_8.toString()
        options.release.set(8)
        classpath = files()
    }

    val prepareJvmCallbackBridgeJarContents = tasks.register<Sync>("prepareJvmCallbackBridgeJarContents") {
        dependsOn(compileJvmCallbackBridge)
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
        from(zipTree(callbackBridgeBaseJar))
        from(generatedJvmCallbackBridgeClassesDir)
        into(generatedJvmCallbackBridgeJarContentsDir)
    }

    val packageLwjglCallbackBridgeJar = tasks.register<Zip>("packageLwjglCallbackBridgeJar") {
        dependsOn(prepareJvmCallbackBridgeJarContents)
        from(generatedJvmCallbackBridgeJarContentsDir)
        destinationDirectory.set(packagedLwjglBridgeJarDir)
        archiveFileName.set("lwjgl-glfw-classes.jar")
        archiveExtension.set("jar")
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    val generateLwjglBridgeVersion = tasks.register<DefaultTask>("generateLwjglBridgeVersion") {
        val androidTemplate = callbackBridgeTemplatesDir.file("android/CallbackBridge.java.tmpl")
        val jvmTemplate = callbackBridgeTemplatesDir.file("jvm/CallbackBridge.java.tmpl")
        val outputFile = generatedLwjglBridgeAssetDir.map { it.file("version") }
        inputs.file(callbackBridgeBaseJar)
        inputs.file(androidTemplate)
        inputs.file(jvmTemplate)
        inputs.property("callbackBridgeContractHash", CallbackBridgeCodegen.contractHash)
        outputs.file(outputFile)
        doLast {
            val targetFile = outputFile.get().asFile
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(
                CallbackBridgeCodegen.fingerprint(
                    CallbackBridgeCodegen.contractHash,
                    androidTemplate.asFile.readText(StandardCharsets.UTF_8),
                    jvmTemplate.asFile.readText(StandardCharsets.UTF_8),
                    callbackBridgeBaseJar.asFile.length().toString(),
                    callbackBridgeBaseJar.asFile.lastModified().toString()
                ),
                StandardCharsets.UTF_8
            )
        }
    }

    val installLwjglBridgeAssets = tasks.register<Sync>("installLwjglBridgeAssets") {
        dependsOn(packageLwjglCallbackBridgeJar)
        dependsOn(generateLwjglBridgeVersion)
        from(packagedLwjglBridgeJarDir) {
            include("lwjgl-glfw-classes.jar")
        }
        from(generateLwjglBridgeVersion)
        into(generatedLwjglBridgeAssetDir)
    }

    val installPatchJars = tasks.register<Sync>("installPatchJars") {
        val patchProjectPaths = listOf(":patches:gdx-patch")
        dependsOn(patchProjectPaths.map { projectPath -> "$projectPath:jar" })
        patchProjectPaths.forEach { projectPath ->
            from(project(projectPath).layout.buildDirectory.dir("libs")) {
                include("*.jar")
            }
        }
        into(generatedRuntimeAssetsDir.map { it.dir("components/gdx_patch") })
    }

    val installBundledModJars = tasks.register<Sync>("installBundledModJars") {
        val bundledModProjectPaths = listOf(":mods:amethyst-runtime-compat", ":mods:ram-saver")
        dependsOn(bundledModProjectPaths.map { projectPath -> "$projectPath:jar" })
        bundledModProjectPaths.forEach { projectPath ->
            from(project(projectPath).layout.buildDirectory.dir("libs")) {
                include("*.jar")
            }
        }
        into(generatedRuntimeAssetsDir.map { it.dir("components/mods") })
    }

    val installLog4jRuntimeAssets = tasks.register<Sync>("installLog4jRuntimeAssets") {
        from(log4jRuntimeComponents) {
            include("log4j-api-*.jar")
            rename { "log4j-api.jar" }
        }
        from(log4jRuntimeComponents) {
            include("log4j-core-*.jar")
            rename { "log4j-core.jar" }
        }
        into(generatedRuntimeAssetsDir.map { it.dir("components/log4j_runtime") })
        doLast {
            val outputDir = generatedRuntimeAssetsDir.get().dir("components/log4j_runtime").asFile
            listOf(
                File(outputDir, "log4j-api.jar"),
                File(outputDir, "log4j-core.jar")
            ).forEach { jarFile ->
                if (!jarFile.isFile || jarFile.length() <= 0L) {
                    throw GradleException("Missing packaged Log4j runtime asset: ${jarFile.absolutePath}")
                }
            }
        }
    }

    val installRuntimePackAssets = tasks.register<Sync>("installRuntimePackAssets") {
        doFirst {
            val runtimePackFile = runtimePackZip.asFile
            if (!runtimePackFile.isFile) {
                throw GradleException(
                    "Missing runtime pack zip: ${runtimePackFile.absolutePath}. " +
                        "Expected runtime-pack/jre8-pojav.zip."
                )
            }
        }
        from(zipTree(runtimePackZip)) {
            exclude("bin-arm.tar.xz", "bin-x86.tar.xz", "bin-x86_64.tar.xz")
        }
        into(generatedRuntimeAssetsDir.map { it.dir("components/jre") })
    }

    registerRendererBackendImportTask()

    return listOf(
        generateAndroidCallbackBridgeSource,
        installBootBridgeJar,
        installLwjglBridgeAssets,
        installPatchJars,
        installBundledModJars,
        installLog4jRuntimeAssets,
        installRuntimePackAssets
    )
}

private fun Project.registerRendererBackendImportTask() {
    val rendererLibsSource = readGradleProperty("rendererLibsSource")
    val rendererBackendImportLibraries = listOf(
        "libc++_shared.so",
        "libEGL_mesa.so",
        "libglapi.so",
        "libglxshim.so",
        "liblinkerhook.so",
        "libmobileglues.so",
        "libspirv-cross-c-shared.so",
        "libzink_dri.so",
        "libcutils.so",
        "libvulkan_freedreno.so",
        "libVkLayer_khronos_timeline_semaphore.so",
        "libOSMesa.so"
    )

    tasks.register<DefaultTask>("importRendererBackendLibs") {
        group = "dev"
        description = "Import backend-native libraries from a renderer APK or an outer archive containing one."

        fun extractApk(source: File, tempDir: File): File {
            if (source.extension.equals("apk", ignoreCase = true)) {
                return source
            }
            ZipFile(source).use { zip ->
                val apkEntry = zip.entries().asSequence()
                    .firstOrNull { !it.isDirectory && it.name.endsWith(".apk", ignoreCase = true) }
                    ?: throw GradleException("No .apk found inside ${source.absolutePath}")
                val extractedApk = File(tempDir, apkEntry.name.substringAfterLast('/'))
                extractedApk.parentFile?.mkdirs()
                zip.getInputStream(apkEntry).use { input ->
                    FileOutputStream(extractedApk, false).use { output ->
                        input.copyTo(output)
                    }
                }
                return extractedApk
            }
        }

        doLast {
            if (rendererLibsSource.isBlank()) {
                throw GradleException("Missing -PrendererLibsSource=<apk-or-zip-path>")
            }
            val source = file(rendererLibsSource)
            if (!source.isFile) {
                throw GradleException("Renderer backend source does not exist: ${source.absolutePath}")
            }

            val tempDir = layout.buildDirectory.dir("tmp/importRendererBackendLibs").get().asFile
            tempDir.mkdirs()
            val apkFile = extractApk(source, tempDir)
            ZipFile(apkFile).use { apkZip ->
                for (abi in listOf("arm64-v8a")) {
                    val targetDir = file("src/main/jniLibs/$abi")
                    targetDir.mkdirs()
                    for (libraryName in rendererBackendImportLibraries) {
                        val entry = apkZip.getEntry("lib/$abi/$libraryName") ?: continue
                        val targetFile = File(targetDir, libraryName)
                        if (targetFile.exists()) {
                            logger.lifecycle("Skip existing renderer lib: ${targetFile.absolutePath}")
                            continue
                        }
                        apkZip.getInputStream(entry).use { input ->
                            FileOutputStream(targetFile, false).use { output ->
                                input.copyTo(output)
                            }
                        }
                        logger.lifecycle("Imported renderer lib: ${targetFile.absolutePath}")
                    }
                }

                val angleLicenseEntry = apkZip.getEntry("assets/licenses/ANGLE_LICENSE")
                if (angleLicenseEntry != null) {
                    val licenseFile = file("src/main/assets/licenses/ANGLE_LICENSE")
                    if (!licenseFile.exists()) {
                        licenseFile.parentFile?.mkdirs()
                        apkZip.getInputStream(angleLicenseEntry).use { input ->
                            FileOutputStream(licenseFile, false).use { output ->
                                input.copyTo(output)
                            }
                        }
                        logger.lifecycle("Imported ANGLE license: ${licenseFile.absolutePath}")
                    } else {
                        logger.lifecycle("Skip existing ANGLE license: ${licenseFile.absolutePath}")
                    }
                }
            }
        }
    }
}

private fun Project.registerAdbTasks(adb: Provider<String>, packageName: String) {
    val supportedLaunchModes = setOf("mts", "vanilla")
    val rawLaunchMode = readGradleProperty("launchMode", "mts")
    val launchMode = when (rawLaunchMode) {
        "mts_basemod" -> "mts"
        else -> rawLaunchMode
    }
    val forceJvmCrash = readGradleProperty("forceJvmCrash", "false")
    val deviceSerial = readGradleProperty("deviceSerial")
    val logsDir = readGradleProperty("logsDir")
    require(launchMode in supportedLaunchModes) {
        "Unsupported launchMode: $launchMode. Supported: ${supportedLaunchModes.joinToString(", ")}"
    }

    fun adbCommand(vararg args: String): List<String> = buildList {
        add(adb.get())
        if (deviceSerial.isNotEmpty()) {
            add("-s")
            add(deviceSerial)
        }
        addAll(args)
    }

    tasks.register<Exec>("stsStart") {
        group = "debug"
        description = "Start SlayTheAmethyst on a connected Android device."
        val remoteCommand = buildString {
            append("am start")
            append(" -n $(pm resolve-activity --components $packageName)")
            append(" --es io.stamethyst.debug_launch_mode $launchMode")
            append(" --ez io.stamethyst.debug_force_jvm_crash $forceJvmCrash")
        }
        commandLine(adbCommand("shell", "sh", "-c", remoteCommand))
        isIgnoreExitValue = true
    }

    tasks.register<Exec>("stsStop") {
        group = "debug"
        description = "Force stop SlayTheAmethyst on a connected Android device."
        commandLine(adbCommand("shell", "am", "force-stop", packageName))
        isIgnoreExitValue = true
    }

    tasks.register<StsPullLogsTask>("stsPullLogs") {
        group = "debug"
        description = "Export the same JVM log bundle as Settings > Share Logs."
        adbPath.set(adb)
        applicationId.set(packageName)
        this.deviceSerial.set(deviceSerial)
        this.logsDir.set(logsDir)
    }
}

private fun Project.androidComponents(): ApplicationAndroidComponentsExtension =
    extensions.getByType(ApplicationAndroidComponentsExtension::class.java)

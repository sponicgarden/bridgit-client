import java.util.Properties
import java.util.UUID

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

@org.gradle.work.DisableCachingByDefault(because = "Each build needs a fresh APK version")
abstract class AllocateApkVersion : DefaultTask() {
    @get:Internal abstract val counterFile: RegularFileProperty
    @get:OutputFile abstract val versionFile: RegularFileProperty

    init {
        outputs.upToDateWhen { false }
        notCompatibleWithConfigurationCache("APK allocation must use a unique invocation output")
    }

    @TaskAction
    fun allocate() {
        val command = listOf(
            "python3", project.rootProject.file("tools/allocate-apk-version.py").absolutePath,
            "--counter", counterFile.get().asFile.absolutePath,
            "--output", versionFile.get().asFile.absolutePath
        )
        val process = ProcessBuilder(command).directory(project.rootProject.projectDir)
            .redirectErrorStream(true).start()
        val result = process.inputStream.bufferedReader().use { it.readText() }
        check(process.waitFor() == 0) { "APK version allocation failed: $result" }
        logger.lifecycle(result.trim())
    }
}

// Release signing comes from keystore.properties (gitignored). It points at a
// keystore and password file kept outside the repo.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "cc.bridgit.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "cc.bridgit.app"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 29
        targetSdk = 35
        val allocated = Properties().apply { rootProject.file("apk-version.properties").inputStream().use { load(it) } }
        versionCode = allocated.getProperty("versionCode").toInt()
        versionName = allocated.getProperty("versionName")
        buildConfigField("int", "DEFAULT_PORT", "8734")
        buildConfigField("String", "GIT_COMMIT", "\"${gitCommit()}\"")
        buildConfigField("boolean", "DIRTY_BUILD", "${gitDirty()}")
        // FBK-14: the Bridgit team's feedback backend (source in /feedback). Empty turns uploads off.
        buildConfigField("String", "FEEDBACK_URL", "\"${(project.findProperty("bridgitFeedbackUrl") as String?) ?: "https://bridgit-feedback.bridgit-app.workers.dev"}\"")
        // Crash reports (D32) go only where the builder points them: -PbridgitSentryDsn, BRIDGIT_SENTRY_DSN,
        // or sentryDsn in keystore.properties. Empty (the default, and every fork) means no crash reports.
        val sentryDsn = (project.findProperty("bridgitSentryDsn") as String?)
            ?: System.getenv("BRIDGIT_SENTRY_DSN") ?: keystoreProps.getProperty("sentryDsn") ?: ""
        buildConfigField("String", "SENTRY_DSN", "\"${sentryDsn.trim()}\"")
        // The relay this build turns on with one tap: -PbridgitRelayUrl, BRIDGIT_RELAY_URL or relayUrl in
        // keystore.properties, as an https origin. Empty means the owner enters an address under Relay > Advanced.
        val relayUrl = (project.findProperty("bridgitRelayUrl") as String?)
            ?: System.getenv("BRIDGIT_RELAY_URL") ?: keystoreProps.getProperty("relayUrl") ?: ""
        buildConfigField("String", "RELAY_URL", "\"${relayUrl.trim().trimEnd('/')}\"")
        // Relay push wake-ups (FCM) use the builder's Firebase project the same way: -PbridgitFcmProjectId,
        // BRIDGIT_FCM_PROJECT_ID or fcmProjectId in keystore.properties (and ApiKey, SenderId). The App ID
        // is per package, so it is picked per variant below. Empty means no push: the relay then reaches
        // the phone only while its socket is up.
        for (name in listOf("ProjectId", "ApiKey", "SenderId")) {
            val value = (project.findProperty("bridgitFcm$name") as String?)
                ?: System.getenv("BRIDGIT_FCM_" + name.replace(Regex("(?<=.)([A-Z])"), "_$1").uppercase())
                ?: keystoreProps.getProperty("fcm$name") ?: ""
            buildConfigField("String", "FCM_" + name.replace(Regex("(?<=.)([A-Z])"), "_$1").uppercase(), "\"${value.trim()}\"")
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    flavorDimensions += "distribution"
    productFlavors {
        // PLAY_STORE: the Google Play build. No self-updater (Play updates it), no Health Connect, usage
        // access or notification reading (Play policy; see docs/release-checklist.md §3-4).
        create("private") { dimension="distribution"; buildConfigField("boolean","PRIVATE_CONTROL","true"); buildConfigField("boolean","PLAY_STORE","false") }
        create("play") { dimension="distribution"; applicationIdSuffix=".play"; buildConfigField("boolean","PRIVATE_CONTROL","false"); buildConfigField("boolean","PLAY_STORE","true") }
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                val pw = file(keystoreProps.getProperty("passwordFile")).readText().trim()
                storePassword = pw
                keyPassword = pw
                keyAlias = keystoreProps.getProperty("keyAlias")
            }
        }
    }

    buildTypes {
        debug {
            val suffix = bridgitProperty("ApplicationIdSuffix") ?: ".debug"
            // Test builds have one name pattern: cc.bridgit.app.test_claude_<feature>, e.g. .test_claude_cam.
            // Android package names allow no hyphens, so the words are joined with underscores.
            require(suffix == ".debug" || Regex("""\.test_claude_[a-z0-9_]+""").matches(suffix)) {
                "Test build suffix '$suffix' doesn't follow the naming rule. Use .test_claude_<feature>, e.g. " +
                    "-PbridgitApplicationIdSuffix=.test_claude_" +
                    suffix.trimStart('.').lowercase().replace(Regex("[^a-z0-9]+"), "_").removePrefix("test_claude_").trim('_')
            }
            applicationIdSuffix = suffix
            // Several test builds can sit on one phone: name each after its suffix ("bridgit test claude cam").
            if (suffix != ".debug") resValue("string", "app_name", "bridgit " + suffix.trimStart('.').replace('_', ' '))
            // Debug builds listen on 8735 so they never clash with a release build on 8734.
            buildConfigField("int", "DEFAULT_PORT", bridgitProperty("DebugPort") ?: "8735")
        }
        release {
            isMinifyEnabled = false
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // Phones are ARM, so x86/x86_64 native libraries (emulator only) are left out to keep the APK
            // smaller. There is no APK size limit. armeabi-v7a stays for 32-bit Android 10 phones.
            // Debug builds keep every ABI so they still run on any emulator.
            ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/helperAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/demoAssets"))
    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/samplesAssets"))
    // Only the instrumentation APK receives the external catalog and fresh-session examples.
    sourceSets["androidTest"].assets.srcDir(layout.buildDirectory.dir("generated/appletDeviceFixtures"))
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/builderCatalog"))
    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
        // ApiDocsTest writes build/apidocs/api.html for this version (tools/publish-apk.sh passes the APK's).
        unitTests.all {
            // Robolectric keeps each SDK it loads (34, 30, 29...) for the whole run; the 512 MB default runs out of heap
            // when a later class first loads another SDK (SecretViewsTest's API 30 cases).
            it.maxHeapSize = "2g"
            it.systemProperty("bridgit.applet.output", bridgitProperty("AppletOutputDir") ?: "build/applet-authoring")
            it.systemProperty("bridgit.applet.file", bridgitProperty("AppletPackageFile") ?: "")
            it.systemProperty("bridgit.version", bridgitProperty("DocsVersion") ?: defaultConfig.versionName!!)
            // LearnTest: -PbridgitLearnRefresh=1 (tools/refresh-learn.sh) rewrites the website's /learn/ copy instead of failing on it.
            it.systemProperty("bridgit.learn.refresh", bridgitProperty("LearnRefresh") ?: "0")
        }
    }
    packaging {
        // Wake words: Vosk's x86 builds only serve emulators (about 20 MB); there wake words report an error instead.
        jniLibs { excludes += setOf("lib/x86/libvosk.so", "lib/x86_64/libvosk.so") }
        // ONNX Runtime's native libraries (31 MB) download on first use of an on-phone engine (vision/OrtLoader).
        jniLibs { excludes += setOf("**/libonnxruntime.so", "**/libonnxruntime4j_jni.so") }
        resources {
            excludes += setOf(
                "META-INF/versions/**/OSGI-INF/MANIFEST.MF", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties",
                "META-INF/*.kotlin_module", "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*"
            )
        }
    }
}

// GET /v1/tools/bridgit (and the old /v1/tools/bridget) serves the helper script. tools/bridgit is the single
// source of truth; this copies it into the APK's assets at build time.
// Canonical website recipe definitions feed both public pages and the installed discovery index.
// Chunked escaped literals avoid JVM constant-size limits as the recipe examples grow.
val generateBuilderCatalog by tasks.registering {
    val source = rootProject.file("site-content/content/builder/recipes.json")
    val target = layout.buildDirectory.file("generated/builderCatalog/cc/bridgit/app/api/BuilderRecipeIndex.kt")
    inputs.file(source)
    outputs.file(target)
    doLast {
        fun literal(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$")
            .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
        val parts = source.readText().chunked(12000).joinToString(",\n") { literal(it) }
        target.get().asFile.apply { parentFile.mkdirs(); writeText("package cc.bridgit.app.api\ninternal object BuilderRecipeIndex { val json = listOf(\n$parts\n).joinToString(\"\") }\n") }
    }
}
tasks.named("preBuild").configure { dependsOn(generateBuilderCatalog) }

val prepareAppletDeviceFixtures by tasks.registering(Sync::class) {
    into(layout.buildDirectory.dir("generated/appletDeviceFixtures"))
    from(rootProject.file("applets")) { include("*/applet.json"); into("catalog") }
    from(rootProject.file("applets/dog-monitor/editions/1.5.0")) { include("applet.json"); into("previous-dog") }
    from(rootProject.file("site-content/public/recipes")) { include("*/applet.json"); into("catalog") }
    val trial = rootProject.file("docs/applet-authoring-trials/2026-11-06")
    for ((author, edition) in mapOf("sol" to "sol", "terra" to "terra/reviewed", "luna" to "luna/reviewed")) {
        from(trial.resolve(edition)) { include("applet.json"); into("authors/$author") }
    }
}
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("AndroidTestAssets") }
    .configureEach { dependsOn(prepareAppletDeviceFixtures) }

val copyHelper by tasks.registering(Copy::class) {
    from(rootProject.file("tools/bridgit"))
    into(layout.buildDirectory.dir("generated/helperAssets"))
    rename { "bridgit-helper.py" }
}
// GET /v1/demo serves the first-run tour. site-content/public/demo/SKILL.md is the single source (the website
// hosts the same file); this copies it into the APK's assets at build time.
val copyDemo by tasks.registering(Copy::class) {
    from(rootProject.file("site-content/public/demo/SKILL.md"))
    into(layout.buildDirectory.dir("generated/demoAssets"))
    rename { "demo-first-run.md" }
}
// GET /v1/samples serves site-content/public/samples/samples.json (built from site-content/content/samples.json and the
// card pages by site-content/scripts/samples.mjs; the website hosts the same file).
val copySamples by tasks.registering(Copy::class) {
    from(rootProject.file("site-content/public/samples/samples.json"))
    into(layout.buildDirectory.dir("generated/samplesAssets"))
}
val writeProvenance by tasks.registering {
    val target = layout.buildDirectory.file("generated/helperAssets/build-provenance.json")
    val commit = providers.exec { commandLine("git", "rev-parse", "HEAD") }.standardOutput.asText.get().trim()
    val dirty = gitDirty()
    inputs.property("commit", commit)
    inputs.property("dirty", dirty)
    outputs.file(target)
    doLast {
        target.get().asFile.apply { parentFile.mkdirs(); writeText("""{"git_commit":"$commit","dirty_build":$dirty}""" + "\n") }
    }
}
tasks.named("preBuild") { dependsOn(copyHelper, copyDemo, copySamples, writeProvenance) }

/** -Pbridgit<Name>, or the deprecated -Pbridget<Name> spelling older scripts still pass. */
fun bridgitProperty(name: String): String? =
    (project.findProperty("bridgit$name") ?: project.findProperty("bridget$name")) as String?

fun gitCommit(): String = runCatching {
    providers.exec { commandLine("git", "rev-parse", "--short=12", "HEAD") }.standardOutput.asText.get().trim()
}.getOrDefault("unknown")

fun gitCommitCount(): Int =
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()

fun gitDirty(): Boolean = runCatching {
    providers.exec { commandLine("git", "status", "--porcelain") }.standardOutput.asText.get().isNotBlank()
}.getOrDefault(true)

// Firebase App IDs are bound to a package name: fcmAppId.<applicationId> in keystore.properties
// (or -PbridgitFcmAppId.<applicationId>), e.g. fcmAppId.cc.bridgit.app.play=1:...:android:...
androidComponents {
    onVariants { variant ->
        val id = variant.applicationId.get()
        val appId = (project.findProperty("bridgitFcmAppId.$id") as String?) ?: keystoreProps.getProperty("fcmAppId.$id") ?: ""
        variant.buildConfigFields.put("FCM_APP_ID", com.android.build.api.variant.BuildConfigField("String", "\"${appId.trim()}\"", "Firebase App ID for $id"))
    }
}

// An immutable per-invocation file prevents another build in the same checkout
// from changing values while AGP reads this invocation’s manifest providers.
val apkVersionInvocation = UUID.randomUUID().toString()

androidComponents {
    onVariants(selector().all()) { variant ->
        val allocation = tasks.register<AllocateApkVersion>(
            "allocate${variant.name.replaceFirstChar { it.uppercaseChar() }}ApkVersion"
        ) {
            counterFile.set(rootProject.layout.projectDirectory.file("apk-version.properties"))
            versionFile.set(layout.buildDirectory.file("generated/apk-version/${variant.name}/$apkVersionInvocation/version.properties"))
        }
        val version = allocation.flatMap { it.versionFile }.map { file ->
            Properties().apply { file.asFile.inputStream().use { load(it) } }
        }
        variant.outputs.forEach { output ->
            output.versionCode.set(version.map { it.getProperty("versionCode").toInt() })
            output.versionName.set(version.map { it.getProperty("versionName") })
        }
    }
}

dependencies {
    implementation("com.github.mwiede:jsch:2.28.8")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.5.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.86")
    implementation("com.google.firebase:firebase-messaging:24.1.0")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation("com.google.mlkit:translate:17.0.3")
    // 1.5+: its native helpers meet Android's 16 KB page rules (tools/check-16kb.py); 1.4.x failed the RELRO check.
    val camerax = "1.5.3"
    val ktor = "2.3.12"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    // Applets › Portals: an origin-checked message bridge for portal pages (PortalBridge).
    implementation("androidx.webkit:webkit:1.12.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax") // Bridgit Portal: scanning another phone's pairing code
    implementation("com.google.mlkit:text-recognition:16.0.1")
    // Face finding for POST /v1/images/find: the model comes through Google Play services, so the APK stays small.
    implementation("com.google.android.gms:play-services-mlkit-face-detection:17.1.0")
    // Exercise check applet: body points for POST /v1/camera/pose/start (bundled base model, offline).
    implementation("com.google.mlkit:pose-detection:18.0.0-beta5")
    // Face embeddings for the kitchen applet's People (watch option people): TFLite runs from Google Play services,
    // so only the small Java API ships in the APK; the model itself is downloaded on demand (vision/FaceEmbedder).
    implementation("com.google.android.gms:play-services-tflite-java:16.4.0")
    implementation("io.ktor:ktor-server-core:$ktor")
    implementation("io.ktor:ktor-server-cio:$ktor")
    implementation("com.google.zxing:core:3.5.3")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.fragment:fragment-ktx:1.8.5")
    implementation("androidx.biometric:biometric:1.1.0") // optional fingerprint check (Settings › Security)
    implementation("androidx.navigation:navigation-fragment-ktx:2.8.4")
    implementation("androidx.navigation:navigation-ui-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    // Email alerts: digests and retries survive restarts and wait for a network.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Crash reports (D32). Core only: no NDK, replay or Gradle plugin, so nothing is uploaded at build time.
    implementation("io.sentry:sentry-android-core:7.18.1")
    // Wake words (CAP-47): offline keyword spotting. The English model is downloaded on first use, not bundled.
    implementation("com.alphacephei:vosk-android:0.3.75")
    implementation("net.java.dev.jna:jna:5.18.1@aar")

    // ADB setup (8.27): Bridgit pairs with this device's own Wireless debugging and runs shell commands as uid shell.
    // Apache-2.0 option of the dual licence. Conscrypt supplies the TLS that pairing needs.
    // main already ships the newer BouncyCastle (bcprov-jdk18on); the same classes twice fail the build.
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") { exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18") }
    // 2.9.0+: libconscrypt_jni.so meets Android's 16 KB page rules; 2.5.3 failed the RELRO check (tools/check-16kb.py).
    implementation("org.conscrypt:conscrypt-android:2.9.0")
    implementation("com.github.MuntashirAkon:sun-security-android:1.1")

    // ONNX Runtime (MIT): the opt-in on-phone vision engines (vision/OnDeviceVision). Only its Java classes ship;
    // its native libraries and the models download on first use (packaging excludes below, vision/OrtLoader).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    // Debug builds only: MediaPipe for the on-device detection benchmark (app/src/debug/.../DetectBench.kt).
    debugImplementation("com.google.mediapipe:tasks-vision:0.10.26")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("junit:junit:4.13.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

// Production builds reach devices only through tools/publish-apk.sh, which passes -PbridgitPublish=1.
// A session that sideloaded a local release build put an unpublished 1.8.683 on every test device (2 Nov 2026).
// Release unit tests still run freely; only packaging an APK or bundle is stopped.
gradle.taskGraph.whenReady {
    val packaging = Regex("""(assemble|package|bundle|install)\w*Release""")
    val hit = allTasks.filter { it.project == project && packaging.matches(it.name) }.map { it.name }
    if (hit.isNotEmpty() && bridgitProperty("Publish") != "1") throw GradleException(
        "${hit.first()} builds a production APK, and those come only from tools/publish-apk.sh. " +
            "For a device test, build cc.bridgit.app.test_claude_<feature>: " +
            "./gradlew :app:assemblePrivateDebug -PbridgitApplicationIdSuffix=.test_claude_<feature> -PbridgitDebugPort=<free port>"
    )
}

// Maintainer inputs for the offline JVM validator. The model/user receives only the finished kit.
val authoringLibraries = configurations.detachedConfiguration(
    dependencies.create("org.jetbrains.kotlin:kotlin-stdlib:2.0.21"),
    dependencies.create("org.json:json:20240303")
)
tasks.register<Sync>("prepareAppletAuthoringKit") {
    dependsOn("compilePrivateDebugUnitTestKotlin", "bundlePrivateDebugClassesToRuntimeJar")
    into(layout.buildDirectory.dir("authoring-kit-inputs"))
    from(authoringLibraries) { into("libraries") }
    from(layout.buildDirectory.dir("tmp/kotlin-classes/privateDebugUnitTest")) {
        include("cc/bridgit/app/applets/OfflineAppletValidator*.class")
        into("cli")
    }
    from(layout.buildDirectory.file("intermediates/runtime_app_classes_jar/privateDebug/bundlePrivateDebugClassesToRuntimeJar/classes.jar"))
}

plugins {
    kotlin("jvm") version "2.4.10"
    kotlin("plugin.allopen") version "2.4.10"
    id("io.quarkus")
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-jdbc-mysql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-scheduler")
    implementation("io.quarkus:quarkus-mailer")
    implementation("dnsjava:dnsjava:3.6.5")
    implementation("com.squareup.okhttp3:okhttp:5.5.0")
    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.rest-assured:rest-assured")
}

val flutterResourceRoot = layout.buildDirectory.dir("generated/flutter-web")
val flutterWebOutput = flutterResourceRoot.map { it.dir("META-INF/resources") }
val includeFlutterDevCredentials = gradle.startParameter.taskNames.any { it.contains("quarkusDev", ignoreCase = true) }
val buildFlutterWeb by tasks.registering(Exec::class) {
    val flutterSource = layout.projectDirectory.dir("admin-flutter")
    workingDir(flutterSource)
    commandLine(
        "flutter", "build", "web", "--release",
        "--dart-define=GEEK_MONITOR_DEV_CREDENTIALS=$includeFlutterDevCredentials",
        "--output", flutterWebOutput.get().asFile.absolutePath,
    )
    inputs.dir(flutterSource.dir("lib"))
    inputs.dir(flutterSource.dir("web"))
    inputs.file(flutterSource.file("pubspec.yaml"))
    inputs.file(flutterSource.file("pubspec.lock"))
    inputs.property("devCredentials", includeFlutterDevCredentials)
    outputs.dir(flutterResourceRoot)
}

sourceSets.main {
    resources.srcDir(flutterResourceRoot)
}

tasks.named("processResources") {
    dependsOn(buildFlutterWeb)
}

group = "com.geekcorelab"
version = "1.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
        javaParameters = true
    }
}

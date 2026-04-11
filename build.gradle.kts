plugins {
    kotlin("jvm") version "1.9.25"
    kotlin("plugin.spring") version "1.9.25"
    id("org.springframework.boot") version "3.3.0"
    id("io.spring.dependency-management") version "1.1.5"
}

group = "com.juujarvis"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-websocket")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Anthropic Claude SDK
    implementation("com.anthropic:anthropic-java:2.18.0")

    // SQLite (for iMessage chat.db)
    implementation("org.xerial:sqlite-jdbc:3.45.1.0")

    // Google Calendar API
    implementation("com.google.api-client:google-api-client:2.7.2")
    implementation("com.google.oauth-client:google-oauth-client-jetty:1.36.0")
    implementation("com.google.apis:google-api-services-calendar:v3-rev20241101-2.0.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.named<org.springframework.boot.gradle.tasks.run.BootRun>("bootRun") {
    args("--spring.profiles.active=local")
}

tasks.register("generateBuildInfo") {
    val outputDir = layout.buildDirectory.dir("resources/main")
    outputs.dir(outputDir)
    outputs.upToDateWhen { false }
    doLast {
        val file = outputDir.get().file("build-info.json").asFile
        file.parentFile.mkdirs()
        val timestamp = System.currentTimeMillis().toString()
        val gitHash = try {
            providers.exec { commandLine("git", "rev-parse", "--short", "HEAD") }
                .standardOutput.asText.get().trim()
        } catch (_: Exception) { "unknown" }
        file.writeText("""{"buildTime":"$timestamp","gitHash":"$gitHash","version":"$version"}""")
    }
}

tasks.named("processResources") {
    dependsOn("generateBuildInfo")
}

tasks.named("classes") {
    dependsOn("generateBuildInfo")
}

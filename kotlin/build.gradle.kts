plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("org.graalvm.buildtools.native") version "0.10.2"
}

group = "app.muka.project"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}

tasks.test {
    useJUnit()
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("app.muka.project.kquickjs.MQuickJS")
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = "app.muka.project.kquickjs.MQuickJS"
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
}

graalvmNative {
    binaries {
        named("main") {
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(17))
                vendor.set(JvmVendorSpec.matching("GraalVM Community"))
            })
            
            buildArgs.addAll(
                "--no-fallback",
                "--enable-http",
                "--enable-https",
                "-H:+ReportExceptionStackTraces",
                "--initialize-at-build-time=kotlin",
                "--initialize-at-run-time=app.muka.project.kquickjs"
            )
            
            if (org.gradle.internal.os.OperatingSystem.current().isWindows) {
                buildArgs.add("--enable-url-protocols=http,https")
            }
        }
    }
    
    binaries.all {
        buildArgs.add("-H:ConfigurationFileDirectories=${projectDir}/src/main/resources/META-INF/native-image")
    }
}

tasks.withType<org.graalvm.buildtools.gradle.tasks.BuildNativeImageTask> {
    classpathJar.set(tasks.jar.flatMap { it.archiveFile })
}

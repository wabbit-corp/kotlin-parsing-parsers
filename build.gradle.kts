import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

repositories {
    mavenCentral()

    maven("https://jitpack.io")
}

group   = "one.wabbit"
version = "2.0.0"

plugins {
    kotlin("jvm") version "2.1.20"

    id("maven-publish")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "one.wabbit"
            artifactId = "kotlin-parsing-parsers"
            version = "2.0.0"
            from(components["java"])
        }
    }
}

dependencies {
    implementation("com.github.wabbit-corp:kotlin-data:3.0.0")
    implementation("com.github.wabbit-corp:kotlin-data-need:1.2.0")
    implementation("com.github.wabbit-corp:kotlin-data-ref:1.1.1")
    implementation("com.github.wabbit-corp:kotlin-parsing-charset:1.3.0")
    implementation("com.github.wabbit-corp:kotlin-parsing-charinput:1.2.0")
    implementation("com.github.wabbit-corp:kotlin-java-escape:1.0.1")
    testImplementation("com.github.wabbit-corp:kotlin-random-gen:2.0.0")

    testImplementation(kotlin("test"))

    implementation("org.ow2.asm:asm:9.7.1")
    implementation("org.ow2.asm:asm-tree:9.7.1")
    implementation("org.ow2.asm:asm-util:9.7.1")
    implementation("org.ow2.asm:asm-analysis:9.7.1")
}

java {
    targetCompatibility = JavaVersion.toVersion(21)
    sourceCompatibility = JavaVersion.toVersion(21)
}

tasks {
    withType<Test> {
        jvmArgs("-ea")

    }
    withType<JavaCompile> {
        options.encoding = Charsets.UTF_8.name()
    }
    withType<Javadoc> {
        options.encoding = Charsets.UTF_8.name()
    }

    withType<KotlinCompile> {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xcontext-receivers")
        }
    }

    jar {
        setProperty("zip64", true)

    }
}

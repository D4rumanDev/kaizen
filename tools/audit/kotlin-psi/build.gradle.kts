plugins {
    kotlin("jvm") version "2.4.10"
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.4.10")
    implementation("com.google.code.gson:gson:2.13.2")
}

application { mainClass.set("CodeGraphPsiKt") }

tasks.register<JavaExec>("analyze") {
    group = "code analysis"
    description = "Parse Kotlin source files with Kotlin PSI and emit JSON."
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("CodeGraphPsiKt")
    val root = providers.gradleProperty("projectRoot")
    val output = providers.gradleProperty("outputFile")
    doFirst {
        require(root.isPresent) { "Pass -PprojectRoot=<project directory>" }
        require(output.isPresent) { "Pass -PoutputFile=<JSON output>" }
        args(root.get(), output.get())
    }
}

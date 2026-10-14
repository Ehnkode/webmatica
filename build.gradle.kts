plugins {
    id("dev.kikugie.loom-back-compat")
}

val minecraftVersion = sc.current.version
val requiredJava = sc.properties.get<Int>("java.version")
group = sc.properties.get<String>("mod.group")
version = sc.properties.get<String>("mod.version")
base.archivesName = "webmatica"

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")
    // Mojang mappings and remapping up to 1.21.11; 26.1+ is unobfuscated and needs none
    loomx.applyMojangMappings()
    modImplementation("net.fabricmc:fabric-loader:${sc.properties.get<String>("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${sc.properties.get<String>("deps.fabric_api")}")
}

loom {
    runConfigs.named("client") {
        runDirectory = rootProject.file("run/${project.name}")
        // Game tests (AutoTest): ./gradlew runClient -Pautotest=all -Pautoquit
        if (project.hasProperty("autotest")) {
            vmArg("-Dwebmatica.autotest=${project.property("autotest")}")
            vmArg("-Dwebmatica.autotest.quit=${project.hasProperty("autoquit")}")
        }
    }
}

java {
    withSourcesJar()
    toolchain.languageVersion = JavaLanguageVersion.of(requiredJava)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = requiredJava
}

tasks.processResources {
    val props = mapOf(
        "version" to project.version.toString(),
        "minecraft" to sc.properties.get<String>("mc.range"),
        "java" to requiredJava.toString(),
    )
    inputs.properties(props)
    filesMatching("fabric.mod.json") { expand(props) }
    // Reuse the viewer's translation table for in-game messages
    from(rootProject.file("viewer/static/i18n")) {
        into("assets/webmatica/i18n")
    }
    // The viewer ships inside the jar; the mod copies it next to the game (ViewerLauncher)
    from(rootProject.projectDir) {
        include("viewer/**", "requirements.txt", "start-viewer.bat", "start-viewer.sh", "LICENSE")
        exclude("**/__pycache__/**", "**/*.pyc", "viewer/tests/**", "viewer/assets/**")
        into("webmatica-viewer")
    }
}

tasks.withType<Jar>().configureEach {
    from(rootProject.file("LICENSE")) { rename { "LICENSE_webmatica" } }
}

loomx.modJar.configure {
    archiveFileName = "webmatica-${project.version}+mc$minecraftVersion.jar"
}

tasks.register<Copy>("buildAndCollect") {
    group = "build"
    description = "Builds this version and copies its jar to the root build/libs."
    dependsOn(tasks.build)
    from(loomx.modJar.flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.dir("libs"))
}

plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.11"

tasks.register("buildAll") {
    group = "build"
    description = "Builds every Minecraft version and collects the jars in build/libs."
    dependsOn(stonecutter.tasks.named("buildAndCollect"))
}

rootProject.name = "devpath-sandbox-svc"

// CI checks out the exact shared commit into .ci/devpath-shared. This keeps the
// normal `./gradlew build` command deterministic without resolving a mutable
// SNAPSHOT. Local callers may point at a verified shared worktree explicitly.
val sharedSource = providers.gradleProperty("devpathSharedDir").orNull
    ?: providers.environmentVariable("DEVPATH_SHARED_DIR").orNull
    ?: file(".ci/devpath-shared").takeIf { it.isDirectory }?.path
if (!sharedSource.isNullOrBlank()) {
    includeBuild(sharedSource)
}


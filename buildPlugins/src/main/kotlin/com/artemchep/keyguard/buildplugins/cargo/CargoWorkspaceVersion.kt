package com.artemchep.keyguard.buildplugins.cargo

import com.artemchep.keyguard.buildplugins.version.requireAppVersionName
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val CARGO_MANIFEST = "Cargo.toml"
private const val CARGO_LOCK = "Cargo.lock"

private val versionField = Regex("""(?m)^([ \t]*version[ \t]*=[ \t]*)["']([^"'\r\n]+)["']""")
private val inheritedVersionField = Regex(
    """(?m)^[ \t]*version[ \t]*(?:\.[ \t]*workspace[ \t]*=[ \t]*true|""" +
        """=[ \t]*\{[ \t]*workspace[ \t]*=[ \t]*true[ \t]*\})[ \t]*(?:#.*)?$""",
)
private val nameField = Regex("""(?m)^[ \t]*name[ \t]*=[ \t]*"([^"\r\n]+)"[ \t]*(?:#.*)?$""")

// Cargo writes lockfiles in a fixed format, so line patterns are enough to read them.
// A package block ends at the next table, such as [[patch.unused]].
private val lockPackage = Regex("""(?m)^\[\[package\]\][^\r\n]*(?:\r?\n(?!\[)[^\r\n]*)*""")
private val lockSource = Regex("""(?m)^source = """)

/**
 * Returns the root Cargo workspace files whose versions differ from [version], keyed by
 * their path relative to [rootDir]. Reads and validates every file; writes nothing.
 */
fun cargoWorkspaceVersionUpdates(
    rootDir: File,
    version: String,
): Map<String, String> {
    requireAppVersionName(version)
    val manifest = rootDir.resolve(CARGO_MANIFEST).readText()
    val names = cargoWorkspaceMembers(rootDir, manifest)
        .mapTo(mutableSetOf()) { member -> cargoMemberName(rootDir, member) }
    val lock = rootDir.resolve(CARGO_LOCK).readText()

    val updates = linkedMapOf<String, String>()
    val table = cargoTable(manifest, CARGO_MANIFEST, "workspace.package")
    val updatedManifest =
        manifest.replaceRange(table.range, replaceVersion(table.value, CARGO_MANIFEST, version))
    if (updatedManifest != manifest) updates[CARGO_MANIFEST] = updatedManifest
    val updatedLock = updateCargoLockVersions(lock, names, version)
    if (updatedLock != lock) updates[CARGO_LOCK] = updatedLock
    return updates
}

/** Writes each update to a temporary file next to its target, then moves it into place. */
fun writeCargoWorkspaceVersionUpdates(
    rootDir: File,
    updates: Map<String, String>,
) {
    updates.forEach { (path, content) ->
        val target = rootDir.resolve(path).toPath()
        val temp = target.resolveSibling("${target.fileName}.tmp")
        Files.writeString(temp, content)
        Files.move(temp, target, StandardCopyOption.ATOMIC_MOVE)
    }
}

private fun updateCargoLockVersions(
    source: String,
    names: Set<String>,
    version: String,
): String {
    // Edit only workspace packages; registry and git packages stay byte-for-byte intact.
    val oldVersions = mutableMapOf<String, String?>()
    val updated = lockPackage.replace(source) { block ->
        val name = nameField.find(block.value)?.groupValues?.get(1)
        if (name == null || name !in names || lockSource.containsMatchIn(block.value)) {
            return@replace block.value
        }
        oldVersions[name] = versionField.find(block.value)?.groupValues?.get(2)
        replaceVersion(block.value, CARGO_LOCK, version)
    }
    val missing = names - oldVersions.keys
    check(missing.isEmpty()) {
        "$CARGO_LOCK: missing workspace packages: ${missing.sorted().joinToString()}."
    }
    // Cargo qualifies dependency references with a version when names collide.
    return oldVersions.entries.fold(updated) { text, (name, old) ->
        text.replace("\"$name $old\"", "\"$name $version\"")
    }
}

private fun cargoWorkspaceMembers(
    rootDir: File,
    manifest: String,
): List<File> {
    val workspace = cargoTable(manifest, CARGO_MANIFEST, "workspace").value
        .lineSequence()
        .joinToString("\n") { line -> line.substringBefore('#') }
    val members = Regex("""(?s)\bmembers\s*=\s*\[(.*?)\]""").find(workspace)
        ?: error("$CARGO_MANIFEST: [workspace] has no members.")
    return Regex("\"([^\"]+)\"")
        .findAll(members.groupValues[1])
        .flatMap { pattern -> expandMemberPattern(rootDir, pattern.groupValues[1]) }
        .toList()
}

private fun expandMemberPattern(
    rootDir: File,
    pattern: String,
): List<File> {
    // The root workspace lists plain paths and `dir/*`. Cargo matches directories only,
    // so files such as .DS_Store are skipped.
    val base = rootDir.resolve(pattern.removeSuffix("/*"))
    val candidates = if (pattern.endsWith("/*")) base.listFiles().orEmpty().toList() else listOf(base)
    val matches = candidates.filter { it.isDirectory }
    check(matches.isNotEmpty()) { "$CARGO_MANIFEST: no members match '$pattern'." }
    return matches
}

private fun cargoMemberName(
    rootDir: File,
    member: File,
): String {
    val manifest = member.resolve(CARGO_MANIFEST)
    val path = manifest.relativeTo(rootDir).invariantSeparatorsPath
    check(manifest.isFile) { "$path is missing." }
    val table = cargoTable(manifest.readText(), path, "package").value
    check(inheritedVersionField.containsMatchIn(table)) {
        "$path must use version.workspace = true."
    }
    return nameField.find(table)?.groupValues?.get(1)
        ?: error("$path has no package name.")
}

private fun cargoTable(
    source: String,
    path: String,
    name: String,
): MatchResult {
    val tables = Regex("""(?ms)^\[${Regex.escape(name)}\][^\r\n]*$.*?(?=^\[|\z)""")
        .findAll(source)
        .toList()
    check(tables.size == 1) { "$path: expected one [$name] table, found ${tables.size}." }
    return tables.single()
}

private fun replaceVersion(
    source: String,
    path: String,
    version: String,
): String {
    val fields = versionField.findAll(source).toList()
    check(fields.size == 1) { "$path: expected one version field, found ${fields.size}." }
    val field = fields.single()
    return source.replaceRange(field.range, field.groupValues[1] + "\"$version\"")
}

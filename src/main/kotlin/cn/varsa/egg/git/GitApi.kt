package cn.varsa.egg.git

import cn.varsa.cli.core.CliException
import cn.varsa.egg.runtime.ProcessRunner
import cn.varsa.egg.runtime.runCaptureOrThrow
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

interface GitApi {
  fun makeWorktree(workingDir: Path, repoName: String, branch: String, subdir: String?)
  fun generateCommitMessage(workingDir: Path): String
  fun changedPaths(workingDir: Path, staged: Boolean, range: String?): String
  fun localIgnore(workingDir: Path, pattern: String): String
  fun localIgnoreEclipse(workingDir: Path): String
  fun aheadFeature(workingDir: Path): String
  fun aheadMaster(workingDir: Path): String
  fun behindMaster(workingDir: Path): String
  fun updateMaster(workingDir: Path)
  fun cloneBranch(workingDir: Path, repo: String, branch: String)
  fun cloneKnime(workingDir: Path, repo: String)
  fun reword(workingDir: Path)
}

class GitCliApi(private val processRunner: ProcessRunner) : GitApi {
  override fun makeWorktree(workingDir: Path, repoName: String, branch: String, subdir: String?) {
    val repoPath = Path.of(System.getProperty("user.home"), "repos", repoName)
    val branchDirName = branch.replace('/', '_')
    val worktreePath = workingDir.resolve("${repoName}_$branchDirName").normalize()

    val hasLocalBranch = processRunner.run(repoPath, listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/$branch")).exitCode == 0
    val hasRemoteBranch = processRunner.run(repoPath, listOf("git", "show-ref", "--verify", "--quiet", "refs/remotes/origin/$branch")).exitCode == 0

    when {
      hasLocalBranch -> processRunner.runCaptureOrThrow(repoPath, listOf("git", "worktree", "add", worktreePath.toString(), branch))
      hasRemoteBranch -> processRunner.runCaptureOrThrow(repoPath, listOf("git", "worktree", "add", "-b", branch, worktreePath.toString(), "origin/$branch"))
      else -> {
        val baseRef = defaultBaseRef(repoPath)
        processRunner.runCaptureOrThrow(repoPath, listOf("git", "worktree", "add", "-b", branch, worktreePath.toString(), baseRef))
      }
    }

    if (!subdir.isNullOrBlank()) {
      processRunner.runCaptureOrThrow(worktreePath, listOf("git", "sparse-checkout", "init", "--cone"))
      processRunner.runCaptureOrThrow(worktreePath, listOf("git", "sparse-checkout", "set", subdir))
    }
  }

  override fun generateCommitMessage(workingDir: Path): String {
    val inRepo = processRunner.run(workingDir, listOf("git", "rev-parse", "--is-inside-work-tree")).exitCode == 0
    if (!inRepo) throw CliException("egg git generate-commit-message: not inside a git repository", 1)

    val hasStagedChanges = processRunner.run(workingDir, listOf("git", "diff", "--cached", "--quiet")).exitCode != 0
    if (!hasStagedChanges) throw CliException("egg git generate-commit-message: no staged changes found", 1)

    val diff = processRunner.runCaptureOrThrow(workingDir, listOf("git", "diff", "--cached", "--binary"))
    val diffFile = Files.createTempFile("egg-commit-diff", ".patch")
    Files.writeString(diffFile, diff)

    val prompt = """
      Draft a git commit message for the attached staged diff.

      Requirements:
      - Use imperative mood.
      - Keep the subject line under 72 characters.
      - Add a short body only if it adds useful context.
      - Return ONLY the commit message text, with no Markdown or explanation.
    """.trimIndent()

    return try {
      val jsonEvents = processRunner.runCaptureOrThrow(
        workingDir,
        listOf("opencode", "run", "--model", "github-copilot/gemini-3-flash-preview", "--format", "json", "--file", diffFile.toString(), "--", prompt)
      )
      extractCommitMessage(jsonEvents)
    } finally {
      Files.deleteIfExists(diffFile)
    }
  }

  override fun changedPaths(workingDir: Path, staged: Boolean, range: String?): String {
    val repo = processRunner.runCaptureOrThrow(workingDir, listOf("git", "rev-parse", "--show-toplevel"))
    val diffCommand = when {
      !range.isNullOrBlank() -> listOf("git", "diff", "--name-only", "-z", range)
      staged -> listOf("git", "diff", "--name-only", "-z", "--cached")
      else -> listOf("git", "diff", "--name-only", "-z")
    }
    val changed = processRunner.runCaptureOrThrow(workingDir, diffCommand)
    if (changed.isBlank()) return ""
    return changed
      .split('\u0000')
      .asSequence()
      .filter { it.isNotBlank() }
      .joinToString("\n") { "$repo/$it" }
  }

  override fun localIgnore(workingDir: Path, pattern: String): String {
    if (pattern.isBlank()) throw CliException("Usage: egg git local-ignore <pattern>", 1)

    val ignoreFile = resolveGlobalIgnoreFile(workingDir)
    Files.createDirectories(ignoreFile.parent)
    if (!Files.exists(ignoreFile)) Files.createFile(ignoreFile)

    val normalized = normalizeIgnorePattern(pattern)
    val existing = Files.readAllLines(ignoreFile)
    val added = !existing.contains(normalized)
    if (added) {
      val prefix = if (existing.isEmpty()) "" else "\n"
      Files.writeString(ignoreFile, "$prefix$normalized", java.nio.file.StandardOpenOption.APPEND)
    }

    val tracked = runOrEmpty(workingDir, listOf("git", "ls-files")).lineSequence().filter { it.isNotBlank() }
    val matcher = buildMatcher(pattern)
    var trackedCount = 0
    tracked.forEach { file ->
      if (matcher(file)) {
        processRunner.run(workingDir, listOf("git", "update-index", "--assume-unchanged", file))
        processRunner.run(workingDir, listOf("git", "update-index", "--skip-worktree", file))
        trackedCount += 1
      }
    }

    val first = if (added) "Added to global ignore: $normalized" else "Global ignore already contained: $normalized"
    return "$first\nMarked assume-unchanged on tracked files: $trackedCount"
  }

  override fun localIgnoreEclipse(workingDir: Path): String {
    val patterns = listOf(
      ".project",
      ".classpath",
      "org.eclipse.jdt.core.prefs",
      "org.eclipse.m2e.core.prefs",
      "org.eclipse.jdt.apt.core.prefs",
      "org.eclipse.core.resources.prefs"
    )
    return patterns.joinToString("\n") { localIgnore(workingDir, it) }
  }

  override fun aheadFeature(workingDir: Path): String = processRunner.runCaptureOrThrow(
    workingDir,
    listOf("git", "log", "--left-right", "--cherry", "--pretty=format:%m %h %ad %an %s", "--date=format:%Y-%m-%d %H:%M", "@{upstream}...HEAD")
  )

  override fun aheadMaster(workingDir: Path): String = processRunner.runCaptureOrThrow(
    workingDir,
    listOf("git", "log", "--pretty=format:%h %ad %an %s", "--date=format:%Y-%m-%d %H:%M", "origin/master..HEAD")
  )

  override fun behindMaster(workingDir: Path): String = processRunner.runCaptureOrThrow(
    workingDir,
    listOf("git", "log", "--pretty=format:%h %ad %an %s", "--date=format:%Y-%m-%d %H:%M", "HEAD..master")
  )

  override fun updateMaster(workingDir: Path) {
    processRunner.runCaptureOrThrow(workingDir, listOf("git", "fetch", "origin"))
    processRunner.runCaptureOrThrow(workingDir, listOf("git", "branch", "-f", "master", "origin/master"))
  }

  override fun cloneBranch(workingDir: Path, repo: String, branch: String) {
    processRunner.runCaptureOrThrow(workingDir, listOf("git", "clone", "-b", branch, "--single-branch", "git@github.com:$repo.git"))
  }

  override fun cloneKnime(workingDir: Path, repo: String) {
    processRunner.runCaptureOrThrow(workingDir, listOf("git", "clone", "git@github.com:knime/$repo.git"))
  }

  override fun reword(workingDir: Path) {
    val home = System.getProperty("user.home")
    val python = Path.of(home, "git-repositories", "git-reword", ".venv", "bin", "python")
    val script = Path.of(home, "git-repositories", "git-reword", "reword.py")
    processRunner.runCaptureOrThrow(workingDir, listOf(python.toString(), script.toString()))
  }

  private fun resolveGlobalIgnoreFile(workingDir: Path): Path {
    val configured = runOrEmpty(workingDir, listOf("git", "config", "--global", "--get", "core.excludesfile"))
    if (configured.isNotBlank()) return Path.of(configured)
    val defaultFile = Path.of(System.getProperty("user.home"), ".config", "git", "ignore")
    processRunner.runCaptureOrThrow(workingDir, listOf("git", "config", "--global", "core.excludesfile", defaultFile.toString()))
    return defaultFile
  }

  private fun normalizeIgnorePattern(pattern: String): String {
    var value = pattern.removePrefix("/")
    if (value.startsWith("**/")) value = value.removePrefix("**/")
    return if (value.contains('/') && !value.startsWith("**/")) "**/$value" else value
  }

  private fun buildMatcher(pattern: String): (String) -> Boolean {
    val normalized = pattern.removePrefix("/")
    val matchers = listOf(
      FileSystems.getDefault().getPathMatcher("glob:$normalized"),
      FileSystems.getDefault().getPathMatcher("glob:**/$normalized")
    )
    return { candidate ->
      val path = Path.of(candidate)
      matchers.any { it.matches(path) }
    }
  }

  private fun defaultBaseRef(repoPath: Path): String {
    val originHead = runOrEmpty(repoPath, listOf("git", "symbolic-ref", "--quiet", "--short", "refs/remotes/origin/HEAD"))
      .removePrefix("origin/")
    if (originHead.isNotBlank()) {
      val hasOriginHead = processRunner.run(repoPath, listOf("git", "show-ref", "--verify", "--quiet", "refs/remotes/origin/$originHead")).exitCode == 0
      if (hasOriginHead) return "origin/$originHead"
    }
    val headRef = runOrEmpty(repoPath, listOf("git", "symbolic-ref", "--quiet", "--short", "HEAD"))
    if (headRef.isNotBlank()) {
      val hasHead = processRunner.run(repoPath, listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/$headRef")).exitCode == 0
      if (hasHead) return headRef
    }
    return processRunner.runCaptureOrThrow(repoPath, listOf("git", "rev-parse", "--verify", "HEAD"))
  }

  private fun runOrEmpty(workingDir: Path, command: List<String>): String {
    val result = processRunner.run(workingDir, command)
    return if (result.exitCode == 0) result.stdout else ""
  }

  private fun extractCommitMessage(jsonEvents: String): String {
    val parts = jsonEvents
      .lineSequence()
      .filter { it.isNotBlank() }
      .mapNotNull { line ->
        if (!line.contains("\"type\":\"text\"")) return@mapNotNull null
        extractPartText(line)?.takeIf { it.isNotBlank() }
      }
      .toList()

    if (parts.isNotEmpty()) return parts.joinToString("\n").trim()
    return jsonEvents.trim()
  }

  private fun unescapeJsonString(value: String): String = value
    .replace("\\n", "\n")
    .replace("\\t", "\t")
    .replace("\\r", "\r")
    .replace("\\\"", "\"")
    .replace("\\\\", "\\")

  private fun extractPartText(line: String): String? {
    val marker = "\"part\":{\"text\":\""
    val start = line.indexOf(marker)
    if (start < 0) return null

    val raw = StringBuilder()
    var escaped = false
    var idx = start + marker.length
    while (idx < line.length) {
      val ch = line[idx]
      if (escaped) {
        raw.append('\\').append(ch)
        escaped = false
      } else if (ch == '\\') {
        escaped = true
      } else if (ch == '"') {
        return unescapeJsonString(raw.toString())
      } else {
        raw.append(ch)
      }
      idx += 1
    }
    return null
  }
}

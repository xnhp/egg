package cn.varsa.egg.git

import cn.varsa.cli.core.CliException
import cn.varsa.egg.runtime.ProcessRunner
import cn.varsa.egg.runtime.runCaptureOrThrow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

interface GitApi {
  fun makeWorktree(workingDir: Path, repoName: String, branch: String, subdir: String?, override: Boolean)
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
  fun reword(workingDir: Path, request: RewordRequest)
}

enum class RewordMode {
  ISSUE_IDS,
  REMOVE_WIP
}

data class RewordRequest(
  val mode: RewordMode,
  val numCommits: Int,
  val authorEmail: String
)

class GitCliApi(private val processRunner: ProcessRunner) : GitApi {
  private val jiraIdRegex = Regex("([A-Z]+-\\d+)")
  private val defaultAuthorEmail = "benjamin.moser@knime.com"
  private val httpClient: HttpClient = HttpClient.newHttpClient()

  override fun makeWorktree(workingDir: Path, repoName: String, branch: String, subdir: String?, override: Boolean) {
    val repoPath = Path.of(System.getProperty("user.home"), "repos", repoName)
    val branchDirName = branch.replace('/', '_')
    val worktreePath = workingDir.resolve("${repoName}_$branchDirName").normalize()

    println("[INFO] Fetching latest changes from origin for $repoName at $repoPath")
    processRunner.runCaptureOrThrow(repoPath, listOf("git", "fetch", "--quiet", "origin"))

    val hasLocalBranch = processRunner.run(repoPath, listOf("git", "show-ref", "--verify", "--quiet", "refs/heads/$branch")).exitCode == 0
    val hasRemoteBranch = processRunner.run(repoPath, listOf("git", "show-ref", "--verify", "--quiet", "refs/remotes/origin/$branch")).exitCode == 0

    if (override && isRegisteredWorktreePath(repoPath, worktreePath)) {
      println("[INFO] Overriding existing registered worktree path: $worktreePath")
    }

    val forceArgs = if (override) listOf("-f") else emptyList()

    when {
      hasLocalBranch -> processRunner.runCaptureOrThrow(repoPath, listOf("git", "worktree", "add") + forceArgs + listOf(worktreePath.toString(), branch))
      hasRemoteBranch -> processRunner.runCaptureOrThrow(repoPath, listOf("git", "worktree", "add") + forceArgs + listOf("-b", branch, worktreePath.toString(), "origin/$branch"))
      else -> {
        val baseRef = defaultBaseRef(repoPath)
        processRunner.runCaptureOrThrow(repoPath, listOf("git", "worktree", "add") + forceArgs + listOf("-b", branch, worktreePath.toString(), baseRef))
        configureUpstream(repoPath, branch)
      }
    }

    if (!subdir.isNullOrBlank()) {
      processRunner.runCaptureOrThrow(worktreePath, listOf("git", "sparse-checkout", "init", "--cone"))
      processRunner.runCaptureOrThrow(worktreePath, listOf("git", "sparse-checkout", "set", subdir))
    }
  }

  private fun isRegisteredWorktreePath(repoPath: Path, worktreePath: Path): Boolean {
    val worktreeList = processRunner.runCaptureOrThrow(repoPath, listOf("git", "worktree", "list", "--porcelain"))
    return worktreeList
      .lineSequence()
      .filter { it.startsWith("worktree ") }
      .map { Path.of(it.removePrefix("worktree ").trim()).normalize() }
      .any { it == worktreePath }
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
    val resolvedRange = resolveChangedPathsRange(workingDir, range)
    val diffCommand = when {
      !resolvedRange.isNullOrBlank() -> listOf("git", "diff", "--name-only", "-z", resolvedRange)
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

  private fun resolveChangedPathsRange(workingDir: Path, range: String?): String? {
    return when (range) {
      "feature" -> {
        val upstream = processRunner.runCaptureOrThrow(
          workingDir,
          listOf("git", "rev-parse", "--abbrev-ref", "--symbolic-full-name", "@{upstream}")
        )
        val remote = upstream.substringBefore('/').takeIf { it != upstream } ?: "origin"
        processRunner.runCaptureOrThrow(workingDir, listOf("git", "fetch", "--quiet", remote))
        "@{upstream}"
      }
      "master" -> {
        processRunner.runCaptureOrThrow(workingDir, listOf("git", "fetch", "--quiet", "origin"))
        "origin/master"
      }
      else -> range
    }
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

  override fun reword(workingDir: Path, request: RewordRequest) {
    if (request.numCommits <= 0) throw CliException("Error: --num-commits must be greater than 0.", 1)
    if (request.authorEmail.isNotBlank()) {
      println("Author filtering enabled. Only processing commits from: ${request.authorEmail}")
    }

    when (request.mode) {
      RewordMode.ISSUE_IDS -> processIssueIdsMode(workingDir, request)
      RewordMode.REMOVE_WIP -> processRemoveWipMode(workingDir, request)
    }
  }

  private fun processIssueIdsMode(workingDir: Path, request: RewordRequest) {
    val currentBranch = runOrEmpty(workingDir, listOf("git", "branch", "--show-current"))
    if (currentBranch == "master") {
      println("Skipping repository ${workingDir.normalize()} (currently on master branch)")
      return
    }

    val issueId = jiraIdRegex.find(currentBranch)?.groupValues?.get(1)
    if (issueId.isNullOrBlank()) {
      println("Current branch '$currentBranch' does not contain a valid Jira issue ID. Stopping.")
      return
    }

    val summary = fetchJiraSummary(issueId)
    processCommits(workingDir, request) { original ->
      formatCommitMessage(original, issueId, summary)
    }
  }

  private fun processRemoveWipMode(workingDir: Path, request: RewordRequest) {
    processCommits(workingDir, request) { original -> removeWipTokens(original) }
  }

  private fun processCommits(
    workingDir: Path,
    request: RewordRequest,
    transform: (String) -> String
  ) {
    val stashed = stashChangesIfNeeded(workingDir)
    try {
      var sawCandidates = false
      val rewritten = mutableSetOf<String>()
      while (true) {
        val commits = commitsToReword(workingDir, request.numCommits, request.authorEmail)
        if (commits.isEmpty()) {
          if (!sawCandidates) {
            println("No commits found to reword")
          }
          return
        }

        sawCandidates = true
        var rewroteAny = false
        for (commitHash in commits.asReversed()) {
          if (rewritten.contains(commitHash)) {
            continue
          }
          val original = processRunner.runCaptureOrThrow(
            workingDir,
            listOf("git", "log", "-1", "--format=%B", commitHash)
          )
          val updated = transform(original)
          if (updated == original) {
            println("Skipping commit ${commitHash.take(7)} (no changes needed)")
            continue
          }
          println("Rewording commit ${commitHash.take(7)}")
          rewordCommit(workingDir, commitHash, updated)
          rewritten.add(commitHash)
          rewroteAny = true
          break
        }

        if (!rewroteAny) {
          return
        }
      }
    } finally {
      if (stashed) restoreStashedChanges(workingDir)
    }
  }

  private fun commitsToReword(workingDir: Path, count: Int, authorEmail: String): List<String> {
    val command = mutableListOf("git", "log", "-$count", "--format=%H")
    if (authorEmail.isNotBlank()) {
      command += listOf("--author", authorEmail)
    }
    val output = processRunner.runCaptureOrThrow(workingDir, command)
    return output
      .lineSequence()
      .map { it.trim() }
      .filter { it.isNotBlank() }
      .toList()
  }

  private fun stashChangesIfNeeded(workingDir: Path): Boolean {
    val status = runOrEmpty(workingDir, listOf("git", "status", "--porcelain"))
    if (status.isBlank()) return false
    println("Stashing existing changes")
    processRunner.runCaptureOrThrow(
      workingDir,
      listOf("git", "stash", "push", "--include-untracked", "--message", "egg-reword-temp")
    )
    return true
  }

  private fun restoreStashedChanges(workingDir: Path) {
    val result = processRunner.run(workingDir, listOf("git", "stash", "pop"))
    if (result.exitCode == 0) {
      println("Restored stashed changes")
      return
    }
    println("Warning: Failed to apply stashed changes automatically.")
    println("Run 'git stash pop' manually to restore them.")
    if (result.stderr.isNotBlank()) println(result.stderr)
  }

  private fun rewordCommit(workingDir: Path, commitHash: String, newMessage: String) {
    val originalBranch = processRunner.runCaptureOrThrow(
      workingDir,
      listOf("git", "branch", "--show-current")
    )
    val parentCommit = processRunner.runCaptureOrThrow(
      workingDir,
      listOf("git", "rev-parse", "$commitHash^")
    )
    val tempBranch = "temp-reword-${commitHash.take(7)}"

    try {
      processRunner.runCaptureOrThrow(
        workingDir,
        listOf("git", "checkout", "-b", tempBranch, commitHash)
      )
      processRunner.runCaptureOrThrow(
        workingDir,
        listOf("git", "commit", "--amend", "-m", newMessage)
      )
      val newCommit = processRunner.runCaptureOrThrow(
        workingDir,
        listOf("git", "rev-parse", "HEAD")
      )

      processRunner.runCaptureOrThrow(
        workingDir,
        listOf("git", "checkout", originalBranch)
      )
      processRunner.runCaptureOrThrow(
        workingDir,
        listOf("git", "rebase", "--onto", newCommit, parentCommit)
      )
    } catch (e: CliException) {
      processRunner.run(workingDir, listOf("git", "rebase", "--abort"))
      processRunner.run(workingDir, listOf("git", "checkout", originalBranch))
      processRunner.run(workingDir, listOf("git", "branch", "-D", tempBranch))
      throw e
    } finally {
      processRunner.run(workingDir, listOf("git", "branch", "-D", tempBranch))
    }
  }

  private fun formatCommitMessage(original: String, issueId: String, summary: String): String {
    val trimmed = original.trimEnd()
    val parts = trimmed.split("\n\n", limit = 2)
    val subject = parts[0]
    val body = parts.getOrNull(1).orEmpty()

    if (jiraIdRegex.containsMatchIn(subject)) return original

    val newSubject = "$issueId: $subject"
    val suffix = "$issueId ($summary)"
    val newBody = if (body.isBlank()) suffix else "${body.trim()}\n\n$suffix"
    return "$newSubject\n\n$newBody"
  }

  private fun removeWipTokens(text: String): String {
    val leadingPattern = Regex("^\\s*WIP[:\\-\\s]+", RegexOption.IGNORE_CASE)
    val inlinePattern = Regex("\\b\\(*\\[?WIP\\]?\\)*\\b[:\\-]?", RegexOption.IGNORE_CASE)

    return text
      .split("\n")
      .joinToString("\n") { line ->
        line
          .replace(leadingPattern, "")
          .replace(inlinePattern, "")
          .replace(Regex("\\s{2,}"), " ")
          .trim()
      }
      .trimEnd()
  }

  private fun fetchJiraSummary(issueId: String): String {
    val jiraUrl = requireConfig("JIRA_URL")
    val jiraEmail = requireConfig("JIRA_EMAIL")
    val jiraApiToken = requireConfig("JIRA_API_TOKEN")

    val base = jiraUrl.trimEnd('/')
    val request = HttpRequest.newBuilder()
      .uri(URI.create("$base/rest/api/3/issue/$issueId"))
      .header("Accept", "application/json")
      .header("Authorization", basicAuth(jiraEmail, jiraApiToken))
      .GET()
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if (response.statusCode() !in 200..299) {
      throw CliException(
        "Error getting Jira issue summary: Failed to fetch Jira issue: ${response.body()}",
        1
      )
    }

    return try {
      Json.parseToJsonElement(response.body())
        .jsonObject["fields"]
        ?.jsonObject
        ?.get("summary")
        ?.jsonPrimitive
        ?.content
        ?: throw IllegalStateException("Missing fields.summary")
    } catch (ex: Exception) {
      throw CliException("Error getting Jira issue summary: invalid Jira response: ${ex.message}", 1)
    }
  }

  private fun requireConfig(name: String): String {
    val value = System.getenv(name)?.trim().orEmpty().ifBlank {
      System.getProperty(name)?.trim().orEmpty()
    }
    if (value.isNotBlank()) return value

    if (name == "AUTHOR_EMAIL") {
      return defaultAuthorEmail
    }

    throw CliException(
      "JIRA credentials not configured. Please set JIRA_URL, JIRA_EMAIL, and JIRA_API_TOKEN environment variables.",
      1
    )
  }

  private fun basicAuth(email: String, token: String): String {
    val raw = "$email:$token"
    val encoded = Base64.getEncoder().encodeToString(raw.toByteArray(Charsets.UTF_8))
    return "Basic $encoded"
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
    val hasOriginMaster = processRunner.run(repoPath, listOf("git", "show-ref", "--verify", "--quiet", "refs/remotes/origin/master")).exitCode == 0
    if (hasOriginMaster) return "origin/master"

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

  private fun configureUpstream(repoPath: Path, branch: String) {
    processRunner.runCaptureOrThrow(repoPath, listOf("git", "config", "branch.$branch.remote", "origin"))
    processRunner.runCaptureOrThrow(repoPath, listOf("git", "config", "branch.$branch.merge", "refs/heads/$branch"))
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

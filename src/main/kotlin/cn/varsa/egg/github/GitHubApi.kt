package cn.varsa.egg.github

import cn.varsa.cli.core.CliException
import cn.varsa.egg.runtime.ProcessRunner
import cn.varsa.egg.runtime.runCaptureOrNull
import cn.varsa.egg.runtime.runCaptureOrThrow
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64

data class ReplyRequest(
  val repo: String?,
  val body: String?,
  val bodyFile: String?,
  val commentIds: List<String>
)

data class ResolveRequest(
  val repo: String?,
  val pr: String?,
  val commentIds: List<String>
)

interface GitHubApi {
  fun currentPrId(workingDir: Path): String
  fun currentPrUrl(workingDir: Path): String
  fun currentPrWeb(workingDir: Path)
  fun currentPrChecks(workingDir: Path): String
  fun prChecks(workingDir: Path, repo: String, prNumber: String): String
  fun currentPrReviewStatus(workingDir: Path, json: Boolean): String
  fun currentPrFeedback(workingDir: Path): String
  fun prFeedback(workingDir: Path, repo: String?, prNumber: String): String
  fun replyToReviewComments(workingDir: Path, request: ReplyRequest): String
  fun resolveReviewComments(workingDir: Path, request: ResolveRequest): String
  fun searchPrs(workingDir: Path, issueKey: String): String
  fun searchCode(workingDir: Path, queryParts: List<String>): String
  fun look(workingDir: Path, repo: String, path: String, ref: String?): String
  fun lookWeb(workingDir: Path, repo: String, path: String, ref: String?)
}

class GhCliGitHubApi(private val processRunner: ProcessRunner) : GitHubApi {
  override fun currentPrId(workingDir: Path): String = processRunner.runCaptureOrThrow(
    workingDir,
    listOf("gh", "pr", "view", "--json", "number", "--jq", ".number")
  )

  override fun currentPrUrl(workingDir: Path): String = processRunner.runCaptureOrThrow(
    workingDir,
    listOf("gh", "pr", "view", "--json", "url", "--jq", ".url")
  )

  override fun currentPrWeb(workingDir: Path) {
    processRunner.runCaptureOrThrow(workingDir, listOf("gh", "pr", "view", "--web"))
  }

  override fun currentPrChecks(workingDir: Path): String {
    val prId = currentPrId(workingDir)
    return processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "pr", "view", prId, "--json", "statusCheckRollup", "--jq", ".statusCheckRollup[] | {name, state, targetUrl}")
    )
  }

  override fun prChecks(workingDir: Path, repo: String, prNumber: String): String = processRunner.runCaptureOrThrow(
    workingDir,
    listOf("gh", "pr", "view", prNumber, "-R", repo, "--json", "statusCheckRollup", "--jq", ".statusCheckRollup[] | {name, state, targetUrl}")
  )

  override fun currentPrReviewStatus(workingDir: Path, json: Boolean): String {
    val prId = currentPrIdOrNull(workingDir)
    if (prId == null) {
      return if (json) "[]" else "No current PR found for this branch"
    }

    val query = """
      def normalize_state(${'$'}state):
        if ${'$'}state == "APPROVED" then "accepted"
        elif ${'$'}state == "CHANGES_REQUESTED" then "changes requested"
        else "pending"
        end;

      def reviewer_name:
        .login // .name // .requestedReviewer.login // .requestedReviewer.name // empty;

      (
        [(.reviewRequests // [])[] | reviewer_name | select(length > 0) | {reviewer: ., status: "pending"}] +
        [(.latestReviews // [])[]
          | (.author.login // .author.name // empty) as ${'$'}reviewer
          | select(${'$'}reviewer != "")
          | {reviewer: ${'$'}reviewer, status: normalize_state(.state // "")}
        ]
      )
      | group_by(.reviewer)
      | map({
          reviewer: .[0].reviewer,
          status: (
            if any(.[]; .status == "changes requested") then "changes requested"
            elif any(.[]; .status == "accepted") then "accepted"
            else "pending"
            end
          )
        })
      | sort_by(.reviewer)
    """.trimIndent()

    val jsonOutput = processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "pr", "view", prId, "--json", "latestReviews,reviewRequests", "--jq", query)
    )
    if (json) return jsonOutput
    if (jsonOutput == "[]") return "No reviewers yet"

    val lineQuery = "$query | .[] | \"\\(.reviewer): \\(.status)\""
    return processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "pr", "view", prId, "--json", "latestReviews,reviewRequests", "--jq", lineQuery)
    )
  }

  override fun currentPrFeedback(workingDir: Path): String {
    val prId = currentPrIdOrNull(workingDir) ?: return "{\"inlineComments\":[]}"
    return prFeedback(workingDir, repo = null, prNumber = prId)
  }

  override fun prFeedback(workingDir: Path, repo: String?, prNumber: String): String {
    val repoName = repo ?: resolveRepo(workingDir)
    val (owner, name) = splitRepo(repoName)
    val query = "query(${'$'}owner:String!,${'$'}name:String!,${'$'}number:Int!){repository(owner:${'$'}owner,name:${'$'}name){pullRequest(number:${'$'}number){reviewThreads(first:100){nodes{id isResolved isOutdated comments(first:100){nodes{databaseId body createdAt url path line author{login}}}}}}}}"
    val jq = ".data.repository.pullRequest.reviewThreads.nodes | map(. as ${'$'}t | ${'$'}t.comments.nodes[] | {id:.databaseId, author:(.author.login // \"\"), path, line, body, createdAt, permalink:.url, isResolved:${'$'}t.isResolved, isOutdated:${'$'}t.isOutdated}) | {inlineComments:.}"

    return processRunner.runCaptureOrThrow(
      workingDir,
      listOf(
        "gh", "api", "graphql",
        "-f", "query=$query",
        "-F", "owner=$owner",
        "-F", "name=$name",
        "-F", "number=$prNumber",
        "--jq", jq
      )
    )
  }

  override fun replyToReviewComments(workingDir: Path, request: ReplyRequest): String {
    if (request.commentIds.isEmpty()) throw CliException("At least one comment id is required", 2)
    val body = resolveBody(workingDir, request)
    val repo = request.repo ?: resolveRepo(workingDir)
    val reviewPairs = mutableSetOf<String>()
    var replied = 0

    request.commentIds.forEach { commentId ->
      val tsv = processRunner.runCaptureOrThrow(
        workingDir,
        listOf(
          "gh", "api", "repos/$repo/pulls/comments/$commentId/replies",
          "-f", "body=$body",
          "--jq", "[.pull_request_review_id, .pull_request_url] | @tsv"
        )
      )
      replied += 1
      val parts = tsv.split("\t")
      if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
        val pullNumber = parts[1].substringAfterLast('/')
        reviewPairs += "$pullNumber:${parts[0]}"
      }
    }

    reviewPairs.forEach { key ->
      val pullNumber = key.substringBefore(':')
      val reviewId = key.substringAfter(':')
      processRunner.run(
        workingDir,
        listOf("gh", "api", "-X", "POST", "repos/$repo/pulls/$pullNumber/reviews/$reviewId/events", "-f", "event=COMMENT")
      )
    }

    return "Replied: $replied"
  }

  override fun resolveReviewComments(workingDir: Path, request: ResolveRequest): String {
    if (request.commentIds.isEmpty()) throw CliException("At least one comment id is required", 2)

    val repo = request.repo ?: resolveRepo(workingDir)
    val pr = request.pr ?: currentPrId(workingDir)
    val (owner, name) = splitRepo(repo)
    val query = "query(${'$'}owner:String!,${'$'}name:String!,${'$'}number:Int!){repository(owner:${'$'}owner,name:${'$'}name){pullRequest(number:${'$'}number){reviewThreads(first:100){nodes{id isResolved comments(first:100){nodes{databaseId}}}}}}}"
    val jq = ".data.repository.pullRequest.reviewThreads.nodes[] | . as ${'$'}t | ${'$'}t.comments.nodes[] | [${'$'}t.id, ${'$'}t.isResolved, .databaseId] | @tsv"

    val threads = processRunner.runCaptureOrThrow(
      workingDir,
      listOf(
        "gh", "api", "graphql",
        "-f", "query=$query",
        "-F", "owner=$owner",
        "-F", "name=$name",
        "-F", "number=$pr",
        "--jq", jq
      )
    )

    val requested = request.commentIds.toSet()
    val seen = mutableSetOf<String>()
    var resolved = 0
    var already = 0

    threads.lineSequence().filter { it.isNotBlank() }.forEach { line ->
      val fields = line.split("\t")
      if (fields.size != 3) return@forEach
      val threadId = fields[0]
      val isResolved = fields[1] == "true"
      val commentId = fields[2]
      if (!requested.contains(commentId)) return@forEach

      seen += commentId
      if (!isResolved) {
        val mutation = "mutation(${'$'}threadId:ID!){resolveReviewThread(input:{threadId:${'$'}threadId}){thread{id isResolved}}}"
        processRunner.runCaptureOrThrow(
          workingDir,
          listOf("gh", "api", "graphql", "-f", "query=$mutation", "-F", "threadId=$threadId")
        )
        resolved += 1
      } else {
        already += 1
      }
    }

    val missing = requested.size - seen.size
    return "Resolved: $resolved\nAlready resolved: $already\nNot found: $missing"
  }

  override fun searchPrs(workingDir: Path, issueKey: String): String {
    if (issueKey.isBlank()) throw CliException("Usage: egg gh search-prs ISSUE_KEY", 2)
    val query = "query(${'$'}q:String!){search(query:${'$'}q,type:ISSUE,first:100){nodes{... on PullRequest{number title headRefName baseRefName url repository{nameWithOwner}}}}}"
    return processRunner.runCaptureOrThrow(
      workingDir,
      listOf(
        "gh", "api", "graphql",
        "-f", "query=$query",
        "-f", "q=org:knime in:title $issueKey type:pr",
        "--jq", "[.data.search.nodes[] | {number, title, headRefName, baseRefName, url, repository: .repository.nameWithOwner}]"
      )
    )
  }

  override fun searchCode(workingDir: Path, queryParts: List<String>): String {
    if (queryParts.isEmpty()) throw CliException("usage: egg gh search <query>", 2)
    return processRunner.runCaptureOrThrow(workingDir, listOf("gh", "search", "code") + queryParts + listOf("org:knime"))
  }

  override fun look(workingDir: Path, repo: String, path: String, ref: String?): String {
    val resolvedRef = ref ?: "master"
    val contentBase64 = processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "api", "repos/$repo/contents/$path?ref=$resolvedRef", "--jq", ".content")
    )
    return String(Base64.getMimeDecoder().decode(contentBase64))
  }

  override fun lookWeb(workingDir: Path, repo: String, path: String, ref: String?) {
    val resolvedRef = ref ?: processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "repo", "view", repo, "--json", "defaultBranchRef", "-q", ".defaultBranchRef.name")
    ).ifBlank { "main" }
    processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "browse", "--repo", repo, "--branch", resolvedRef, "--path", path)
    )
  }

  private fun resolveRepo(workingDir: Path): String = processRunner.runCaptureOrThrow(
    workingDir,
    listOf("gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner")
  )

  private fun resolveBody(workingDir: Path, request: ReplyRequest): String {
    request.bodyFile?.let {
      val file = workingDir.resolve(it).normalize()
      return Files.readString(file)
    }
    request.body?.let { return it }
    throw CliException("Provide --body or --body-file", 2)
  }

  private fun currentPrIdOrNull(workingDir: Path): String? {
    val value = processRunner.runCaptureOrNull(
      workingDir,
      listOf("gh", "pr", "view", "--json", "number", "--jq", ".number")
    )?.trim()
    return if (value.isNullOrBlank() || value == "null") null else value
  }

  private fun splitRepo(repo: String): Pair<String, String> {
    val owner = repo.substringBefore('/')
    val name = repo.substringAfter('/', "")
    if (owner.isBlank() || name.isBlank()) throw CliException("Repository must be in owner/name format", 2)
    return owner to name
  }
}

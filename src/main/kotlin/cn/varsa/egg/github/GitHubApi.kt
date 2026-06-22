package cn.varsa.egg.github

import cn.varsa.cli.core.CliException
import cn.varsa.egg.runtime.ProcessRunner
import cn.varsa.egg.runtime.runCaptureOrNull
import cn.varsa.egg.runtime.runCaptureOrThrow
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.Base64
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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

data class ThreadPullRequest(
  val repo: String?,
  val pr: String?,
  val json: Boolean
)

data class ThreadPushRequest(
  val repo: String?,
  val pr: String?,
  val dryRun: Boolean,
  val json: Boolean,
  val entityJson: String
)

data class ThreadPushResult(
  val payload: String,
  val exitCode: Int
)

data class IssuesPullRequest(
  val repo: String?,
  val issue: String?,
  val state: String?,
  val json: Boolean
)

data class IssuesPushRequest(
  val repo: String?,
  val issue: String?,
  val dryRun: Boolean,
  val json: Boolean,
  val entityJson: String
)

data class IssuesPushResult(
  val payload: String,
  val exitCode: Int
)

private data class CreatedThreadReply(
  val reviewId: Int?,
  val pullNumber: Int?
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
  fun prThreadPull(workingDir: Path, request: ThreadPullRequest): String
  fun prThreadPush(workingDir: Path, request: ThreadPushRequest): ThreadPushResult
  fun issuesPull(workingDir: Path, request: IssuesPullRequest): String
  fun issuesPush(workingDir: Path, request: IssuesPushRequest): IssuesPushResult
  fun searchPrs(workingDir: Path, issueKey: String): String
  fun searchCode(workingDir: Path, queryParts: List<String>): String
  fun look(workingDir: Path, repo: String, path: String, ref: String?): String
  fun lookWeb(workingDir: Path, repo: String, path: String, ref: String?)
}

class GhCliGitHubApi(private val processRunner: ProcessRunner) : GitHubApi {
  private val json = Json { ignoreUnknownKeys = true }

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

  override fun prThreadPull(workingDir: Path, request: ThreadPullRequest): String {
    val repo = request.repo ?: resolveRepo(workingDir)
    val pr = request.pr ?: currentPrId(workingDir)
    val snapshot = fetchReviewThreadsForPr(workingDir, repo, pr)
    return buildJsonObject {
      put("threads", JsonArray(snapshot.map { thread -> threadEntity(repo, pr.toInt(), thread) }))
    }.toString()
  }

  override fun prThreadPush(workingDir: Path, request: ThreadPushRequest): ThreadPushResult {
    return try {
      val local = parseThreadEntity(request.entityJson, request.repo, request.pr)
      val remote = fetchThreadById(workingDir, local.syncId)
      val remoteFingerprint = ThreadSyncFingerprint.compute(remote.thread)
      if (remoteFingerprint != local.syncBase) {
        val deltaOnCurrent = try {
          ThreadDeltaDeriver.derive(remote.thread, local.thread)
        } catch (e: CliException) {
          if (e.exitCode == 2) {
            return result(
              status = "invalid",
              exitCode = 2,
              syncId = local.syncId,
              repo = local.repo,
              prNumber = local.prNumber,
              operations = emptyList(),
              message = e.message ?: "Push failed"
            )
          }
          null
        }
        if (
          deltaOnCurrent != null &&
          deltaOnCurrent.deletedCommentIds.isEmpty() &&
          deltaOnCurrent.appendedBodies.isEmpty() &&
          deltaOnCurrent.visibilityOps.isEmpty() &&
          deltaOnCurrent.resolutionOp == null
        ) {
          return result(
            status = "noop",
            exitCode = 0,
            syncId = local.syncId,
            repo = local.repo,
            prNumber = local.prNumber,
            operations = emptyList(),
            message = "Remote changed since baseline; local intent already applied"
          )
        }
        return result(
          status = "conflict",
          exitCode = 3,
          syncId = local.syncId,
          repo = local.repo,
          prNumber = local.prNumber,
          operations = emptyList(),
          message = "Remote thread changed since baseline"
        )
      }

      val delta = ThreadDeltaDeriver.derive(remote.thread, local.thread)
      val operations = mutableListOf<JsonObject>()
      if (delta.deletedCommentIds.isNotEmpty()) {
        operations += buildJsonObject {
          put("type", "delete-comment")
          put("count", delta.deletedCommentIds.size)
        }
      }
      if (delta.appendedBodies.isNotEmpty()) {
        operations += buildJsonObject {
          put("type", "reply")
          put("count", delta.appendedBodies.size)
        }
      }
      val minimizeCount = delta.visibilityOps.count { it.type == CommentVisibilityOpType.MINIMIZE }
      if (minimizeCount > 0) {
        operations += buildJsonObject {
          put("type", "minimize-comment")
          put("count", minimizeCount)
        }
      }
      val unminimizeCount = delta.visibilityOps.count { it.type == CommentVisibilityOpType.UNMINIMIZE }
      if (unminimizeCount > 0) {
        operations += buildJsonObject {
          put("type", "unminimize-comment")
          put("count", unminimizeCount)
        }
      }
      if (delta.resolutionOp == ResolutionOpType.RESOLVE) {
        operations += buildJsonObject { put("type", "resolve") }
      }
      if (delta.resolutionOp == ResolutionOpType.UNRESOLVE) {
        operations += buildJsonObject { put("type", "unresolve") }
      }

      if (operations.isEmpty()) {
        return result(
          status = "noop",
          exitCode = 0,
          syncId = local.syncId,
          repo = local.repo,
          prNumber = local.prNumber,
          operations = emptyList(),
          message = "No changes to apply"
        )
      }

      if (!request.dryRun) {
        delta.deletedCommentIds.forEach { commentId ->
          deleteThreadComment(workingDir, local.repo, commentId)
        }
        val reviewsToSubmit = mutableSetOf<Pair<Int, Int>>()
        delta.appendedBodies.forEach { body ->
          val createdReply = addThreadReply(workingDir, local.syncId, body)
          val reviewId = createdReply.reviewId
          if (reviewId != null) {
            val pullNumber = createdReply.pullNumber ?: local.prNumber
            reviewsToSubmit += pullNumber to reviewId
          }
        }
        reviewsToSubmit.forEach { (pullNumber, reviewId) ->
          submitPullRequestReviewComment(workingDir, local.repo, pullNumber, reviewId)
        }
        delta.visibilityOps.forEach { op ->
          when (op.type) {
            CommentVisibilityOpType.MINIMIZE -> minimizeThreadComment(workingDir, remote.thread, op.commentId, op.reason!!)
            CommentVisibilityOpType.UNMINIMIZE -> unminimizeThreadComment(workingDir, remote.thread, op.commentId)
          }
        }
        when (delta.resolutionOp) {
          ResolutionOpType.RESOLVE -> resolveThread(workingDir, local.syncId)
          ResolutionOpType.UNRESOLVE -> unresolveThread(workingDir, local.syncId)
          null -> Unit
        }
      }

      val appliedMessage = if (request.dryRun) {
        "Dry run; computed operations without writing"
      } else {
        "Applied ${operations.size} operation(s)"
      }
      result(
        status = "applied",
        exitCode = 0,
        syncId = local.syncId,
        repo = local.repo,
        prNumber = local.prNumber,
        operations = operations,
        message = appliedMessage
      )
    } catch (e: CliException) {
      val exitCode = if (e.exitCode in listOf(2, 3)) e.exitCode else 1
      result(
        status = if (exitCode == 2) "invalid" else "error",
        exitCode = exitCode,
        syncId = null,
        repo = null,
        prNumber = null,
        operations = emptyList(),
        message = e.message ?: "Push failed"
      )
    } catch (e: Exception) {
      result(
        status = "error",
        exitCode = 1,
        syncId = null,
        repo = null,
        prNumber = null,
        operations = emptyList(),
        message = e.message ?: "Push failed"
      )
    }
  }

  override fun issuesPull(workingDir: Path, request: IssuesPullRequest): String {
    val repo = request.repo ?: resolveRepo(workingDir)
    val issueNumber = request.issue?.toIntOrNull()
      ?: request.issue?.let { throw CliException("--issue must be an integer", 2) }
    val state = request.state?.trim()?.lowercase()?.ifEmpty { null } ?: "open"
    if (state != "open" && state != "closed" && state != "all") {
      throw CliException("--state must be one of open|closed|all", 2)
    }

    val snapshots = if (issueNumber != null) {
      listOf(fetchIssueByNumber(workingDir, repo, issueNumber))
    } else {
      fetchIssuesForRepo(workingDir, repo, state)
    }

    val issues = snapshots.map { issueEntity(repo, it) }
    return buildJsonObject {
      put("issues", JsonArray(issues))
    }.toString()
  }

  override fun issuesPush(workingDir: Path, request: IssuesPushRequest): IssuesPushResult {
    return try {
      val local = parseIssueEntity(workingDir, request.entityJson, request.repo, request.issue)
      val remote = fetchIssueByNumber(workingDir, local.repo, local.issueNumber)
      if (remote.id != local.syncId) {
        return issueResult(
          status = "conflict",
          exitCode = 3,
          syncId = local.syncId,
          repo = local.repo,
          issueNumber = local.issueNumber,
          operations = emptyList(),
          message = "Remote issue identity does not match _sync.id"
        )
      }
      val remoteFingerprint = IssueSyncFingerprint.compute(remote)
      if (remoteFingerprint != local.syncBase) {
        val deltaOnCurrent = try {
          IssueDeltaDeriver.derive(remote, local.issue)
        } catch (e: CliException) {
          if (e.exitCode == 2) {
            return issueResult(
              status = "invalid",
              exitCode = 2,
              syncId = local.syncId,
              repo = local.repo,
              issueNumber = local.issueNumber,
              operations = emptyList(),
              message = e.message ?: "Push failed"
            )
          }
          null
        }
        if (deltaOnCurrent != null && deltaOnCurrent.hasNoChanges()) {
          return issueResult(
            status = "noop",
            exitCode = 0,
            syncId = local.syncId,
            repo = local.repo,
            issueNumber = local.issueNumber,
            operations = emptyList(),
            message = "Remote changed since baseline; local intent already applied"
          )
        }
        return issueResult(
          status = "conflict",
          exitCode = 3,
          syncId = local.syncId,
          repo = local.repo,
          issueNumber = local.issueNumber,
          operations = emptyList(),
          message = "Remote issue changed since baseline"
        )
      }

      val delta = IssueDeltaDeriver.derive(remote, local.issue)
      val operations = mutableListOf<JsonObject>()
      if (delta.changedTitle != null) operations += buildJsonObject { put("type", "update-title") }
      if (delta.changedBody != null) operations += buildJsonObject { put("type", "update-body") }
      if (delta.changedState != null) operations += buildJsonObject { put("type", "update-state") }
      if (delta.changedStateReason != null) operations += buildJsonObject { put("type", "update-state-reason") }
      if (delta.changedLabels != null) {
        operations += buildJsonObject {
          put("type", "set-labels")
          put("count", delta.changedLabels.size)
        }
      }
      if (delta.changedAssignees != null) {
        operations += buildJsonObject {
          put("type", "set-assignees")
          put("count", delta.changedAssignees.size)
        }
      }
      if (delta.appendedCommentBodies.isNotEmpty()) {
        operations += buildJsonObject {
          put("type", "reply-comment")
          put("count", delta.appendedCommentBodies.size)
        }
      }
      if (delta.milestoneChanged) {
        operations += buildJsonObject {
          put("type", "set-milestone")
          if (delta.changedMilestoneNumber != null) put("number", delta.changedMilestoneNumber)
        }
      }

      if (operations.isEmpty()) {
        return issueResult(
          status = "noop",
          exitCode = 0,
          syncId = local.syncId,
          repo = local.repo,
          issueNumber = local.issueNumber,
          operations = emptyList(),
          message = "No changes to apply"
        )
      }

      if (!request.dryRun) {
        val patch = buildJsonObject {
          if (delta.changedTitle != null) put("title", delta.changedTitle)
          if (delta.changedBody != null) put("body", delta.changedBody)
          if (delta.changedState != null) put("state", delta.changedState)
          if (delta.changedStateReason != null) put("state_reason", delta.changedStateReason)
          if (delta.changedLabels != null) {
            put("labels", JsonArray(delta.changedLabels.map(::JsonPrimitive)))
          }
          if (delta.changedAssignees != null) {
            put("assignees", JsonArray(delta.changedAssignees.map(::JsonPrimitive)))
          }
          if (delta.milestoneChanged) {
            if (delta.changedMilestoneNumber != null) put("milestone", delta.changedMilestoneNumber) else put("milestone", JsonNull)
          }
        }
        if (patch.isNotEmpty()) {
          applyIssuePatch(workingDir, local.repo, local.issueNumber, patch)
        }
        delta.appendedCommentBodies.forEach { body ->
          addIssueComment(workingDir, local.repo, local.issueNumber, body)
        }
      }

      val appliedMessage = if (request.dryRun) {
        "Dry run; computed operations without writing"
      } else {
        "Applied ${operations.size} operation(s)"
      }
      issueResult(
        status = "applied",
        exitCode = 0,
        syncId = local.syncId,
        repo = local.repo,
        issueNumber = local.issueNumber,
        operations = operations,
        message = appliedMessage
      )
    } catch (e: CliException) {
      val exitCode = if (e.exitCode in listOf(2, 3)) e.exitCode else 1
      issueResult(
        status = if (exitCode == 2) "invalid" else if (exitCode == 3) "conflict" else "error",
        exitCode = exitCode,
        syncId = null,
        repo = null,
        issueNumber = null,
        operations = emptyList(),
        message = e.message ?: "Push failed"
      )
    } catch (e: Exception) {
      issueResult(
        status = "error",
        exitCode = 1,
        syncId = null,
        repo = null,
        issueNumber = null,
        operations = emptyList(),
        message = e.message ?: "Push failed"
      )
    }
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

  private fun fetchIssuesForRepo(workingDir: Path, repo: String, state: String): List<IssueSnapshot> {
    val output = processRunner.runCaptureOrThrow(
      workingDir,
      listOf(
        "gh", "issue", "list",
        "-R", repo,
        "--state", state,
        "--limit", "200",
        "--json", "id,number,title,body,state,assignees,labels,milestone,url,author,createdAt,updatedAt,closedAt"
      )
    )
    val root = json.parseToJsonElement(output)
    val array = root as? JsonArray ?: throw CliException("Unexpected gh issue list payload", 1)
    return array.map { issueElement ->
      val base = parseIssueSnapshot(issueElement.jsonObject)
      val comments = fetchIssueComments(workingDir, repo, base.number)
      base.copy(comments = comments)
    }
  }

  private fun fetchIssueByNumber(workingDir: Path, repo: String, issueNumber: Int): IssueSnapshot {
    val output = processRunner.runCaptureOrThrow(
      workingDir,
      listOf(
        "gh", "issue", "view", issueNumber.toString(),
        "-R", repo,
        "--json", "id,number,title,body,state,assignees,labels,milestone,url,author,createdAt,updatedAt,closedAt"
      )
    )
    val base = parseIssueSnapshot(json.parseToJsonElement(output).jsonObject)
    val comments = fetchIssueComments(workingDir, repo, issueNumber)
    return base.copy(comments = comments)
  }

  private fun fetchIssueComments(workingDir: Path, repo: String, issueNumber: Int): List<IssueCommentSnapshot> {
    val comments = mutableListOf<IssueCommentSnapshot>()
    var page = 1
    while (true) {
      val output = processRunner.runCaptureOrThrow(
        workingDir,
        listOf("gh", "api", "repos/$repo/issues/$issueNumber/comments?per_page=100&page=$page")
      )
      val root = json.parseToJsonElement(output)
      val array = root as? JsonArray ?: throw CliException("Unexpected gh issue comments payload", 1)
      val pageItems = array.map { parseIssueCommentSnapshot(it.jsonObject) }
      comments += pageItems
      if (pageItems.size < 100) break
      page += 1
    }
    return comments
  }

  private fun parseIssueSnapshot(obj: JsonObject): IssueSnapshot {
    val id = obj.pathString("id") ?: throw CliException("Issue snapshot missing id", 1)
    val number = obj.pathInt("number") ?: throw CliException("Issue snapshot missing number", 1)
    val title = obj.pathString("title") ?: ""
    val body = obj.pathString("body") ?: ""
    val state = obj.pathString("state")?.uppercase() ?: "OPEN"
    val stateReason = obj.pathString("stateReason")?.uppercase()
    val assignees = (obj.pathArray("assignees") ?: emptyList()).mapNotNull { assignee ->
      assignee.jsonObject.pathString("login")
    }
    val labels = (obj.pathArray("labels") ?: emptyList()).mapNotNull { label ->
      label.jsonObject.pathString("name")
    }
    val milestoneObj = obj.pathObject("milestone")
    val milestoneNumber = milestoneObj?.pathInt("number")
    val milestoneTitle = milestoneObj?.pathString("title")

    return IssueSnapshot(
      id = id,
      number = number,
      title = title,
      body = body,
      state = state,
      stateReason = stateReason,
      assignees = assignees.sorted(),
      labels = labels.sorted(),
      milestoneNumber = milestoneNumber,
      milestoneTitle = milestoneTitle,
      url = obj.pathString("url"),
      author = obj.pathObject("author")?.pathString("login"),
      createdAt = obj.pathString("createdAt"),
      updatedAt = obj.pathString("updatedAt"),
      closedAt = obj.pathString("closedAt"),
      comments = emptyList(),
      parentIssueNumber = null
    )
  }

  private fun parseIssueCommentSnapshot(obj: JsonObject): IssueCommentSnapshot {
    val id = obj.pathString("node_id") ?: obj.pathLong("id")?.toString() ?: obj.pathString("id")
    return IssueCommentSnapshot(
      id = id,
      author = obj.pathObject("user")?.pathString("login") ?: obj.pathObject("author")?.pathString("login") ?: obj.pathString("author"),
      body = obj.pathString("body") ?: "",
      createdAt = obj.pathString("created_at") ?: obj.pathString("createdAt"),
      updatedAt = obj.pathString("updated_at") ?: obj.pathString("updatedAt"),
      url = obj.pathString("html_url") ?: obj.pathString("url")
    )
  }

  private data class LocalIssueEntity(
    val syncId: String,
    val syncBase: String,
    val repo: String,
    val issueNumber: Int,
    val issue: IssueSnapshot
  )

  private fun parseIssueEntity(workingDir: Path, entityJson: String, argRepo: String?, argIssue: String?): LocalIssueEntity {
    if (entityJson.isBlank()) throw CliException("Push expects one JSON entity on stdin", 2)
    val root = try {
      json.parseToJsonElement(entityJson).jsonObject
    } catch (_: SerializationException) {
      throw CliException("Push expects one valid JSON entity on stdin", 2)
    } catch (_: IllegalStateException) {
      throw CliException("Push expects one JSON object entity on stdin", 2)
    }

    val sync = root.pathObject("_sync") ?: throw CliException("Missing _sync object", 2)
    val syncId = sync.pathString("id") ?: throw CliException("Missing _sync.id", 2)
    val syncBase = sync.pathString("base") ?: throw CliException("Missing _sync.base", 2)
    if (!syncBase.startsWith("sha256:")) throw CliException("_sync.base must use sha256:<hex>", 2)

    val entityRepo = argRepo ?: root.pathString("repo") ?: resolveRepo(workingDir)
    val entityIssue = argIssue?.toIntOrNull()
      ?: argIssue?.let { throw CliException("--issue must be an integer", 2) }
      ?: root.pathInt("issueNumber")
      ?: root.pathInt("number")
      ?: root.pathObject("issue")?.pathInt("number")
      ?: throw CliException("Missing issueNumber", 2)

    val issueObj = root.pathObject("issue") ?: root
    val issueId = issueObj.pathString("id") ?: throw CliException("Missing issue.id", 2)
    if (issueId != syncId) throw CliException("issue.id must match _sync.id", 2)
    val issueNumber = issueObj.pathInt("number") ?: throw CliException("Missing issue.number", 2)
    if (issueNumber != entityIssue) throw CliException("issue.number must match issueNumber", 2)
    val state = issueObj.pathString("state") ?: throw CliException("Missing issue.state", 2)

    val assignees = (issueObj.pathArray("assignees") ?: throw CliException("Missing issue.assignees", 2)).map { element ->
      val value = element as? JsonPrimitive ?: throw CliException("issue.assignees must be an array of strings", 2)
      value.contentOrNull ?: throw CliException("issue.assignees must be an array of strings", 2)
    }
    val labels = (issueObj.pathArray("labels") ?: throw CliException("Missing issue.labels", 2)).map { element ->
      val value = element as? JsonPrimitive ?: throw CliException("issue.labels must be an array of strings", 2)
      value.contentOrNull ?: throw CliException("issue.labels must be an array of strings", 2)
    }

    val milestoneObj = issueObj.pathObject("milestone")
    val milestoneNumber = when {
      milestoneObj == null -> null
      milestoneObj["number"] is JsonNull -> null
      else -> milestoneObj.pathInt("number") ?: throw CliException("issue.milestone.number must be an integer", 2)
    }

    val relationshipObj = issueObj.pathObject("relationships")
    val parentIssueNumber = relationshipObj?.pathInt("parentIssueNumber")
    val comments = (issueObj.pathArray("comments") ?: emptyList()).map { element ->
      val commentObj = element as? JsonObject ?: throw CliException("issue.comments must be an array of objects", 2)
      val body = commentObj.pathString("body") ?: throw CliException("issue.comments[*].body must be a string", 2)
      val commentId = commentObj.pathString("id") ?: commentObj.pathLong("id")?.toString()
      IssueCommentSnapshot(
        id = commentId,
        author = commentObj.pathString("author"),
        body = body,
        createdAt = commentObj.pathString("createdAt"),
        updatedAt = commentObj.pathString("updatedAt"),
        url = commentObj.pathString("url")
      )
    }

    return LocalIssueEntity(
      syncId = syncId,
      syncBase = syncBase,
      repo = entityRepo,
      issueNumber = entityIssue,
      issue = IssueSnapshot(
        id = issueId,
        number = issueNumber,
        title = issueObj.pathString("title") ?: throw CliException("Missing issue.title", 2),
        body = issueObj.pathString("body") ?: "",
        state = state,
        stateReason = issueObj.pathString("stateReason"),
        assignees = assignees,
        labels = labels,
        milestoneNumber = milestoneNumber,
        milestoneTitle = milestoneObj?.pathString("title"),
        url = issueObj.pathString("url"),
        author = issueObj.pathString("author"),
        createdAt = issueObj.pathString("createdAt"),
        updatedAt = issueObj.pathString("updatedAt"),
        closedAt = issueObj.pathString("closedAt"),
        comments = comments,
        parentIssueNumber = parentIssueNumber
      )
    )
  }

  private fun issueEntity(repo: String, issue: IssueSnapshot): JsonObject {
    val base = IssueSyncFingerprint.compute(issue)
    return buildJsonObject {
      put("_sync", buildJsonObject {
        put("id", issue.id)
        put("base", base)
      })
      put("repo", repo)
      put("issueNumber", issue.number)
      put("id", issue.id)
      put("number", issue.number)
      put("title", issue.title)
      put("body", issue.body)
      put("state", issue.state)
      if (issue.stateReason != null) put("stateReason", issue.stateReason) else put("stateReason", JsonNull)
      if (issue.url != null) put("url", issue.url) else put("url", JsonNull)
      if (issue.author != null) put("author", issue.author) else put("author", JsonNull)
      if (issue.createdAt != null) put("createdAt", issue.createdAt) else put("createdAt", JsonNull)
      if (issue.updatedAt != null) put("updatedAt", issue.updatedAt) else put("updatedAt", JsonNull)
      if (issue.closedAt != null) put("closedAt", issue.closedAt) else put("closedAt", JsonNull)
      put("assignees", buildJsonArray { issue.assignees.forEach { add(JsonPrimitive(it)) } })
      put("labels", buildJsonArray { issue.labels.forEach { add(JsonPrimitive(it)) } })
      put("comments", buildJsonArray {
        issue.comments.forEach { comment ->
          add(buildJsonObject {
            if (comment.id != null) put("id", comment.id) else put("id", JsonNull)
            if (comment.author != null) put("author", comment.author) else put("author", JsonNull)
            put("body", comment.body)
            if (comment.createdAt != null) put("createdAt", comment.createdAt) else put("createdAt", JsonNull)
            if (comment.updatedAt != null) put("updatedAt", comment.updatedAt) else put("updatedAt", JsonNull)
            if (comment.url != null) put("url", comment.url) else put("url", JsonNull)
          })
        }
      })
      put("milestone", if (issue.milestoneNumber == null) JsonNull else buildJsonObject {
        put("number", issue.milestoneNumber)
        if (issue.milestoneTitle != null) put("title", issue.milestoneTitle) else put("title", JsonNull)
      })
      put("relationships", buildJsonObject {
        if (issue.parentIssueNumber != null) put("parentIssueNumber", issue.parentIssueNumber) else put("parentIssueNumber", JsonNull)
        put("parentIssue", JsonNull)
        put("subIssues", JsonArray(emptyList()))
      })
      put("references", buildJsonObject {
        put("issues", JsonArray(emptyList()))
        put("pullRequests", JsonArray(emptyList()))
        put("commits", JsonArray(emptyList()))
      })
      put("associatedBranches", JsonArray(emptyList()))
    }
  }

  private fun applyIssuePatch(workingDir: Path, repo: String, issueNumber: Int, patch: JsonObject) {
    val tmpFile = Files.createTempFile("egg-issue-patch-", ".json")
    try {
      Files.writeString(tmpFile, patch.toString(), StandardOpenOption.TRUNCATE_EXISTING)
      processRunner.runCaptureOrThrow(
        workingDir,
        listOf("gh", "api", "-X", "PATCH", "repos/$repo/issues/$issueNumber", "--input", tmpFile.toString())
      )
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  private fun addIssueComment(workingDir: Path, repo: String, issueNumber: Int, body: String) {
    val tmpFile = Files.createTempFile("egg-issue-comment-", ".json")
    try {
      val payload = buildJsonObject { put("body", body) }
      Files.writeString(tmpFile, payload.toString(), StandardOpenOption.TRUNCATE_EXISTING)
      processRunner.runCaptureOrThrow(
        workingDir,
        listOf("gh", "api", "-X", "POST", "repos/$repo/issues/$issueNumber/comments", "--input", tmpFile.toString())
      )
    } finally {
      Files.deleteIfExists(tmpFile)
    }
  }

  override fun searchCode(workingDir: Path, queryParts: List<String>): String {
    if (queryParts.isEmpty()) throw CliException("usage: egg gh search <query>", 2)
    val contentOutput = processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "search", "code") + queryParts + listOf("org:knime")
    )
    val pathOutput = processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "search", "code") + queryParts + listOf("in:path", "org:knime")
    )
    return (contentOutput.lines() + pathOutput.lines())
      .map { it.trimEnd() }
      .filter { it.isNotBlank() }
      .distinct()
      .joinToString("\n")
  }

  override fun look(workingDir: Path, repo: String, path: String, ref: String?): String {
    val resolvedRef = ref ?: "master"
    val apiPath = "repos/$repo/contents/$path?ref=$resolvedRef"
    val result = processRunner.run(workingDir, listOf("gh", "api", apiPath, "--jq", ".content"))
    if (result.exitCode != 0) {
      throw CliException(githubFileLookupError(repo, path, resolvedRef, apiPath, result.stdout, result.stderr), result.exitCode)
    }
    val contentBase64 = result.stdout.trim()
    if (contentBase64.isBlank() || contentBase64 == "null") {
      throw CliException(githubFileLookupError(repo, path, resolvedRef, apiPath, result.stdout, result.stderr), 1)
    }
    return try {
      String(Base64.getMimeDecoder().decode(contentBase64))
    } catch (e: IllegalArgumentException) {
      throw CliException(
        "Could not decode GitHub file content for $repo:$path at ref $resolvedRef\n" +
          "GitHub API path: $apiPath\n" +
          "The contents API response did not contain valid base64 file content.",
        1
      )
    }
  }

  private fun githubFileLookupError(repo: String, path: String, ref: String, apiPath: String, stdout: String, stderr: String): String {
    val details = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
    return buildString {
      append("Could not read GitHub file $repo:$path at ref $ref")
      append("\nGitHub API path: $apiPath")
      if (details.isNotBlank()) {
        append("\n")
        append(details)
      }
      append("\nPossible causes: the file path or ref does not exist, the path is a directory, or the repository is inaccessible.")
    }
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

  private fun fetchReviewThreadsForPr(workingDir: Path, repo: String, prNumber: String): List<ThreadSnapshot> {
    val (owner, name) = splitRepo(repo)
    val query = "query(${'$'}owner:String!,${'$'}name:String!,${'$'}number:Int!,${'$'}after:String){repository(owner:${'$'}owner,name:${'$'}name){pullRequest(number:${'$'}number){reviewThreads(first:100,after:${'$'}after){nodes{id isResolved isOutdated path line comments(first:100){nodes{id databaseId body createdAt updatedAt url isMinimized minimizedReason author{login}} pageInfo{hasNextPage endCursor}}} pageInfo{hasNextPage endCursor}}}}}"
    val threadNodes = mutableListOf<JsonObject>()
    var after: String? = null
    while (true) {
      val command = mutableListOf(
        "gh", "api", "graphql",
        "-f", "query=$query",
        "-F", "owner=$owner",
        "-F", "name=$name",
        "-F", "number=$prNumber"
      )
      if (after != null) command += listOf("-F", "after=$after")

      val response = processRunner.runCaptureOrThrow(workingDir, command)
      val root = json.parseToJsonElement(response).jsonObject
      val reviewThreads = root.pathObject("data", "repository", "pullRequest", "reviewThreads") ?: break
      val nodes = reviewThreads.pathArray("nodes") ?: emptyList<JsonElement>()
      threadNodes += nodes.map { it.jsonObject }

      val hasNextPage = reviewThreads.pathBoolean("pageInfo", "hasNextPage") == true
      after = reviewThreads.pathString("pageInfo", "endCursor")
      if (!hasNextPage) break
      if (after == null) throw CliException("Review thread pagination cursor missing", 1)
    }

    return threadNodes.map { node ->
      val threadId = node.pathString("id") ?: throw CliException("Thread snapshot missing id", 1)
      val comments = fetchAllThreadComments(
        workingDir = workingDir,
        threadId = threadId,
        initialComments = node.pathArray("comments", "nodes") ?: emptyList(),
        hasNextPage = node.pathBoolean("comments", "pageInfo", "hasNextPage") == true,
        endCursor = node.pathString("comments", "pageInfo", "endCursor")
      )
      parseThreadSnapshot(node, comments)
    }
  }

  private data class RemoteThreadRef(
    val repo: String,
    val prNumber: Int,
    val thread: ThreadSnapshot
  )

  private data class LocalThreadEntity(
    val syncId: String,
    val syncBase: String,
    val repo: String,
    val prNumber: Int,
    val thread: ThreadSnapshot
  )

  private fun fetchThreadById(workingDir: Path, syncId: String): RemoteThreadRef {
    val query = "query(${'$'}id:ID!){node(id:${'$'}id){... on PullRequestReviewThread{id isResolved isOutdated path line pullRequest{number repository{nameWithOwner}} comments(first:100){nodes{id databaseId body createdAt updatedAt url isMinimized minimizedReason author{login}} pageInfo{hasNextPage endCursor}}}}}"
    val response = processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "api", "graphql", "-f", "query=$query", "-F", "id=$syncId")
    )
    val root = json.parseToJsonElement(response).jsonObject
    val node = root.pathObject("data", "node")
      ?: throw CliException("Thread not found for _sync.id=$syncId", 2)
    val pr = node.pathObject("pullRequest") ?: throw CliException("Thread missing pullRequest", 1)
    val repo = pr.pathObject("repository")?.pathString("nameWithOwner")
      ?: throw CliException("Thread missing repository", 1)
    val prNumber = pr.pathInt("number") ?: throw CliException("Thread missing pull request number", 1)
    val comments = fetchAllThreadComments(
      workingDir = workingDir,
      threadId = syncId,
      initialComments = node.pathArray("comments", "nodes") ?: emptyList(),
      hasNextPage = node.pathBoolean("comments", "pageInfo", "hasNextPage") == true,
      endCursor = node.pathString("comments", "pageInfo", "endCursor")
    )
    return RemoteThreadRef(repo = repo, prNumber = prNumber, thread = parseThreadSnapshot(node, comments))
  }

  private fun fetchAllThreadComments(
    workingDir: Path,
    threadId: String,
    initialComments: List<JsonElement>,
    hasNextPage: Boolean,
    endCursor: String?
  ): List<ThreadCommentSnapshot> {
    val query = "query(${'$'}id:ID!,${'$'}after:String){node(id:${'$'}id){... on PullRequestReviewThread{comments(first:100,after:${'$'}after){nodes{id databaseId body createdAt updatedAt url isMinimized minimizedReason author{login}} pageInfo{hasNextPage endCursor}}}}}"
    val comments = initialComments.map { parseComment(it.jsonObject) }.toMutableList()
    var next = hasNextPage
    var cursor = endCursor
    while (next) {
      if (cursor == null) throw CliException("Thread comment pagination cursor missing", 1)
      val response = processRunner.runCaptureOrThrow(
        workingDir,
        listOf("gh", "api", "graphql", "-f", "query=$query", "-F", "id=$threadId", "-F", "after=$cursor")
      )
      val root = json.parseToJsonElement(response).jsonObject
      val page = root.pathObject("data", "node", "comments")
        ?: throw CliException("Thread comment page missing comments", 1)
      comments += (page.pathArray("nodes") ?: emptyList()).map { parseComment(it.jsonObject) }
      next = page.pathBoolean("pageInfo", "hasNextPage") == true
      cursor = page.pathString("pageInfo", "endCursor")
    }
    return comments
  }

  private fun parseThreadEntity(entityJson: String, argRepo: String?, argPr: String?): LocalThreadEntity {
    if (entityJson.isBlank()) throw CliException("Push expects one JSON entity on stdin", 2)
    val root = try {
      json.parseToJsonElement(entityJson).jsonObject
    } catch (_: SerializationException) {
      throw CliException("Push expects one valid JSON entity on stdin", 2)
    } catch (_: IllegalStateException) {
      throw CliException("Push expects one JSON object entity on stdin", 2)
    }
    val sync = root.pathObject("_sync") ?: throw CliException("Missing _sync object", 2)
    val syncId = sync.pathString("id") ?: throw CliException("Missing _sync.id", 2)
    val syncBase = sync.pathString("base") ?: throw CliException("Missing _sync.base", 2)
    if (!syncBase.startsWith("sha256:")) throw CliException("_sync.base must use sha256:<hex>", 2)

    val entityRepo = argRepo ?: root.pathString("repo") ?: throw CliException("Missing repo", 2)
    val entityPr = argPr?.toIntOrNull() ?: root.pathInt("prNumber") ?: throw CliException("Missing prNumber", 2)
    val threadObj = root.pathObject("thread") ?: throw CliException("Missing thread object", 2)
    val threadId = threadObj.pathString("id") ?: throw CliException("Missing thread.id", 2)
    if (threadId != syncId) throw CliException("thread.id must match _sync.id", 2)
    val resolved = threadObj.pathBoolean("isResolved") ?: throw CliException("Missing thread.isResolved", 2)
    val outdated = threadObj.pathBoolean("isOutdated") ?: false
    val comments = threadObj.pathArray("comments")
      ?: throw CliException("Missing thread.comments", 2)
    val parsedComments = comments.map { element ->
      parseComment(element.jsonObject)
    }

    return LocalThreadEntity(
      syncId = syncId,
      syncBase = syncBase,
      repo = entityRepo,
      prNumber = entityPr,
      thread = ThreadSnapshot(
        id = threadId,
        isResolved = resolved,
        isOutdated = outdated,
        path = threadObj.pathString("path"),
        line = threadObj.pathInt("line"),
        comments = parsedComments
      )
    )
  }

  private fun parseThreadSnapshot(node: JsonObject, commentsOverride: List<ThreadCommentSnapshot>? = null): ThreadSnapshot {
    val threadId = node.pathString("id") ?: throw CliException("Thread snapshot missing id", 1)
    val comments = commentsOverride ?: node.pathArray("comments", "nodes")?.map { element ->
      parseComment(element.jsonObject)
    } ?: emptyList()
    return ThreadSnapshot(
      id = threadId,
      isResolved = node.pathBoolean("isResolved") ?: false,
      isOutdated = node.pathBoolean("isOutdated") ?: false,
      path = node.pathString("path"),
      line = node.pathInt("line"),
      comments = comments
    )
  }

  private fun parseComment(commentObj: JsonObject): ThreadCommentSnapshot {
    val id = commentObj.pathLong("databaseId") ?: commentObj.pathLong("id")
    val minimizedReason = commentObj.pathString("minimizedReason")
    val minimizedReasonSet = commentObj.containsKey("minimizedReason")
    return ThreadCommentSnapshot(
      id = id,
      author = commentObj.pathObject("author")?.pathString("login") ?: commentObj.pathString("author"),
      body = commentObj.pathString("body"),
      createdAt = commentObj.pathString("createdAt"),
      updatedAt = commentObj.pathString("updatedAt"),
      url = commentObj.pathString("url"),
      minimizedReason = minimizedReason,
      minimizedReasonSet = minimizedReasonSet,
      nodeId = commentObj.pathString("id") ?: commentObj.pathString("nodeId")
    )
  }

  private fun threadEntity(repo: String, prNumber: Int, thread: ThreadSnapshot): JsonObject {
    val base = ThreadSyncFingerprint.compute(thread)
    return buildJsonObject {
      put("_sync", buildJsonObject {
        put("id", thread.id)
        put("base", base)
      })
      put("repo", repo)
      put("prNumber", prNumber)
      put("thread", buildJsonObject {
        put("id", thread.id)
        put("isResolved", thread.isResolved)
        put("isOutdated", thread.isOutdated)
        if (thread.path != null) put("path", thread.path) else put("path", JsonNull)
        if (thread.line != null) put("line", thread.line) else put("line", JsonNull)
        put("comments", buildJsonArray {
          thread.comments.forEach { comment ->
            add(buildJsonObject {
              if (comment.id != null) put("id", comment.id) else put("id", JsonNull)
              if (comment.author != null) put("author", comment.author) else put("author", JsonNull)
              if (comment.body != null) put("body", comment.body) else put("body", JsonNull)
              if (comment.createdAt != null) put("createdAt", comment.createdAt) else put("createdAt", JsonNull)
              if (comment.updatedAt != null) put("updatedAt", comment.updatedAt) else put("updatedAt", JsonNull)
              if (comment.url != null) put("url", comment.url) else put("url", JsonNull)
              if (comment.minimizedReason != null) put("minimizedReason", comment.minimizedReason) else put("minimizedReason", JsonNull)
            })
          }
        })
      })
    }
  }

  private fun addThreadReply(workingDir: Path, threadId: String, body: String): CreatedThreadReply {
    val mutation = "mutation(${'$'}threadId:ID!,${'$'}body:String!){addPullRequestReviewThreadReply(input:{pullRequestReviewThreadId:${'$'}threadId,body:${'$'}body}){comment{id pullRequestReview{databaseId} pullRequest{number}}}}"
    val response = processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "api", "graphql", "-f", "query=$mutation", "-F", "threadId=$threadId", "-F", "body=$body")
    )
    val root = json.parseToJsonElement(response).jsonObject
    val comment = root.pathObject("data", "addPullRequestReviewThreadReply", "comment")
      ?: throw CliException("Thread reply mutation response missing comment", 1)
    return CreatedThreadReply(
      reviewId = comment.pathInt("pullRequestReview", "databaseId"),
      pullNumber = comment.pathInt("pullRequest", "number")
    )
  }

  private fun submitPullRequestReviewComment(workingDir: Path, repo: String, pullNumber: Int, reviewId: Int) {
    processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "api", "-X", "POST", "repos/$repo/pulls/$pullNumber/reviews/$reviewId/events", "-f", "event=COMMENT")
    )
  }

  private fun deleteThreadComment(workingDir: Path, repo: String, commentId: Long) {
    processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "api", "-X", "DELETE", "repos/$repo/pulls/comments/$commentId")
    )
  }

  private fun resolveThread(workingDir: Path, threadId: String) {
    val mutation = "mutation(${'$'}threadId:ID!){resolveReviewThread(input:{threadId:${'$'}threadId}){thread{id isResolved}}}"
    processRunner.runCaptureOrThrow(workingDir, listOf("gh", "api", "graphql", "-f", "query=$mutation", "-F", "threadId=$threadId"))
  }

  private fun unresolveThread(workingDir: Path, threadId: String) {
    val mutation = "mutation(${'$'}threadId:ID!){unresolveReviewThread(input:{threadId:${'$'}threadId}){thread{id isResolved}}}"
    processRunner.runCaptureOrThrow(workingDir, listOf("gh", "api", "graphql", "-f", "query=$mutation", "-F", "threadId=$threadId"))
  }

  private fun minimizeThreadComment(workingDir: Path, remoteThread: ThreadSnapshot, commentId: Long, reason: String) {
    val subjectId = remoteThread.comments.firstOrNull { it.id == commentId }?.nodeId
      ?: throw CliException("Cannot minimize comment $commentId: missing node id", 2)
    val mutation = "mutation(${'$'}subjectId:ID!,${'$'}classifier:ReportedContentClassifiers!){minimizeComment(input:{subjectId:${'$'}subjectId,classifier:${'$'}classifier}){minimizedComment{isMinimized minimizedReason}}}"
    processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "api", "graphql", "-f", "query=$mutation", "-F", "subjectId=$subjectId", "-F", "classifier=$reason")
    )
  }

  private fun unminimizeThreadComment(workingDir: Path, remoteThread: ThreadSnapshot, commentId: Long) {
    val subjectId = remoteThread.comments.firstOrNull { it.id == commentId }?.nodeId
      ?: throw CliException("Cannot unminimize comment $commentId: missing node id", 2)
    val mutation = "mutation(${'$'}subjectId:ID!){unminimizeComment(input:{subjectId:${'$'}subjectId}){unminimizedComment{isMinimized minimizedReason}}}"
    processRunner.runCaptureOrThrow(
      workingDir,
      listOf("gh", "api", "graphql", "-f", "query=$mutation", "-F", "subjectId=$subjectId")
    )
  }

  private fun result(
    status: String,
    exitCode: Int,
    syncId: String?,
    repo: String?,
    prNumber: Int?,
    operations: List<JsonObject>,
    message: String
  ): ThreadPushResult {
    val payload = buildJsonObject {
      put("status", status)
      if (syncId != null) put("syncId", syncId) else put("syncId", JsonNull)
      if (repo != null) put("repo", repo) else put("repo", JsonNull)
      if (prNumber != null) put("prNumber", prNumber) else put("prNumber", JsonNull)
      put("operations", JsonArray(operations))
      put("message", message)
    }.toString()
    return ThreadPushResult(payload = payload, exitCode = exitCode)
  }

  private fun issueResult(
    status: String,
    exitCode: Int,
    syncId: String?,
    repo: String?,
    issueNumber: Int?,
    operations: List<JsonObject>,
    message: String
  ): IssuesPushResult {
    val payload = buildJsonObject {
      put("status", status)
      if (syncId != null) put("syncId", syncId) else put("syncId", JsonNull)
      if (repo != null) put("repo", repo) else put("repo", JsonNull)
      if (issueNumber != null) put("issueNumber", issueNumber) else put("issueNumber", JsonNull)
      put("operations", JsonArray(operations))
      put("message", message)
    }.toString()
    return IssuesPushResult(payload = payload, exitCode = exitCode)
  }
}

private fun JsonObject.pathElement(vararg path: String): JsonElement? {
  var current: JsonElement = this
  path.forEach { key ->
    val obj = current as? JsonObject ?: return null
    current = obj[key] ?: return null
  }
  return current
}

private fun JsonObject.pathObject(vararg path: String): JsonObject? = pathElement(*path) as? JsonObject

private fun JsonObject.pathArray(vararg path: String): JsonArray? = pathElement(*path) as? JsonArray

private fun JsonObject.pathString(vararg path: String): String? {
  val primitive = pathElement(*path) as? JsonPrimitive ?: return null
  if (primitive is JsonNull) return null
  return primitive.contentOrNull
}

private fun JsonObject.pathBoolean(vararg path: String): Boolean? {
  val primitive = pathElement(*path) as? JsonPrimitive ?: return null
  if (primitive is JsonNull) return null
  return primitive.booleanOrNull
}

private fun JsonObject.pathInt(vararg path: String): Int? {
  val primitive = pathElement(*path) as? JsonPrimitive ?: return null
  if (primitive is JsonNull) return null
  return primitive.intOrNull
}

private fun JsonObject.pathLong(vararg path: String): Long? {
  val primitive = pathElement(*path) as? JsonPrimitive ?: return null
  if (primitive is JsonNull) return null
  return primitive.contentOrNull?.toLongOrNull()
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: String) {
  put(key, JsonPrimitive(value))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: Int) {
  put(key, JsonPrimitive(value))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: Long) {
  put(key, JsonPrimitive(value))
}

private fun kotlinx.serialization.json.JsonObjectBuilder.put(key: String, value: Boolean) {
  put(key, JsonPrimitive(value))
}

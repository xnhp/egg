package cn.varsa.egg.github

import cn.varsa.cli.core.CliException
import cn.varsa.egg.runtime.ProcessResult
import cn.varsa.egg.runtime.ProcessRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GhCliGitHubApiTest {
  @Test
  fun `current pr id uses gh pr view json number`() {
    val runner = RecordingProcessRunner(
      responses = mapOf(
        listOf("gh", "pr", "view", "--json", "number", "--jq", ".number") to ProcessResult(0, "42", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val id = api.currentPrId(java.nio.file.Path.of("."))

    assertEquals("42", id)
    assertEquals(listOf("gh", "pr", "view", "--json", "number", "--jq", ".number"), runner.commands.single())
  }

  @Test
  fun `reply posts one api call per comment`() {
    val runner = RecordingProcessRunner(
      responses = mapOf(
        listOf("gh", "repo", "view", "--json", "nameWithOwner", "-q", ".nameWithOwner") to ProcessResult(0, "octo/repo", ""),
        listOf("gh", "api", "repos/octo/repo/pulls/comments/10/replies", "-f", "body=ok", "--jq", "[.pull_request_review_id, .pull_request_url] | @tsv") to ProcessResult(0, "11\thttps://api.github.com/repos/octo/repo/pulls/20", ""),
        listOf("gh", "api", "repos/octo/repo/pulls/comments/12/replies", "-f", "body=ok", "--jq", "[.pull_request_review_id, .pull_request_url] | @tsv") to ProcessResult(0, "13\thttps://api.github.com/repos/octo/repo/pulls/21", ""),
        listOf("gh", "api", "-X", "POST", "repos/octo/repo/pulls/20/reviews/11/events", "-f", "event=COMMENT") to ProcessResult(0, "", ""),
        listOf("gh", "api", "-X", "POST", "repos/octo/repo/pulls/21/reviews/13/events", "-f", "event=COMMENT") to ProcessResult(0, "", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val output = api.replyToReviewComments(
      java.nio.file.Path.of("."),
      ReplyRequest(repo = null, body = "ok", bodyFile = null, commentIds = listOf("10", "12"))
    )

    assertEquals("Replied: 2", output)
    assertEquals(5, runner.commands.size)
  }

  @Test
  fun `review status returns empty list when current pr is missing`() {
    val runner = RecordingProcessRunner(
      responses = mapOf(
        listOf("gh", "pr", "view", "--json", "number", "--jq", ".number") to ProcessResult(1, "", "no pull requests found")
      )
    )
    val api = GhCliGitHubApi(runner)

    val output = api.currentPrReviewStatus(java.nio.file.Path.of("."), json = true)

    assertEquals("[]", output)
  }

  @Test
  fun `look reports repo path and ref when contents api fails`() {
    val runner = RecordingProcessRunner(
      responses = mapOf(
        listOf("gh", "api", "repos/octo/repo/contents/src/Missing.kt?ref=main", "--jq", ".content") to
          ProcessResult(1, "{\"message\":\"Not Found\"}", "gh: Not Found (HTTP 404)")
      )
    )
    val api = GhCliGitHubApi(runner)

    val error = assertFailsWith<CliException> {
      api.look(java.nio.file.Path.of("."), repo = "octo/repo", path = "src/Missing.kt", ref = "main")
    }

    assertEquals(1, error.exitCode)
    assertTrue(error.message.orEmpty().contains("Could not read GitHub file octo/repo:src/Missing.kt at ref main"))
    assertTrue(error.message.orEmpty().contains("repos/octo/repo/contents/src/Missing.kt?ref=main"))
    assertTrue(error.message.orEmpty().contains("gh: Not Found (HTTP 404)"))
  }

  @Test
  fun `thread push returns conflict when fingerprint mismatches`() {
    val threadNodeResponse = """
      {"data":{"node":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"pullRequest":{"number":12,"repository":{"nameWithOwner":"octo/repo"}},"comments":{"nodes":[{"databaseId":101,"body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u","author":{"login":"alice"}}]}}}}
    """.trimIndent()
    val runner = SingleResponseRunner(ProcessResult(0, threadNodeResponse, ""))
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"TH_1","base":"sha256:deadbeef"},"repo":"octo/repo","prNumber":12,"thread":{"id":"TH_1","isResolved":false,"comments":[{"id":101,"author":"alice","body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},{"body":"new local reply"}]}}
    """.trimIndent()
    val result = api.prThreadPush(
      java.nio.file.Path.of("."),
      ThreadPushRequest(repo = null, pr = null, dryRun = false, json = true, entityJson = entity)
    )

    assertEquals(3, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"conflict\""))
    assertEquals(1, runner.commands.size)
  }

  @Test
  fun `thread push returns noop when stale baseline already reflects remote-applied comment`() {
    val threadNodeResponse = """
      {"data":{"node":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"pullRequest":{"number":12,"repository":{"nameWithOwner":"octo/repo"}},"comments":{"nodes":[{"databaseId":101,"body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u","author":{"login":"alice"}},{"databaseId":102,"body":"new reply","createdAt":"2026-01-01T00:00:10Z","updatedAt":"2026-01-01T00:00:10Z","url":"u2","author":{"login":"ben"}}]}}}}
    """.trimIndent()
    val runner = SingleResponseRunner(ProcessResult(0, threadNodeResponse, ""))
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"TH_1","base":"sha256:deadbeef"},"repo":"octo/repo","prNumber":12,"thread":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"comments":[{"id":101,"author":"alice","body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},{"body":"new reply"}]}}
    """.trimIndent()
    val result = api.prThreadPush(
      java.nio.file.Path.of("."),
      ThreadPushRequest(repo = null, pr = null, dryRun = false, json = true, entityJson = entity)
    )

    assertEquals(0, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"noop\""))
    assertEquals(1, runner.commands.size)
  }

  @Test
  fun `thread push dry run computes reply and resolve ops without write calls`() {
    val threadNodeResponse = """
      {"data":{"node":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"pullRequest":{"number":12,"repository":{"nameWithOwner":"octo/repo"}},"comments":{"nodes":[{"databaseId":101,"body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u","author":{"login":"alice"}}]}}}}
    """.trimIndent()
    val baseSnapshot = ThreadSnapshot(
      id = "TH_1",
      isResolved = false,
      isOutdated = false,
      path = "src/A.kt",
      line = 10,
      comments = listOf(
        ThreadCommentSnapshot(
          id = 101,
          author = "alice",
          body = "root",
          createdAt = "2026-01-01T00:00:00Z",
          updatedAt = "2026-01-01T00:00:00Z",
          url = "u"
        )
      )
    )
    val base = ThreadSyncFingerprint.compute(baseSnapshot)
    val runner = SingleResponseRunner(ProcessResult(0, threadNodeResponse, ""))
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"TH_1","base":"$base"},"repo":"octo/repo","prNumber":12,"thread":{"id":"TH_1","isResolved":true,"isOutdated":false,"path":"src/A.kt","line":10,"comments":[{"id":101,"author":"alice","body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},{"body":"new reply"}]}}
    """.trimIndent()
    val result = api.prThreadPush(
      java.nio.file.Path.of("."),
      ThreadPushRequest(repo = null, pr = null, dryRun = true, json = true, entityJson = entity)
    )

    assertEquals(0, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"applied\""))
    assertTrue(result.payload.contains("\"type\":\"reply\""))
    assertTrue(result.payload.contains("\"type\":\"resolve\""))
    assertEquals(1, runner.commands.size)
  }

  @Test
  fun `thread push applies delete comment operation`() {
    val threadNodeResponse = """
      {"data":{"node":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"pullRequest":{"number":12,"repository":{"nameWithOwner":"octo/repo"}},"comments":{"nodes":[{"databaseId":101,"body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u","author":{"login":"alice"}},{"databaseId":102,"body":"remove me","createdAt":"2026-01-01T00:00:01Z","updatedAt":"2026-01-01T00:00:01Z","url":"u2","author":{"login":"bob"}}]}}}}
    """.trimIndent()
    val baseSnapshot = ThreadSnapshot(
      id = "TH_1",
      isResolved = false,
      isOutdated = false,
      path = "src/A.kt",
      line = 10,
      comments = listOf(
        ThreadCommentSnapshot(101, "alice", "root", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "u"),
        ThreadCommentSnapshot(102, "bob", "remove me", "2026-01-01T00:00:01Z", "2026-01-01T00:00:01Z", "u2")
      )
    )
    val base = ThreadSyncFingerprint.compute(baseSnapshot)
    val runner = QueueResponseRunner(
      listOf(
        ProcessResult(0, threadNodeResponse, ""),
        ProcessResult(0, "", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"TH_1","base":"$base"},"repo":"octo/repo","prNumber":12,"thread":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"comments":[{"id":101,"author":"alice","body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}]}}
    """.trimIndent()

    val result = api.prThreadPush(
      java.nio.file.Path.of("."),
      ThreadPushRequest(repo = null, pr = null, dryRun = false, json = true, entityJson = entity)
    )

    assertEquals(0, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"applied\""))
    assertTrue(result.payload.contains("\"type\":\"delete-comment\""))
    assertTrue(runner.commands.any { it == listOf("gh", "api", "-X", "DELETE", "repos/octo/repo/pulls/comments/102") })
  }

  @Test
  fun `thread push applies minimize and unminimize operations`() {
    val threadNodeResponse = """
      {"data":{"node":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"pullRequest":{"number":12,"repository":{"nameWithOwner":"octo/repo"}},"comments":{"nodes":[{"id":"NODE_101","databaseId":101,"body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u","isMinimized":false,"minimizedReason":null,"author":{"login":"alice"}},{"id":"NODE_102","databaseId":102,"body":"old","createdAt":"2026-01-01T00:00:01Z","updatedAt":"2026-01-01T00:00:01Z","url":"u2","isMinimized":true,"minimizedReason":"OUTDATED","author":{"login":"bob"}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
    """.trimIndent()
    val baseSnapshot = ThreadSnapshot(
      id = "TH_1",
      isResolved = false,
      isOutdated = false,
      path = "src/A.kt",
      line = 10,
      comments = listOf(
        ThreadCommentSnapshot(101, "alice", "root", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", "u", null, true, "NODE_101"),
        ThreadCommentSnapshot(102, "bob", "old", "2026-01-01T00:00:01Z", "2026-01-01T00:00:01Z", "u2", "OUTDATED", true, "NODE_102")
      )
    )
    val base = ThreadSyncFingerprint.compute(baseSnapshot)
    val runner = QueueResponseRunner(
      listOf(
        ProcessResult(0, threadNodeResponse, ""),
        ProcessResult(0, "{}", ""),
        ProcessResult(0, "{}", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"TH_1","base":"$base"},"repo":"octo/repo","prNumber":12,"thread":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"comments":[{"id":101,"author":"alice","body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","minimizedReason":"OFF_TOPIC"},{"id":102,"author":"bob","body":"old","createdAt":"2026-01-01T00:00:01Z","updatedAt":"2026-01-01T00:00:01Z","minimizedReason":null}]}}
    """.trimIndent()

    val result = api.prThreadPush(
      java.nio.file.Path.of("."),
      ThreadPushRequest(repo = null, pr = null, dryRun = false, json = true, entityJson = entity)
    )

    assertEquals(0, result.exitCode)
    assertTrue(result.payload.contains("\"type\":\"minimize-comment\""))
    assertTrue(result.payload.contains("\"type\":\"unminimize-comment\""))
    assertTrue(
      runner.commands.any { command ->
        command.any { it.contains("minimizeComment") } && command.contains("subjectId=NODE_101") && command.contains("classifier=OFF_TOPIC")
      }
    )
    assertTrue(
      runner.commands.any { command ->
        command.any { it.contains("unminimizeComment") } && command.contains("subjectId=NODE_102")
      }
    )
  }

  @Test
  fun `thread push submits review after creating thread reply`() {
    val threadNodeResponse = """
      {"data":{"node":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"pullRequest":{"number":12,"repository":{"nameWithOwner":"octo/repo"}},"comments":{"nodes":[{"databaseId":101,"body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u","author":{"login":"alice"}}]}}}}
    """.trimIndent()
    val replyMutationResponse = """
      {"data":{"addPullRequestReviewThreadReply":{"comment":{"id":"C_1","pullRequestReview":{"databaseId":55},"pullRequest":{"number":12}}}}}
    """.trimIndent()
    val baseSnapshot = ThreadSnapshot(
      id = "TH_1",
      isResolved = false,
      isOutdated = false,
      path = "src/A.kt",
      line = 10,
      comments = listOf(
        ThreadCommentSnapshot(
          id = 101,
          author = "alice",
          body = "root",
          createdAt = "2026-01-01T00:00:00Z",
          updatedAt = "2026-01-01T00:00:00Z",
          url = "u"
        )
      )
    )
    val base = ThreadSyncFingerprint.compute(baseSnapshot)
    val runner = QueueResponseRunner(
      listOf(
        ProcessResult(0, threadNodeResponse, ""),
        ProcessResult(0, replyMutationResponse, ""),
        ProcessResult(0, "", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"TH_1","base":"$base"},"repo":"octo/repo","prNumber":12,"thread":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"comments":[{"id":101,"author":"alice","body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"},{"body":"new reply"}]}}
    """.trimIndent()

    val result = api.prThreadPush(
      java.nio.file.Path.of("."),
      ThreadPushRequest(repo = null, pr = null, dryRun = false, json = true, entityJson = entity)
    )

    assertEquals(0, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"applied\""))
    assertTrue(
      runner.commands.any {
        it == listOf("gh", "api", "-X", "POST", "repos/octo/repo/pulls/12/reviews/55/events", "-f", "event=COMMENT")
      }
    )
  }

  @Test
  fun `thread pull paginates review threads and comments`() {
    val firstThreadPage = """
      {"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"comments":{"nodes":[{"databaseId":1,"body":"c1","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u1","author":{"login":"alice"}},{"databaseId":2,"body":"c2","createdAt":"2026-01-01T00:00:01Z","updatedAt":"2026-01-01T00:00:01Z","url":"u2","author":{"login":"alice"}}],"pageInfo":{"hasNextPage":true,"endCursor":"C1"}}}],"pageInfo":{"hasNextPage":true,"endCursor":"T1"}}}}}}
    """.trimIndent()
    val secondThreadPage = """
      {"data":{"repository":{"pullRequest":{"reviewThreads":{"nodes":[{"id":"TH_2","isResolved":true,"isOutdated":false,"path":"src/B.kt","line":11,"comments":{"nodes":[{"databaseId":20,"body":"x","createdAt":"2026-01-01T00:00:02Z","updatedAt":"2026-01-01T00:00:02Z","url":"u20","author":{"login":"bob"}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}}
    """.trimIndent()
    val threadCommentPage = """
      {"data":{"node":{"comments":{"nodes":[{"databaseId":3,"body":"c3","createdAt":"2026-01-01T00:00:03Z","updatedAt":"2026-01-01T00:00:03Z","url":"u3","author":{"login":"alice"}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
    """.trimIndent()
    val runner = QueueResponseRunner(
      listOf(
        ProcessResult(0, firstThreadPage, ""),
        ProcessResult(0, secondThreadPage, ""),
        ProcessResult(0, threadCommentPage, "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val payload = api.prThreadPull(
      java.nio.file.Path.of("."),
      ThreadPullRequest(repo = "octo/repo", pr = "12", json = true)
    )

    assertTrue(payload.contains("\"id\":\"TH_1\""))
    assertTrue(payload.contains("\"id\":\"TH_2\""))
    assertTrue(payload.indexOf("\"id\":1") < payload.indexOf("\"id\":2"))
    assertTrue(payload.indexOf("\"id\":2") < payload.indexOf("\"id\":3"))
    assertTrue(runner.commands.any { it.contains("after=T1") })
    assertTrue(runner.commands.any { it.contains("id=TH_1") && it.contains("after=C1") })
    assertEquals(3, runner.commands.size)
  }

  @Test
  fun `thread push uses paged baseline comments for delta derivation`() {
    val firstHundredCommentNodes = (1..100).joinToString(",") { id ->
      """{"databaseId":$id,"body":"c$id","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u$id","author":{"login":"alice"}}"""
    }
    val firstThreadNodeResponse = """
      {"data":{"node":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"pullRequest":{"number":12,"repository":{"nameWithOwner":"octo/repo"}},"comments":{"nodes":[$firstHundredCommentNodes],"pageInfo":{"hasNextPage":true,"endCursor":"C100"}}}}}
    """.trimIndent()
    val trailingCommentPage = """
      {"data":{"node":{"comments":{"nodes":[{"databaseId":101,"body":"c101","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u101","author":{"login":"alice"}}],"pageInfo":{"hasNextPage":false,"endCursor":null}}}}}
    """.trimIndent()
    val runner = QueueResponseRunner(
      listOf(
        ProcessResult(0, firstThreadNodeResponse, ""),
        ProcessResult(0, trailingCommentPage, "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val remoteComments = (1L..101L).map { id ->
      ThreadCommentSnapshot(
        id = id,
        author = "alice",
        body = "c$id",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        url = "u$id"
      )
    }
    val base = ThreadSyncFingerprint.compute(
      ThreadSnapshot(
        id = "TH_1",
        isResolved = false,
        isOutdated = false,
        path = "src/A.kt",
        line = 10,
        comments = remoteComments
      )
    )
    val localComments = remoteComments.joinToString(",") { comment ->
      """{"id":${comment.id},"author":"${comment.author}","body":"${comment.body}","createdAt":"${comment.createdAt}","updatedAt":"${comment.updatedAt}"}"""
    }
    val entity = """
      {"_sync":{"id":"TH_1","base":"$base"},"repo":"octo/repo","prNumber":12,"thread":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"comments":[$localComments,{"body":"new reply"}]}}
    """.trimIndent()

    val result = api.prThreadPush(
      java.nio.file.Path.of("."),
      ThreadPushRequest(repo = null, pr = null, dryRun = true, json = true, entityJson = entity)
    )

    assertEquals(0, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"applied\""))
    assertTrue(result.payload.contains("\"type\":\"reply\""))
    assertTrue(runner.commands.any { it.contains("after=C100") })
    assertEquals(2, runner.commands.size)
  }

  @Test
  fun `thread push rejects missing thread id as invalid`() {
    val threadNodeResponse = """
      {"data":{"node":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"pullRequest":{"number":12,"repository":{"nameWithOwner":"octo/repo"}},"comments":{"nodes":[{"databaseId":101,"body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u","author":{"login":"alice"}}]}}}}
    """.trimIndent()
    val runner = SingleResponseRunner(ProcessResult(0, threadNodeResponse, ""))
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"TH_1","base":"sha256:abcd"},"repo":"octo/repo","prNumber":12,"thread":{"isResolved":false,"comments":[{"id":101,"author":"alice","body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}]}}
    """.trimIndent()
    val result = api.prThreadPush(
      java.nio.file.Path.of("."),
      ThreadPushRequest(repo = null, pr = null, dryRun = false, json = true, entityJson = entity)
    )

    assertEquals(2, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"invalid\""))
    assertEquals(0, runner.commands.size)
  }

  @Test
  fun `thread push rejects malformed json input as invalid`() {
    val runner = SingleResponseRunner(ProcessResult(0, "", ""))
    val api = GhCliGitHubApi(runner)

    val result = api.prThreadPush(
      java.nio.file.Path.of("."),
      ThreadPushRequest(repo = null, pr = null, dryRun = false, json = true, entityJson = "{not-json")
    )

    assertEquals(2, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"invalid\""))
    assertEquals(0, runner.commands.size)
  }

  @Test
  fun `issues pull emits sync entities`() {
    val issueListResponse = """
      [{"id":"I_1","number":12,"title":"Issue title","body":"Issue body","state":"OPEN","assignees":[{"login":"ben"}],"labels":[{"name":"nxt"}],"milestone":{"number":7,"title":"M1"},"url":"https://example.test/issues/12","author":{"login":"alice"},"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:01Z","closedAt":null}]
    """.trimIndent()
    val runner = RecordingProcessRunner(
      responses = mapOf(
        listOf("gh", "issue", "list", "-R", "octo/repo", "--state", "open", "--limit", "200", "--json", "id,number,title,body,state,assignees,labels,milestone,url,author,createdAt,updatedAt,closedAt") to ProcessResult(0, issueListResponse, ""),
        listOf("gh", "api", "repos/octo/repo/issues/12/comments?per_page=100&page=1") to ProcessResult(0, "[]", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val payload = api.issuesPull(
      java.nio.file.Path.of("."),
      IssuesPullRequest(repo = "octo/repo", issue = null, state = "open", json = true)
    )

    assertTrue(payload.contains("\"issues\":[{"))
    assertTrue(payload.contains("\"repo\":\"octo/repo\""))
    assertTrue(payload.contains("\"issueNumber\":12"))
    assertTrue(payload.contains("\"number\":12"))
    assertTrue(payload.contains("\"_sync\":{"))
    assertEquals(2, runner.commands.size)
  }

  @Test
  fun `issues push applies patch operation`() {
    val issueViewResponse = """
      {"id":"I_1","number":12,"title":"Old title","body":"Body","state":"OPEN","assignees":[{"login":"ben"}],"labels":[{"name":"nxt"}],"milestone":null,"url":"https://example.test/issues/12","author":{"login":"alice"},"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:01Z","closedAt":null}
    """.trimIndent()
    val base = IssueSyncFingerprint.compute(
      IssueSnapshot(
        id = "I_1",
        number = 12,
        title = "Old title",
        body = "Body",
        state = "OPEN",
        stateReason = null,
        assignees = listOf("ben"),
        labels = listOf("nxt"),
        milestoneNumber = null,
        milestoneTitle = null,
        url = "https://example.test/issues/12",
        author = "alice",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:01Z",
        closedAt = null,
        comments = emptyList(),
        parentIssueNumber = null
      )
    )
    val runner = QueueResponseRunner(
      listOf(
        ProcessResult(0, "octo/repo", ""),
        ProcessResult(0, issueViewResponse, ""),
        ProcessResult(0, "[]", ""),
        ProcessResult(0, "{}", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"I_1","base":"$base"},"issueNumber":12,"issue":{"id":"I_1","number":12,"title":"New title","body":"Body","state":"OPEN","stateReason":null,"assignees":["ben"],"labels":["nxt"],"milestone":null}}
    """.trimIndent()
    val result = api.issuesPush(
      java.nio.file.Path.of("."),
      IssuesPushRequest(repo = null, issue = null, dryRun = false, json = true, entityJson = entity)
    )

    assertEquals(0, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"applied\""))
    assertTrue(result.payload.contains("\"type\":\"update-title\""))
    assertTrue(runner.commands.any { it.take(5) == listOf("gh", "api", "-X", "PATCH", "repos/octo/repo/issues/12") })
  }

  @Test
  fun `issues pull includes comments in emitted entity`() {
    val issueListResponse = """
      [{"id":"I_1","number":12,"title":"Issue title","body":"Issue body","state":"OPEN","assignees":[],"labels":[],"milestone":null,"url":"https://example.test/issues/12","author":{"login":"alice"},"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:01Z","closedAt":null}]
    """.trimIndent()
    val commentsResponse = """
      [{"node_id":"IC_1","body":"First comment","created_at":"2026-01-01T00:10:00Z","updated_at":"2026-01-01T00:10:00Z","html_url":"https://example.test/issues/12#issuecomment-1","user":{"login":"ben"}}]
    """.trimIndent()
    val runner = RecordingProcessRunner(
      responses = mapOf(
        listOf("gh", "issue", "list", "-R", "octo/repo", "--state", "open", "--limit", "200", "--json", "id,number,title,body,state,assignees,labels,milestone,url,author,createdAt,updatedAt,closedAt") to ProcessResult(0, issueListResponse, ""),
        listOf("gh", "api", "repos/octo/repo/issues/12/comments?per_page=100&page=1") to ProcessResult(0, commentsResponse, "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val payload = api.issuesPull(
      java.nio.file.Path.of("."),
      IssuesPullRequest(repo = "octo/repo", issue = null, state = "open", json = true)
    )

    assertTrue(payload.contains("\"comments\":[{"))
    assertTrue(payload.contains("\"id\":\"IC_1\""))
    assertTrue(payload.contains("\"body\":\"First comment\""))
  }

  @Test
  fun `issues push appends new issue comment`() {
    val issueViewResponse = """
      {"id":"I_1","number":12,"title":"Old title","body":"Body","state":"OPEN","assignees":[],"labels":[],"milestone":null,"url":"https://example.test/issues/12","author":{"login":"alice"},"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:01Z","closedAt":null}
    """.trimIndent()
    val commentsResponse = """
      [{"node_id":"IC_1","body":"Existing","created_at":"2026-01-01T00:10:00Z","updated_at":"2026-01-01T00:10:00Z","html_url":"https://example.test/issues/12#issuecomment-1","user":{"login":"ben"}}]
    """.trimIndent()
    val base = IssueSyncFingerprint.compute(
      IssueSnapshot(
        id = "I_1",
        number = 12,
        title = "Old title",
        body = "Body",
        state = "OPEN",
        stateReason = null,
        assignees = emptyList(),
        labels = emptyList(),
        milestoneNumber = null,
        milestoneTitle = null,
        url = "https://example.test/issues/12",
        author = "alice",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:01Z",
        closedAt = null,
        comments = listOf(
          IssueCommentSnapshot(
            id = "IC_1",
            author = "ben",
            body = "Existing",
            createdAt = "2026-01-01T00:10:00Z",
            updatedAt = "2026-01-01T00:10:00Z",
            url = "https://example.test/issues/12#issuecomment-1"
          )
        ),
        parentIssueNumber = null
      )
    )
    val runner = QueueResponseRunner(
      listOf(
        ProcessResult(0, "octo/repo", ""),
        ProcessResult(0, issueViewResponse, ""),
        ProcessResult(0, commentsResponse, ""),
        ProcessResult(0, "{}", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"I_1","base":"$base"},"issueNumber":12,"issue":{"id":"I_1","number":12,"title":"Old title","body":"Body","state":"OPEN","stateReason":null,"assignees":[],"labels":[],"milestone":null,"comments":[{"id":"IC_1","author":"ben","body":"Existing","createdAt":"2026-01-01T00:10:00Z","updatedAt":"2026-01-01T00:10:00Z","url":"https://example.test/issues/12#issuecomment-1"},{"body":"New local comment"}]}}
    """.trimIndent()
    val result = api.issuesPush(
      java.nio.file.Path.of("."),
      IssuesPushRequest(repo = null, issue = null, dryRun = false, json = true, entityJson = entity)
    )

    assertEquals(0, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"applied\""))
    assertTrue(result.payload.contains("\"type\":\"reply-comment\""))
    assertTrue(runner.commands.any { it.take(5) == listOf("gh", "api", "-X", "POST", "repos/octo/repo/issues/12/comments") })
  }

  @Test
  fun `issues push accepts flat entity payload`() {
    val issueViewResponse = """
      {"id":"I_1","number":12,"title":"Old title","body":"Body","state":"OPEN","assignees":[],"labels":[],"milestone":null,"url":"https://example.test/issues/12","author":{"login":"alice"},"createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:01Z","closedAt":null}
    """.trimIndent()
    val base = IssueSyncFingerprint.compute(
      IssueSnapshot(
        id = "I_1",
        number = 12,
        title = "Old title",
        body = "Body",
        state = "OPEN",
        stateReason = null,
        assignees = emptyList(),
        labels = emptyList(),
        milestoneNumber = null,
        milestoneTitle = null,
        url = "https://example.test/issues/12",
        author = "alice",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:01Z",
        closedAt = null,
        comments = emptyList(),
        parentIssueNumber = null
      )
    )
    val runner = QueueResponseRunner(
      listOf(
        ProcessResult(0, "octo/repo", ""),
        ProcessResult(0, issueViewResponse, ""),
        ProcessResult(0, "[]", ""),
        ProcessResult(0, "{}", "")
      )
    )
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"I_1","base":"$base"},"issueNumber":12,"id":"I_1","number":12,"title":"New title","body":"Body","state":"OPEN","stateReason":null,"assignees":[],"labels":[],"milestone":null}
    """.trimIndent()
    val result = api.issuesPush(
      java.nio.file.Path.of("."),
      IssuesPushRequest(repo = null, issue = null, dryRun = false, json = true, entityJson = entity)
    )

    assertEquals(0, result.exitCode)
    assertTrue(result.payload.contains("\"status\":\"applied\""))
    assertTrue(result.payload.contains("\"type\":\"update-title\""))
  }

  private class RecordingProcessRunner(
    private val responses: Map<List<String>, ProcessResult>
  ) : ProcessRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(workingDir: java.nio.file.Path, command: List<String>): ProcessResult {
      commands += command
      return responses[command] ?: ProcessResult(0, "", "")
    }
  }

  private class SingleResponseRunner(
    private val response: ProcessResult
  ) : ProcessRunner {
    val commands = mutableListOf<List<String>>()

    override fun run(workingDir: java.nio.file.Path, command: List<String>): ProcessResult {
      commands += command
      return response
    }
  }

  private class QueueResponseRunner(
    responses: List<ProcessResult>
  ) : ProcessRunner {
    private val queue = ArrayDeque(responses)
    val commands = mutableListOf<List<String>>()

    override fun run(workingDir: java.nio.file.Path, command: List<String>): ProcessResult {
      commands += command
      return queue.removeFirstOrNull()
        ?: ProcessResult(1, "", "Unexpected command: ${command.joinToString(" ")}")
    }
  }
}

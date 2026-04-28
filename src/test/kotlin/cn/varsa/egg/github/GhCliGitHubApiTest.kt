package cn.varsa.egg.github

import cn.varsa.egg.runtime.ProcessResult
import cn.varsa.egg.runtime.ProcessRunner
import kotlin.test.Test
import kotlin.test.assertEquals
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
  fun `thread push returns conflict when fingerprint mismatches`() {
    val threadNodeResponse = """
      {"data":{"node":{"id":"TH_1","isResolved":false,"isOutdated":false,"path":"src/A.kt","line":10,"pullRequest":{"number":12,"repository":{"nameWithOwner":"octo/repo"}},"comments":{"nodes":[{"databaseId":101,"body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z","url":"u","author":{"login":"alice"}}]}}}}
    """.trimIndent()
    val runner = SingleResponseRunner(ProcessResult(0, threadNodeResponse, ""))
    val api = GhCliGitHubApi(runner)

    val entity = """
      {"_sync":{"id":"TH_1","base":"sha256:deadbeef"},"repo":"octo/repo","prNumber":12,"thread":{"id":"TH_1","isResolved":false,"comments":[{"id":101,"author":"alice","body":"root","createdAt":"2026-01-01T00:00:00Z","updatedAt":"2026-01-01T00:00:00Z"}]}}
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

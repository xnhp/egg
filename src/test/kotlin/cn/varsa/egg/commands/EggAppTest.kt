package cn.varsa.egg.commands

import cn.varsa.cli.core.CliMain
import cn.varsa.egg.git.GitApi
import cn.varsa.egg.git.RewordMode
import cn.varsa.egg.git.RewordRequest
import cn.varsa.egg.github.GitHubApi
import cn.varsa.egg.github.ReplyRequest
import cn.varsa.egg.github.ResolveRequest
import cn.varsa.egg.github.IssuesPullRequest
import cn.varsa.egg.github.IssuesPushRequest
import cn.varsa.egg.github.IssuesPushResult
import cn.varsa.egg.github.ThreadPullRequest
import cn.varsa.egg.github.ThreadPushRequest
import cn.varsa.egg.github.ThreadPushResult
import cn.varsa.egg.output.BufferedOutput
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EggAppTest {
  @Test
  fun `current pr id comes from injected github api`() {
    val fakeApi = FakeGitHubApi(prId = "55")
    val output = BufferedOutput()
    val app = EggApp(gitHubApi = fakeApi, gitApi = NoopGitApi(), output = output)

    val exitCode = CliMain.run(app.commandTree(), arrayOf("gh", "pr", "current", "id"))

    assertEquals(0, exitCode)
    assertEquals(listOf("55"), output.lines())
  }

  @Test
  fun `reply command forwards parsed arguments to injected github api`() {
    val fakeApi = FakeGitHubApi(replyResult = "Replied: 2")
    val output = BufferedOutput()
    val app = EggApp(gitHubApi = fakeApi, gitApi = NoopGitApi(), output = output)

    val exitCode = CliMain.run(
      app.commandTree(),
      arrayOf("gh", "pr", "comment", "reply", "--repo", "octo/repo", "--body", "LGTM", "101", "102")
    )

    assertEquals(0, exitCode)
    assertEquals(listOf("Replied: 2"), output.lines())
    assertEquals(
      ReplyRequest(
        repo = "octo/repo",
        body = "LGTM",
        bodyFile = null,
        commentIds = listOf("101", "102")
      ),
      fakeApi.lastReplyRequest
    )
  }

  @Test
  fun `changed paths delegates to git api`() {
    val output = BufferedOutput()
    val gitApi = NoopGitApi(changedPaths = "/repo/a.txt")
    val app = EggApp(gitHubApi = FakeGitHubApi(), gitApi = gitApi, output = output)

    val exitCode = CliMain.run(app.commandTree(), arrayOf("git", "changed-paths"))

    assertEquals(0, exitCode)
    assertEquals(listOf("/repo/a.txt"), output.lines())
  }

  @Test
  fun `worktree make forwards override flag to git api`() {
    val output = BufferedOutput()
    val gitApi = NoopGitApi()
    val app = EggApp(gitHubApi = FakeGitHubApi(), gitApi = gitApi, output = output)

    val exitCode = CliMain.run(app.commandTree(), arrayOf("git", "worktree", "make", "--override", "knime-ui", "enh/NXT-4439"))

    assertEquals(0, exitCode)
    assertEquals("knime-ui", gitApi.lastWorktreeRepoName)
    assertEquals("enh/NXT-4439", gitApi.lastWorktreeBranch)
    assertEquals(null, gitApi.lastWorktreeSubdir)
    assertEquals(true, gitApi.lastWorktreeOverride)
  }

  @Test
  fun `worktree make parser accepts override after positional args`() {
    val parsed = WorktreeMakeArgsParser.parse(arrayOf("knime-ui", "enh/NXT-4439", "org.knime.ui", "--override"))

    assertEquals("knime-ui", parsed.repoName)
    assertEquals("enh/NXT-4439", parsed.branch)
    assertEquals("org.knime.ui", parsed.subdir)
    assertEquals(true, parsed.override)
  }

  @Test
  fun `reply parser rejects unknown option`() {
    val error = assertFailsWith<RuntimeException> {
      ReplyArgsParser.parse(arrayOf("--wat", "1"))
    }

    assertEquals("Unknown option: --wat", error.message)
  }

  @Test
  fun `completion zsh prints compdef script`() {
    val output = BufferedOutput()
    val app = EggApp(gitHubApi = FakeGitHubApi(), gitApi = NoopGitApi(), output = output)

    val exitCode = CliMain.run(app.commandTree(), arrayOf("completion", "zsh"))

    assertEquals(0, exitCode)
    val script = output.lines().single()
    assertTrue(script.contains("#compdef egg"))
    assertTrue(script.contains("egg __complete"))
  }

  @Test
  fun `internal complete suggests child commands`() {
    val output = BufferedOutput()
    val app = EggApp(gitHubApi = FakeGitHubApi(), gitApi = NoopGitApi(), output = output)

    val exitCode = CliMain.run(app.commandTree(), arrayOf("__complete", "egg", "gh"))

    assertEquals(0, exitCode)
    assertEquals(listOf("issues\nlook\nlook-web\npr\nsearch\nsearch-prs"), output.lines())
  }

  @Test
  fun `issues pull forwards args to github api`() {
    val fakeApi = FakeGitHubApi(issuesPullResult = "{\"issues\":[]}")
    val output = BufferedOutput()
    val app = EggApp(gitHubApi = fakeApi, gitApi = NoopGitApi(), output = output)

    val exitCode = CliMain.run(app.commandTree(), arrayOf("gh", "issues", "pull", "--repo", "octo/repo", "--state", "open", "--json"))

    assertEquals(0, exitCode)
    assertEquals(listOf("{\"issues\":[]}"), output.lines())
    assertEquals(IssuesPullRequest(repo = "octo/repo", issue = null, state = "open", json = true), fakeApi.lastIssuesPullRequest)
  }

  @Test
  fun `issues push prints payload and exits with api exit code`() {
    val fakeApi = FakeGitHubApi(issuesPushResult = IssuesPushResult(payload = "{\"status\":\"conflict\"}", exitCode = 3))
    val output = BufferedOutput()
    val app = EggApp(
      gitHubApi = fakeApi,
      gitApi = NoopGitApi(),
      output = output,
      stdinProvider = { "{\"_sync\":{\"id\":\"I\",\"base\":\"sha256:abc\"},\"repo\":\"octo/repo\",\"issueNumber\":12,\"issue\":{\"id\":\"I\",\"number\":12,\"title\":\"t\",\"body\":\"b\",\"state\":\"OPEN\",\"assignees\":[],\"labels\":[]}}" }
    )

    val exitCode = CliMain.run(app.commandTree(), arrayOf("gh", "issues", "push", "--dry-run", "--json"))

    assertEquals(3, exitCode)
    assertEquals(listOf("{\"status\":\"conflict\"}"), output.lines())
    assertEquals(true, fakeApi.lastIssuesPushRequest?.dryRun)
  }

  @Test
  fun `thread pull forwards args to github api`() {
    val fakeApi = FakeGitHubApi(threadPullResult = "{\"threads\":[]}")
    val output = BufferedOutput()
    val app = EggApp(gitHubApi = fakeApi, gitApi = NoopGitApi(), output = output)

    val exitCode = CliMain.run(app.commandTree(), arrayOf("gh", "pr", "thread", "pull", "--repo", "octo/repo", "--pr", "12", "--json"))

    assertEquals(0, exitCode)
    assertEquals(listOf("{\"threads\":[]}"), output.lines())
    assertEquals(ThreadPullRequest(repo = "octo/repo", pr = "12", json = true), fakeApi.lastThreadPullRequest)
  }

  @Test
  fun `thread push prints payload and exits with api exit code`() {
    val fakeApi = FakeGitHubApi(threadPushResult = ThreadPushResult(payload = "{\"status\":\"conflict\"}", exitCode = 3))
    val output = BufferedOutput()
    val app = EggApp(
      gitHubApi = fakeApi,
      gitApi = NoopGitApi(),
      output = output,
      stdinProvider = { "{\"_sync\":{\"id\":\"T\",\"base\":\"sha256:abc\"},\"repo\":\"octo/repo\",\"prNumber\":1,\"thread\":{\"id\":\"T\",\"isResolved\":false,\"comments\":[]}}" }
    )

    val exitCode = CliMain.run(app.commandTree(), arrayOf("gh", "pr", "thread", "push", "--dry-run", "--json"))

    assertEquals(3, exitCode)
    assertEquals(listOf("{\"status\":\"conflict\"}"), output.lines())
    assertEquals(true, fakeApi.lastThreadPushRequest?.dryRun)
  }

  @Test
  fun `reword command forwards parsed request to git api`() {
    val output = BufferedOutput()
    val gitApi = NoopGitApi()
    val app = EggApp(gitHubApi = FakeGitHubApi(), gitApi = gitApi, output = output)

    val exitCode = CliMain.run(
      app.commandTree(),
      arrayOf("git", "reword", "--remove-WIP", "--author", "dev@knime.com", "--num-commits", "5")
    )

    assertEquals(0, exitCode)
    assertEquals(
      RewordRequest(mode = RewordMode.REMOVE_WIP, numCommits = 5, authorEmail = "dev@knime.com"),
      gitApi.lastRewordRequest
    )
  }

  private class FakeGitHubApi(
    private val prId: String = "1",
    private val prUrl: String = "https://example.test/pr/1",
    private val replyResult: String = "ok",
    private val resolveResult: String = "ok",
    private val threadPullResult: String = "{\"threads\":[]}",
    private val threadPushResult: ThreadPushResult = ThreadPushResult(payload = "{\"status\":\"noop\"}", exitCode = 0),
    private val issuesPullResult: String = "{\"issues\":[]}",
    private val issuesPushResult: IssuesPushResult = IssuesPushResult(payload = "{\"status\":\"noop\"}", exitCode = 0)
  ) : GitHubApi {
    var lastReplyRequest: ReplyRequest? = null
    var lastThreadPullRequest: ThreadPullRequest? = null
    var lastThreadPushRequest: ThreadPushRequest? = null
    var lastIssuesPullRequest: IssuesPullRequest? = null
    var lastIssuesPushRequest: IssuesPushRequest? = null

    override fun currentPrId(workingDir: java.nio.file.Path): String = prId

    override fun currentPrUrl(workingDir: java.nio.file.Path): String = prUrl

    override fun currentPrWeb(workingDir: java.nio.file.Path) = Unit

    override fun currentPrChecks(workingDir: java.nio.file.Path): String = "checks"

    override fun prChecks(workingDir: java.nio.file.Path, repo: String, prNumber: String): String = "checks"

    override fun currentPrReviewStatus(workingDir: java.nio.file.Path, json: Boolean): String = "[]"

    override fun currentPrFeedback(workingDir: java.nio.file.Path): String = "{}"

    override fun prFeedback(workingDir: java.nio.file.Path, repo: String?, prNumber: String): String = "{}"

    override fun replyToReviewComments(workingDir: java.nio.file.Path, request: ReplyRequest): String {
      lastReplyRequest = request
      return replyResult
    }

    override fun resolveReviewComments(workingDir: java.nio.file.Path, request: ResolveRequest): String = resolveResult

    override fun prThreadPull(workingDir: java.nio.file.Path, request: ThreadPullRequest): String {
      lastThreadPullRequest = request
      return threadPullResult
    }

    override fun prThreadPush(workingDir: java.nio.file.Path, request: ThreadPushRequest): ThreadPushResult {
      lastThreadPushRequest = request
      return threadPushResult
    }

    override fun issuesPull(workingDir: java.nio.file.Path, request: IssuesPullRequest): String {
      lastIssuesPullRequest = request
      return issuesPullResult
    }

    override fun issuesPush(workingDir: java.nio.file.Path, request: IssuesPushRequest): IssuesPushResult {
      lastIssuesPushRequest = request
      return issuesPushResult
    }

    override fun searchPrs(workingDir: java.nio.file.Path, issueKey: String): String = "[]"

    override fun searchCode(workingDir: java.nio.file.Path, queryParts: List<String>): String = "[]"

    override fun look(workingDir: java.nio.file.Path, repo: String, path: String, ref: String?): String = ""

    override fun lookWeb(workingDir: java.nio.file.Path, repo: String, path: String, ref: String?) = Unit
  }

  private class NoopGitApi(private val changedPaths: String = "") : GitApi {
    var lastRewordRequest: RewordRequest? = null
    var lastWorktreeRepoName: String? = null
    var lastWorktreeBranch: String? = null
    var lastWorktreeSubdir: String? = null
    var lastWorktreeOverride: Boolean? = null

    override fun makeWorktree(workingDir: java.nio.file.Path, repoName: String, branch: String, subdir: String?, override: Boolean) {
      lastWorktreeRepoName = repoName
      lastWorktreeBranch = branch
      lastWorktreeSubdir = subdir
      lastWorktreeOverride = override
    }

    override fun generateCommitMessage(workingDir: java.nio.file.Path): String = ""

    override fun changedPaths(workingDir: java.nio.file.Path, staged: Boolean, range: String?): String = changedPaths

    override fun localIgnore(workingDir: java.nio.file.Path, pattern: String): String = ""

    override fun localIgnoreEclipse(workingDir: java.nio.file.Path): String = ""

    override fun aheadFeature(workingDir: java.nio.file.Path): String = ""

    override fun aheadMaster(workingDir: java.nio.file.Path): String = ""

    override fun behindMaster(workingDir: java.nio.file.Path): String = ""

    override fun updateMaster(workingDir: java.nio.file.Path) = Unit

    override fun cloneBranch(workingDir: java.nio.file.Path, repo: String, branch: String) = Unit

    override fun cloneKnime(workingDir: java.nio.file.Path, repo: String) = Unit

    override fun reword(workingDir: java.nio.file.Path, request: RewordRequest) {
      lastRewordRequest = request
    }
  }
}

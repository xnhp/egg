package cn.varsa.egg

import cn.varsa.cli.core.CliCommandGroup
import cn.varsa.cli.core.CliCommandLeaf
import cn.varsa.cli.core.CliException
import cn.varsa.cli.core.CliMcpRegistrationConfig
import cn.varsa.cli.core.CliToolBinding
import cn.varsa.cli.core.registerCliTools
import cn.varsa.egg.ci.CiStatusRequest
import cn.varsa.egg.commands.EggApp
import cn.varsa.egg.git.RewordMode
import cn.varsa.egg.git.RewordRequest
import cn.varsa.egg.github.ReplyRequest
import cn.varsa.egg.github.ResolveRequest
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

fun Server.registerEggWorkflowTools(
  app: EggApp,
  config: CliMcpRegistrationConfig = CliMcpRegistrationConfig()
) {
  registerCliTools(app.mcpWorkflowCommand(), config)
}

private fun EggApp.mcpWorkflowCommand(): CliCommandGroup = CliCommandGroup(
  name = "egg",
  description = "Egg MCP workflow tools",
  children = listOf(
    workflowLeaf(
      name = "get-current-pr",
      description = "Return current PR identity and optional checks/review context.",
      tool = CliToolBinding(
        id = "egg_get_current_pr",
        title = "Get current PR",
        description = "Return current PR identity and optional checks/review context.",
        inputSchema = workflowSchema(
          "includeChecks" to booleanProperty("Include current PR check status.", default = true),
          "includeReviewStatus" to booleanProperty("Include current PR review status.", default = true),
          "includeFeedback" to booleanProperty("Include current PR feedback summary.", default = false)
        ),
        decodeArguments = { arguments ->
          buildArgs {
            addFlag("--no-checks", !arguments.boolean("includeChecks", default = true))
            addFlag("--no-review-status", !arguments.boolean("includeReviewStatus", default = true))
            addFlag("--include-feedback", arguments.boolean("includeFeedback"))
          }
        }
      ),
      handler = { args -> currentPrContext(args) }
    ),
    workflowLeaf(
      name = "get-pr-feedback",
      description = "Fetch review feedback and optional checks for a PR.",
      tool = CliToolBinding(
        id = "egg_get_pr_feedback",
        title = "Get PR feedback",
        description = "Fetch review feedback and optional checks for a PR.",
        inputSchema = workflowSchema(
          "repo" to stringProperty("Optional owner/repo override."),
          "pr" to stringProperty("Optional PR number; defaults to current PR."),
          "includeThreads" to booleanProperty("Include review thread feedback.", default = true),
          "includeChecks" to booleanProperty("Include PR check status.", default = true)
        ),
        decodeArguments = { arguments ->
          buildArgs {
            addOptional("--repo", arguments.string("repo"))
            addOptional("--pr", arguments.string("pr"))
            addFlag("--no-threads", !arguments.boolean("includeThreads", default = true))
            addFlag("--no-checks", !arguments.boolean("includeChecks", default = true))
          }
        }
      ),
      handler = { args -> prFeedbackContext(args) }
    ),
    workflowLeaf(
      name = "reply-to-review-comments",
      description = "Reply to review comments.",
      tool = CliToolBinding(
        id = "egg_reply_to_review_comments",
        title = "Reply to review comments",
        description = "Reply to review comments.",
        inputSchema = workflowSchema(
          "comments" to stringArrayProperty("Review comment ids or refs to reply to."),
          "message" to stringProperty("Reply body."),
          "repo" to stringProperty("Optional owner/repo override."),
          "pr" to stringProperty("Optional PR number, reserved for future API support.")
        ),
        annotations = ToolAnnotations(destructiveHint = true),
        decodeArguments = { arguments ->
          buildArgs {
            addOptional("--repo", arguments.string("repo"))
            addOptional("--body", arguments.string("message"))
            addRepeated(null, arguments.stringArray("comments"))
          }
        }
      ),
      handler = { args -> replyToReviewCommentsWorkflow(args) }
    ),
    workflowLeaf(
      name = "resolve-review-comments",
      description = "Resolve review comments or threads.",
      tool = CliToolBinding(
        id = "egg_resolve_review_comments",
        title = "Resolve review comments",
        description = "Resolve review comments or threads.",
        inputSchema = workflowSchema(
          "comments" to stringArrayProperty("Review comment ids or refs to resolve."),
          "repo" to stringProperty("Optional owner/repo override."),
          "pr" to stringProperty("Optional PR number.")
        ),
        annotations = ToolAnnotations(destructiveHint = true),
        decodeArguments = { arguments ->
          buildArgs {
            addOptional("--repo", arguments.string("repo"))
            addOptional("--pr", arguments.string("pr"))
            addRepeated(null, arguments.stringArray("comments"))
          }
        }
      ),
      handler = { args -> resolveReviewCommentsWorkflow(args) }
    ),
    workflowLeaf(
      name = "search-github",
      description = "Search GitHub code or PRs.",
      tool = CliToolBinding(
        id = "egg_search_github",
        title = "Search GitHub",
        description = "Search GitHub code or PRs.",
        inputSchema = workflowSchema(
          "kind" to enumProperty("Search kind.", listOf("code", "pullRequestsByIssue")),
          "query" to stringProperty("Search query or issue key."),
          "org" to stringProperty("Optional organization qualifier for code search."),
          "limit" to integerProperty("Optional result limit for code search.")
        ),
        decodeArguments = { arguments ->
          buildArgs {
            add(arguments.string("kind") ?: "code")
            addOptional(null, arguments.string("query"))
            addOptional("--org", arguments.string("org"))
            addOptional("--limit", arguments.int("limit")?.toString())
          }
        }
      ),
      handler = { args -> searchGithubWorkflow(args) }
    ),
    workflowLeaf(
      name = "read-repo-file",
      description = "Read a file from a GitHub repository.",
      tool = CliToolBinding(
        id = "egg_read_repo_file",
        title = "Read repo file",
        description = "Read a file from a GitHub repository.",
        inputSchema = workflowSchema(
          "repo" to stringProperty("Repository as owner/repo."),
          "path" to stringProperty("File path."),
          "ref" to stringProperty("Optional branch, tag, or commit.")
        ),
        decodeArguments = { arguments ->
          buildArgs {
            addOptional(null, arguments.string("repo"))
            addOptional(null, arguments.string("path"))
            addOptional(null, arguments.string("ref"))
          }
        }
      ),
      handler = { args -> readRepoFileWorkflow(args) }
    ),
    workflowLeaf(
      name = "get-ci-status",
      description = "Show configured CI status for the current repo, branch, or PR.",
      tool = CliToolBinding(
        id = "egg_get_ci_status",
        title = "Get CI status",
        description = "Show configured CI status for the current repo, branch, or PR.",
        inputSchema = workflowSchema(
          "repo" to stringProperty("Optional repo/job override."),
          "branch" to stringProperty("Optional branch override."),
          "pr" to stringProperty("Optional PR number."),
          "includeLogs" to booleanProperty("Reserved for future log inclusion.", default = false)
        ),
        decodeArguments = { arguments ->
          buildArgs {
            addOptional(null, arguments.string("repo"))
            addOptional(null, arguments.string("pr") ?: arguments.string("branch"))
          }
        }
      ),
      handler = { args -> ciStatusWorkflow(args) }
    ),
    workflowLeaf(
      name = "get-git-context",
      description = "Return local git context useful before editing or committing.",
      tool = CliToolBinding(
        id = "egg_get_git_context",
        title = "Get git context",
        description = "Return local git context useful before editing or committing.",
        inputSchema = workflowSchema(
          "includeChangedPaths" to booleanProperty("Include changed paths.", default = true),
          "includeAheadBehind" to booleanProperty("Include ahead/behind summaries.", default = true),
          "stagedOnly" to booleanProperty("Only include staged changed paths.", default = false),
          "range" to stringProperty("Optional git revision range for changed paths.")
        ),
        decodeArguments = { arguments ->
          buildArgs {
            addFlag("--no-changed-paths", !arguments.boolean("includeChangedPaths", default = true))
            addFlag("--no-ahead-behind", !arguments.boolean("includeAheadBehind", default = true))
            addFlag("--staged", arguments.boolean("stagedOnly"))
            addOptional("--range", arguments.string("range"))
          }
        }
      ),
      handler = { args -> gitContextWorkflow(args) }
    ),
    workflowLeaf(
      name = "generate-commit-message",
      description = "Generate a commit message from staged changes.",
      tool = CliToolBinding(
        id = "egg_generate_commit_message",
        title = "Generate commit message",
        description = "Generate a commit message from staged changes.",
        inputSchema = workflowSchema(
          "includeBody" to booleanProperty("Reserved; current generator decides whether a body is useful.", default = true)
        ),
        decodeArguments = { emptyArray() }
      ),
      handler = { generateCommitMessageWorkflow() }
    ),
    workflowLeaf(
      name = "prepare-worktree",
      description = "Create or prepare a development worktree.",
      tool = CliToolBinding(
        id = "egg_prepare_worktree",
        title = "Prepare worktree",
        description = "Create or prepare a development worktree.",
        inputSchema = workflowSchema(
          "repoName" to stringProperty("Repository name under ~/repos."),
          "branch" to stringProperty("Branch name."),
          "subdir" to stringProperty("Optional sparse-checkout subdirectory."),
          "override" to booleanProperty("Allow overriding an existing registered worktree path.", default = false)
        ),
        annotations = ToolAnnotations(destructiveHint = true),
        decodeArguments = { arguments ->
          buildArgs {
            addFlag("--override", arguments.boolean("override"))
            addOptional(null, arguments.string("repoName"))
            addOptional(null, arguments.string("branch"))
            addOptional(null, arguments.string("subdir"))
          }
        }
      ),
      handler = { args -> prepareWorktreeWorkflow(args) }
    ),
    workflowLeaf(
      name = "clone-repository",
      description = "Clone a repository or branch into the expected local layout.",
      tool = CliToolBinding(
        id = "egg_clone_repository",
        title = "Clone repository",
        description = "Clone a repository or branch into the expected local layout.",
        inputSchema = workflowSchema(
          "repo" to stringProperty("Repository as owner/repo or repo name."),
          "branch" to stringProperty("Optional branch."),
          "orgPreset" to enumProperty("Optional organization preset.", listOf("knime"))
        ),
        annotations = ToolAnnotations(destructiveHint = true),
        decodeArguments = { arguments ->
          buildArgs {
            addOptional("--org-preset", arguments.string("orgPreset"))
            addOptional(null, arguments.string("repo"))
            addOptional(null, arguments.string("branch"))
          }
        }
      ),
      handler = { args -> cloneRepositoryWorkflow(args) }
    ),
    workflowLeaf(
      name = "reword-commits",
      description = "Reword recent commits from issue ids or another supported mode.",
      tool = CliToolBinding(
        id = "egg_reword_commits",
        title = "Reword commits",
        description = "Reword recent commits from issue ids or another supported mode.",
        inputSchema = workflowSchema(
          "mode" to enumProperty("Reword mode.", listOf("issueIds", "removeWip")),
          "authorEmail" to stringProperty("Author email override."),
          "numCommits" to integerProperty("Number of recent commits to inspect."),
          "range" to stringProperty("Reserved for future explicit range support."),
          "dryRun" to booleanProperty("Reserved for future dry-run support.", default = false)
        ),
        annotations = ToolAnnotations(destructiveHint = true),
        decodeArguments = { arguments ->
          buildArgs {
            when (arguments.string("mode")) {
              "removeWip" -> add("--remove-WIP")
              else -> add("--issue-ids")
            }
            addOptional("--author", arguments.string("authorEmail"))
            addOptional("--num-commits", arguments.int("numCommits")?.toString())
          }
        }
      ),
      handler = { args -> rewordCommitsWorkflow(args) }
    )
  )
)

private fun EggApp.workflowLeaf(
  name: String,
  description: String,
  tool: CliToolBinding,
  handler: (Array<String>) -> String
): CliCommandLeaf = CliCommandLeaf(
  name = name,
  description = description,
  tool = tool,
  handler = { args ->
    output.println(handler(args))
    0
  }
)

private fun EggApp.currentPrContext(args: Array<String>): String {
  val options = args.toSet()
  return buildList {
    add("PR ID:")
    add(gitHubApi.currentPrId(workingDirProvider()))
    add("URL:")
    add(gitHubApi.currentPrUrl(workingDirProvider()))
    if ("--no-checks" !in options) {
      add("Checks:")
      add(gitHubApi.currentPrChecks(workingDirProvider()))
    }
    if ("--no-review-status" !in options) {
      add("Review status:")
      add(gitHubApi.currentPrReviewStatus(workingDirProvider(), json = true))
    }
    if ("--include-feedback" in options) {
      add("Feedback:")
      add(gitHubApi.currentPrFeedback(workingDirProvider()))
    }
  }.joinToString("\n")
}

private fun EggApp.prFeedbackContext(args: Array<String>): String {
  val parsed = parseOptions(args)
  val repo = parsed.options["repo"]
  val pr = parsed.options["pr"] ?: gitHubApi.currentPrId(workingDirProvider()).trim()
  return buildList {
    if ("no-threads" !in parsed.flags) {
      add("Feedback:")
      add(gitHubApi.prFeedback(workingDirProvider(), repo = repo, prNumber = pr))
    }
    if ("no-checks" !in parsed.flags && repo != null) {
      add("Checks:")
      add(gitHubApi.prChecks(workingDirProvider(), repo = repo, prNumber = pr))
    } else if ("no-checks" !in parsed.flags) {
      add("Checks:")
      add(gitHubApi.currentPrChecks(workingDirProvider()))
    }
  }.joinToString("\n")
}

private fun EggApp.replyToReviewCommentsWorkflow(args: Array<String>): String {
  val parsed = parseOptions(args)
  return gitHubApi.replyToReviewComments(
    workingDirProvider(),
    ReplyRequest(
      repo = parsed.options["repo"],
      body = parsed.options["body"],
      bodyFile = null,
      commentIds = parsed.positionals
    )
  )
}

private fun EggApp.resolveReviewCommentsWorkflow(args: Array<String>): String {
  val parsed = parseOptions(args)
  return gitHubApi.resolveReviewComments(
    workingDirProvider(),
    ResolveRequest(
      repo = parsed.options["repo"],
      pr = parsed.options["pr"],
      commentIds = parsed.positionals
    )
  )
}

private fun EggApp.searchGithubWorkflow(args: Array<String>): String {
  val kind = args.getOrNull(0) ?: "code"
  val query = args.getOrNull(1).orEmpty()
  val parsed = parseOptions(args.drop(2).toTypedArray())
  return when (kind) {
    "pullRequestsByIssue" -> gitHubApi.searchPrs(workingDirProvider(), query)
    else -> {
      val queryParts = buildList {
        add(query)
        parsed.options["org"]?.let { add("org:$it") }
        parsed.options["limit"]?.let { add("--limit"); add(it) }
      }
      gitHubApi.searchCode(workingDirProvider(), queryParts)
    }
  }
}

private fun EggApp.readRepoFileWorkflow(args: Array<String>): String {
  if (args.size !in 2..3) throw CliException("egg_read_repo_file requires repo and path", 2)
  return gitHubApi.look(workingDirProvider(), repo = args[0], path = args[1], ref = args.getOrNull(2))
}

private fun EggApp.ciStatusWorkflow(args: Array<String>): String = ciApi.status(
  workingDirProvider(),
  CiStatusRequest(jobOrRepo = args.getOrNull(0), branchOrPr = args.getOrNull(1))
)

private fun EggApp.gitContextWorkflow(args: Array<String>): String {
  val parsed = parseOptions(args)
  return buildList {
    if ("no-changed-paths" !in parsed.flags) {
      add("Changed paths:")
      add(gitApi.changedPaths(workingDirProvider(), staged = "staged" in parsed.flags, range = parsed.options["range"]))
    }
    if ("no-ahead-behind" !in parsed.flags) {
      add("Ahead feature:")
      add(gitApi.aheadFeature(workingDirProvider()))
      add("Ahead master:")
      add(gitApi.aheadMaster(workingDirProvider()))
      add("Behind master:")
      add(gitApi.behindMaster(workingDirProvider()))
    }
  }.joinToString("\n")
}

private fun EggApp.generateCommitMessageWorkflow(): String = gitApi.generateCommitMessage(workingDirProvider())

private fun EggApp.prepareWorktreeWorkflow(args: Array<String>): String {
  val parsed = parseOptions(args)
  val repoName = parsed.positionals.getOrNull(0) ?: throw CliException("egg_prepare_worktree requires repoName", 2)
  val branch = parsed.positionals.getOrNull(1) ?: throw CliException("egg_prepare_worktree requires branch", 2)
  gitApi.makeWorktree(
    workingDirProvider(),
    repoName = repoName,
    branch = branch,
    subdir = parsed.positionals.getOrNull(2),
    override = "override" in parsed.flags
  )
  return "Prepared worktree for $repoName on $branch"
}

private fun EggApp.cloneRepositoryWorkflow(args: Array<String>): String {
  val parsed = parseOptions(args)
  val repo = parsed.positionals.getOrNull(0) ?: throw CliException("egg_clone_repository requires repo", 2)
  val branch = parsed.positionals.getOrNull(1)
  if (parsed.options["org-preset"] == "knime" && branch == null) {
    gitApi.cloneKnime(workingDirProvider(), repo)
  } else {
    gitApi.cloneBranch(workingDirProvider(), repo = repo, branch = branch ?: "master")
  }
  return "Cloned $repo${branch?.let { " at $it" }.orEmpty()}"
}

private fun EggApp.rewordCommitsWorkflow(args: Array<String>): String {
  val parsed = parseOptions(args)
  val request = RewordRequest(
    mode = if ("remove-WIP" in parsed.flags) RewordMode.REMOVE_WIP else RewordMode.ISSUE_IDS,
    numCommits = parsed.options["num-commits"]?.toIntOrNull() ?: 3,
    authorEmail = parsed.options["author"] ?: "benjamin.moser@knime.com"
  )
  gitApi.reword(workingDirProvider(), request)
  return "Reworded commits"
}

private data class ParsedArgs(val options: Map<String, String>, val flags: Set<String>, val positionals: List<String>)

private fun parseOptions(args: Array<String>): ParsedArgs {
  val options = mutableMapOf<String, String>()
  val flags = mutableSetOf<String>()
  val positionals = mutableListOf<String>()
  var idx = 0
  while (idx < args.size) {
    val arg = args[idx]
    if (!arg.startsWith("--")) {
      positionals += arg
      idx += 1
      continue
    }
    val name = arg.removePrefix("--")
    val next = args.getOrNull(idx + 1)
    if (next != null && !next.startsWith("--")) {
      options[name] = next
      idx += 2
    } else {
      flags += name
      idx += 1
    }
  }
  return ParsedArgs(options = options, flags = flags, positionals = positionals)
}

private fun workflowSchema(vararg properties: Pair<String, kotlinx.serialization.json.JsonElement>): ToolSchema = ToolSchema(
  properties = buildJsonObject {
    properties.forEach { (name, property) -> put(name, property) }
  }
)

private fun stringProperty(description: String) = buildJsonObject {
  put("type", JsonPrimitive("string"))
  put("description", JsonPrimitive(description))
}

private fun stringArrayProperty(description: String) = buildJsonObject {
  put("type", JsonPrimitive("array"))
  put("description", JsonPrimitive(description))
  put("items", buildJsonObject { put("type", JsonPrimitive("string")) })
}

private fun booleanProperty(description: String, default: Boolean) = buildJsonObject {
  put("type", JsonPrimitive("boolean"))
  put("description", JsonPrimitive(description))
  put("default", JsonPrimitive(default))
}

private fun integerProperty(description: String) = buildJsonObject {
  put("type", JsonPrimitive("integer"))
  put("description", JsonPrimitive(description))
}

private fun enumProperty(description: String, values: List<String>) = buildJsonObject {
  put("type", JsonPrimitive("string"))
  put("description", JsonPrimitive(description))
  put("enum", JsonArray(values.map { JsonPrimitive(it) }))
}

private fun JsonObject?.string(name: String): String? = this
  ?.get(name)
  ?.jsonPrimitive
  ?.content
  ?.takeIf { it.isNotBlank() }

private fun JsonObject?.boolean(name: String, default: Boolean = false): Boolean = this
  ?.get(name)
  ?.jsonPrimitive
  ?.booleanOrNull
  ?: default

private fun JsonObject?.int(name: String): Int? = this
  ?.get(name)
  ?.jsonPrimitive
  ?.intOrNull

private fun JsonObject?.stringArray(name: String): List<String> = when (val value = this?.get(name)) {
  is JsonArray -> value.mapNotNull { element -> element.jsonPrimitive.content.takeIf { it.isNotBlank() } }
  null -> emptyList()
  else -> listOfNotNull(value.jsonPrimitive.content.takeIf { it.isNotBlank() })
}

private fun buildArgs(block: MutableList<String>.() -> Unit): Array<String> = buildList(block).toTypedArray()

private fun MutableList<String>.addOptional(option: String?, value: String?) {
  if (value == null) return
  if (option != null) add(option)
  add(value)
}

private fun MutableList<String>.addRepeated(option: String?, values: List<String>) {
  values.forEach { value -> addOptional(option, value) }
}

private fun MutableList<String>.addFlag(option: String, enabled: Boolean) {
  if (enabled) add(option)
}

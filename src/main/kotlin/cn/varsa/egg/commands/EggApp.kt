package cn.varsa.egg.commands

import cn.varsa.cli.core.CliArgs
import cn.varsa.cli.core.CliCompletion
import cn.varsa.cli.core.CliCommandGroup
import cn.varsa.cli.core.CliDsl
import cn.varsa.cli.core.CliException
import cn.varsa.egg.git.GitApi
import cn.varsa.egg.git.RewordMode
import cn.varsa.egg.github.GitHubApi
import cn.varsa.egg.output.Output
import java.nio.file.Path

class EggApp(
  private val gitHubApi: GitHubApi,
  private val gitApi: GitApi,
  private val output: Output,
  private val workingDirProvider: () -> Path = { Path.of(".").toAbsolutePath().normalize() },
  private val stdinProvider: () -> String = { System.`in`.bufferedReader().readText() }
) {
  fun commandTree(): CliCommandGroup = CliDsl.group(
    name = "egg",
    description = "Unified CLI for GitHub and git helpers",
    children = listOf(ghGroup(), gitGroup(), completionGroup(), completeLeaf())
  )

  private fun completionGroup() = CliDsl.group(
    name = "completion",
    description = "Shell completion helpers",
    children = listOf(
      outputLeafNoArgs(
        name = "zsh",
        description = "Generate zsh completion script",
        handler = { _ -> CliCompletion.zshScript("egg") }
      )
    )
  )

  private fun completeLeaf() = CliDsl.output(
    name = "__complete",
    description = "Internal completion helper",
    print = output::println,
    mixinStandardHelpOptions = true
  ) { args ->
    CliCompletion.suggest(commandTree(), args.toList()).joinToString("\n")
  }

  private fun ghGroup() = CliDsl.group(
    name = "gh",
    description = "GitHub workflows",
    children = listOf(
      prGroup(),
      issuesGroup(),
      outputLeaf(
        name = "search-prs",
        description = "Search KNIME PRs by issue key",
        handler = { wd, args ->
          requireArgCount(args, 1, "egg gh search-prs ISSUE_KEY")
          gitHubApi.searchPrs(wd, args[0])
        }
      ),
      outputLeaf(
        name = "search",
        description = "Search code in KNIME organization",
        handler = { wd, args -> gitHubApi.searchCode(wd, args.toList()) }
      ),
      outputLeaf(
        name = "look",
        description = "Show repo file contents",
        handler = { wd, args ->
          requireArgCount(args, 2..3, "egg gh look <owner/repo> <path> [ref]")
          gitHubApi.look(wd, repo = args[0], path = args[1], ref = args.getOrNull(2))
        }
      ),
      actionLeaf(
        name = "look-web",
        description = "Open repo file in browser",
        handler = { wd, args ->
          requireArgCount(args, 2..3, "egg gh look-web <owner/repo> <path> [ref]")
          gitHubApi.lookWeb(wd, repo = args[0], path = args[1], ref = args.getOrNull(2))
        }
      )
    )
  )

  private fun prGroup() = CliDsl.group(
    name = "pr",
    description = "Pull request helpers",
    children = listOf(
      currentPrGroup(),
      commentGroup(),
      threadGroup(),
      outputLeaf(
        name = "checks",
        description = "Show PR checks for given repo/pr",
        handler = { wd, args ->
          requireArgCount(args, 2, "egg gh pr checks <owner/repo> <pr-number>")
          gitHubApi.prChecks(wd, repo = args[0], prNumber = args[1])
        }
      ),
      outputLeaf(
        name = "feedback",
        description = "Show feedback for a PR",
        handler = { wd, args ->
          val (repo, pr) = PrFeedbackArgsParser.parse(args)
          gitHubApi.prFeedback(wd, repo = repo, prNumber = pr)
        }
      )
    )
  )

  private fun issuesGroup() = CliDsl.group(
    name = "issues",
    description = "Issue sync operations",
    children = listOf(
      outputLeaf(
        name = "pull",
        description = "Pull issues as sync entities",
        handler = { wd, args ->
          val request = IssuesPullArgsParser.parse(args)
          gitHubApi.issuesPull(wd, request)
        }
      ),
      actionLeaf(
        name = "push",
        description = "Push one issue sync entity from stdin",
        handler = { wd, args ->
          val request = IssuesPushArgsParser.parse(args, stdinProvider())
          val result = gitHubApi.issuesPush(wd, request)
          output.println(result.payload)
          if (result.exitCode != 0) throw CliException("", result.exitCode)
        }
      )
    )
  )

  private fun currentPrGroup() = CliDsl.group(
    name = "current",
    description = "Current PR details",
    children = listOf(
      outputLeafNoArgs(
        name = "id",
        description = "Print current PR id",
        handler = { wd -> gitHubApi.currentPrId(wd) }
      ),
      outputLeafNoArgs(
        name = "url",
        description = "Print current PR url",
        handler = { wd -> gitHubApi.currentPrUrl(wd) }
      ),
      actionLeafNoArgs(
        name = "web",
        description = "Open current PR in browser",
        handler = { wd -> gitHubApi.currentPrWeb(wd) }
      ),
      outputLeafNoArgs(
        name = "checks",
        description = "Show checks for current PR",
        handler = { wd -> gitHubApi.currentPrChecks(wd) }
      ),
      outputLeaf(
        name = "review-status",
        description = "Show current PR review status",
        handler = { wd, args ->
          val json = CliArgs.singleFlag(args, "--json", "egg gh pr current review-status [--json]")
          gitHubApi.currentPrReviewStatus(wd, json)
        }
      ),
      outputLeafNoArgs(
        name = "feedback",
        description = "Show feedback for current PR",
        handler = { wd -> gitHubApi.currentPrFeedback(wd) }
      )
    )
  )

  private fun commentGroup() = CliDsl.group(
    name = "comment",
    description = "Review comment operations",
    children = listOf(
      outputLeaf(
        name = "reply",
        description = "Reply to review comment ids",
        handler = { wd, args ->
          val request = ReplyArgsParser.parse(args)
          gitHubApi.replyToReviewComments(wd, request)
        }
      ),
      outputLeaf(
        name = "resolve",
        description = "Resolve review threads by comment ids",
        handler = { wd, args ->
          val request = ResolveArgsParser.parse(args)
          gitHubApi.resolveReviewComments(wd, request)
        }
      )
    )
  )

  private fun threadGroup() = CliDsl.group(
    name = "thread",
    description = "Review thread sync operations",
    children = listOf(
      outputLeaf(
        name = "pull",
        description = "Pull PR review threads as sync entities",
        handler = { wd, args ->
          val request = ThreadPullArgsParser.parse(args)
          gitHubApi.prThreadPull(wd, request)
        }
      ),
      actionLeaf(
        name = "push",
        description = "Push one thread sync entity from stdin",
        handler = { wd, args ->
          val request = ThreadPushArgsParser.parse(args, stdinProvider())
          val result = gitHubApi.prThreadPush(wd, request)
          output.println(result.payload)
          if (result.exitCode != 0) throw CliException("", result.exitCode)
        }
      )
    )
  )

  private fun gitGroup() = CliDsl.group(
    name = "git",
    description = "Git helpers",
    children = listOf(
      CliDsl.group(
        name = "worktree",
        description = "Worktree helpers",
        children = listOf(
          actionLeaf(
            name = "make",
            description = "Create worktree from ~/repos",
            handler = { wd, args ->
              val parsed = WorktreeMakeArgsParser.parse(args)
              gitApi.makeWorktree(
                wd,
                repoName = parsed.repoName,
                branch = parsed.branch,
                subdir = parsed.subdir,
                override = parsed.override
              )
            }
          )
        )
      ),
      outputLeafNoArgs(
        name = "generate-commit-message",
        description = "Generate commit message for staged diff",
        handler = { wd -> gitApi.generateCommitMessage(wd) }
      ),
      outputLeaf(
        name = "changed-paths",
        description = "List changed absolute paths",
        handler = { wd, args ->
          val (staged, range) = ChangedPathsArgsParser.parse(args)
          gitApi.changedPaths(wd, staged = staged, range = range)
        }
      ),
      outputLeaf(
        name = "local-ignore",
        description = "Add a local ignore pattern",
        handler = { wd, args ->
          requireArgCount(args, 1, "egg git local-ignore <pattern>", exitCode = 1)
          gitApi.localIgnore(wd, args[0])
        }
      ),
      outputLeafNoArgs(
        name = "local-ignore-eclipse",
        description = "Ignore common Eclipse files",
        handler = { wd -> gitApi.localIgnoreEclipse(wd) }
      ),
      CliDsl.group(
        name = "ahead",
        description = "Show commits ahead of references",
        children = listOf(
          outputLeafNoArgs(
            name = "feature",
            description = "Show commits ahead of upstream",
            handler = { wd -> gitApi.aheadFeature(wd) }
          ),
          outputLeafNoArgs(
            name = "master",
            description = "Show commits ahead of origin/master",
            handler = { wd -> gitApi.aheadMaster(wd) }
          )
        )
      ),
      CliDsl.group(
        name = "behind",
        description = "Show commits behind references",
        children = listOf(
          outputLeafNoArgs(
            name = "master",
            description = "Show commits behind master",
            handler = { wd -> gitApi.behindMaster(wd) }
          )
        )
      ),
      CliDsl.group(
        name = "update",
        description = "Update local references",
        children = listOf(
          actionLeafNoArgs(
            name = "master",
            description = "Reset local master to origin/master",
            handler = { wd -> gitApi.updateMaster(wd) }
          )
        )
      ),
      CliDsl.group(
        name = "clone",
        description = "Clone repositories",
        children = listOf(
          actionLeaf(
            name = "branch",
            description = "Clone a single branch",
            handler = { wd, args ->
              requireArgCount(args, 2, "egg git clone branch <owner/repo> <branch>")
              gitApi.cloneBranch(wd, repo = args[0], branch = args[1])
            }
          ),
          actionLeaf(
            name = "knime",
            description = "Clone a knime repository",
            handler = { wd, args ->
              requireArgCount(args, 1, "egg git clone knime <repo-name>")
              gitApi.cloneKnime(wd, repo = args[0])
            }
          )
        )
      ),
      actionLeaf(
        name = "reword",
        description = "Reword recent commits",
        handler = { wd, args ->
          val parsed = RewordArgsParser.parse(args)
          if (args.isEmpty()) {
            println(
              "No arguments supplied; defaulting to --issue-ids using the " +
                "current branch and author ${parsed.request.authorEmail}"
            )
          } else if (parsed.defaultedMode) {
            println(
              "Defaulting to --issue-ids using the current branch and " +
                "author ${parsed.request.authorEmail}"
            )
          }
          gitApi.reword(wd, parsed.request)
        }
      )
    )
  )

  private fun outputLeaf(
    name: String,
    description: String,
    handler: (Path, Array<String>) -> String
  ) = CliDsl.output(
    name = name,
    description = description,
    print = output::println,
    mixinStandardHelpOptions = true
  ) { args ->
    handler(workingDirProvider(), args)
  }

  private fun outputLeafNoArgs(
    name: String,
    description: String,
    handler: (Path) -> String
  ) = outputLeaf(name, description) { wd, _ -> handler(wd) }

  private fun actionLeaf(
    name: String,
    description: String,
    handler: (Path, Array<String>) -> Unit
  ) = CliDsl.action(
    name = name,
    description = description,
    mixinStandardHelpOptions = true
  ) { args ->
    handler(workingDirProvider(), args)
  }

  private fun actionLeafNoArgs(
    name: String,
    description: String,
    handler: (Path) -> Unit
  ) = actionLeaf(name, description) { wd, _ -> handler(wd) }

  private fun requireArgCount(
    args: Array<String>,
    expected: Int,
    usage: String,
    exitCode: Int = 2
  ) {
    CliArgs.requireArgCount(args, expected, usage, exitCode)
  }

  private fun requireArgCount(
    args: Array<String>,
    allowed: IntRange,
    usage: String,
    exitCode: Int = 2
  ) {
    CliArgs.requireArgCount(args, allowed, usage, exitCode)
  }
}

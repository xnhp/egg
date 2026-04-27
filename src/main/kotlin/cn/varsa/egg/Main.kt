package cn.varsa.egg

import cn.varsa.cli.core.CliMain
import cn.varsa.egg.commands.EggApp
import cn.varsa.egg.git.GitCliApi
import cn.varsa.egg.github.GhCliGitHubApi
import cn.varsa.egg.output.StdOutput
import cn.varsa.egg.runtime.SystemProcessRunner

fun main(args: Array<String>) {
  val processRunner = SystemProcessRunner()
  val gitHubApi = GhCliGitHubApi(processRunner)
  val gitApi = GitCliApi(processRunner)
  val app = EggApp(gitHubApi = gitHubApi, gitApi = gitApi, output = StdOutput())
  val exitCode = CliMain.run(app.commandTree(), args)
  if (exitCode != 0) {
    System.exit(exitCode)
  }
}

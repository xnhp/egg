package cn.varsa.egg

import cn.varsa.cli.core.CliMcpRegistrationConfig
import cn.varsa.cli.core.runCliMcpStdioServer
import cn.varsa.egg.commands.EggApp
import kotlinx.coroutines.runBlocking

fun runEggMcpServer(app: EggApp = createEggApp()) = runBlocking {
  loadDotEnv()
  runCliMcpStdioServer(
    root = app.mcpWorkflowCommand(),
    name = "egg-cli",
    version = detectedVersion(),
    config = CliMcpRegistrationConfig()
  )
}

fun main(args: Array<String>) {
  if (args.isNotEmpty()) {
    System.err.println("Warning: ignoring CLI arguments ${args.joinToString(" ")}")
  }
  runEggMcpServer()
}

private fun detectedVersion(): String {
  val pkg = object {}::class.java.`package`
  return pkg?.implementationVersion?.takeIf { it.isNotBlank() } ?: "0.0.0"
}

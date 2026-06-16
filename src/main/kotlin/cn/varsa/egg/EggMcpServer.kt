package cn.varsa.egg

import cn.varsa.cli.core.CliMcpRegistrationConfig
import cn.varsa.egg.commands.EggApp
import io.ktor.http.HttpMethod
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.cors.routing.CORS
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import java.net.URI
import java.nio.file.Path
import java.nio.file.Paths

data class EggMcpServerConfig(
  val host: String,
  val port: Int,
  val workingDirectory: Path?,
  val allowAnyOrigin: Boolean,
  val allowedOrigins: List<String>,
  val serverName: String,
  val serverVersion: String
) {
  companion object {
    private const val DEFAULT_HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 3031

    fun fromEnvironment(): EggMcpServerConfig {
      val host = System.getenv("EGG_MCP_HOST")?.takeIf { it.isNotBlank() } ?: DEFAULT_HOST
      val port = System.getenv("EGG_MCP_PORT")?.toIntOrNull()?.coerceIn(1, 65535) ?: DEFAULT_PORT
      val workingDir = System.getenv("EGG_MCP_WORKDIR")?.let { toPathOrNull(it) }
      val allowAnyOrigin = System.getenv("EGG_MCP_ALLOW_ANY_ORIGIN")?.toBooleanStrictOrNull() ?: true
      val origins = System.getenv("EGG_MCP_ALLOW_ORIGINS")
        ?.split(',')
        ?.mapNotNull { it.trim().ifEmpty { null } }
        ?.distinct()
        ?: emptyList()
      val name = System.getenv("EGG_MCP_NAME")?.takeIf { it.isNotBlank() } ?: "egg-cli"
      val version = System.getenv("EGG_MCP_VERSION")?.takeIf { it.isNotBlank() } ?: detectedVersion()
      return EggMcpServerConfig(
        host = host,
        port = port,
        workingDirectory = workingDir,
        allowAnyOrigin = if (origins.isNotEmpty()) false else allowAnyOrigin,
        allowedOrigins = origins,
        serverName = name,
        serverVersion = version
      )
    }

    private fun toPathOrNull(value: String?): Path? = value
      ?.takeIf { it.isNotBlank() }
      ?.let { runCatching { Paths.get(it).toAbsolutePath().normalize() }.getOrNull() }

    private fun detectedVersion(): String {
      val pkg = EggMcpServerConfig::class.java.`package`
      return pkg?.implementationVersion?.takeIf { it.isNotBlank() } ?: "0.0.0"
    }
  }
}

fun startEggMcpServer(
  config: EggMcpServerConfig = EggMcpServerConfig.fromEnvironment(),
  appProvider: () -> EggApp = ::createEggApp
): EmbeddedServer<CIOApplicationEngine, CIOApplicationEngine.Configuration> {
  val workingDirectory = config.workingDirectory
  val registration = CliMcpRegistrationConfig(
    workingDirectoryProvider = {
      workingDirectory ?: Paths.get("").toAbsolutePath().normalize()
    }
  )

  val app = appProvider()
  val mcpServer = Server(
    serverInfo = Implementation(
      name = config.serverName,
      version = config.serverVersion
    ),
    options = ServerOptions(
      capabilities = ServerCapabilities(
        tools = ServerCapabilities.Tools(listChanged = false)
      )
    )
  )

  mcpServer.registerEggTools(app, registration)

  return embeddedServer(CIO, host = config.host, port = config.port) {
    install(CORS) {
      if (config.allowAnyOrigin) {
        anyHost()
      } else {
        config.allowedOrigins
          .mapNotNull(::parseOrigin)
          .forEach { origin ->
            allowHost(origin.hostPort, schemes = origin.schemes)
          }
      }
      allowMethod(HttpMethod.Get)
      allowMethod(HttpMethod.Post)
      allowMethod(HttpMethod.Options)
      allowNonSimpleContentTypes = true
      allowHeader("Mcp-Session-Id")
      allowHeader("Mcp-Protocol-Version")
      exposeHeader("Mcp-Session-Id")
      exposeHeader("Mcp-Protocol-Version")
    }

    mcpStreamableHttp {
      mcpServer
    }
  }
}

fun runEggMcpServer(config: EggMcpServerConfig = EggMcpServerConfig.fromEnvironment()) {
  loadDotEnv()
  val engine = startEggMcpServer(config)
  println("Egg MCP server listening on ${config.host}:${config.port}")
  config.workingDirectory?.let { println("Using working directory $it") }
  engine.start(wait = true)
}

fun main(args: Array<String>) {
  if (args.isNotEmpty()) {
    System.err.println("Warning: ignoring CLI arguments ${args.joinToString(" ")}")
  }
  runEggMcpServer(EggMcpServerConfig.fromEnvironment())
}

private data class ParsedOrigin(val hostPort: String, val schemes: List<String>)

private fun parseOrigin(value: String): ParsedOrigin? {
  val uri = runCatching { URI(value) }.getOrNull() ?: return null
  val host = uri.host ?: return null
  val port = uri.port.takeIf { it != -1 }
  val hostPort = buildString {
    append(host)
    if (port != null) {
      append(":")
      append(port)
    }
  }
  val scheme = uri.scheme ?: return null
  return ParsedOrigin(hostPort = hostPort, schemes = listOf(scheme))
}

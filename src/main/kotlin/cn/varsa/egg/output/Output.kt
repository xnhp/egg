package cn.varsa.egg.output

interface Output {
  fun println(message: String)
}

class StdOutput : Output {
  override fun println(message: String) {
    kotlin.io.println(message)
  }
}

class BufferedOutput : Output {
  private val lines = mutableListOf<String>()

  override fun println(message: String) {
    lines += message
  }

  fun lines(): List<String> = lines.toList()
}

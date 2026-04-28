package cn.varsa.egg.github

import cn.varsa.cli.core.CliException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ThreadSyncTest {
  @Test
  fun `fingerprint is stable for same snapshot`() {
    val snapshot = baseThread()

    val first = ThreadSyncFingerprint.compute(snapshot)
    val second = ThreadSyncFingerprint.compute(snapshot.copy())

    assertEquals(first, second)
    assertEquals(true, first.startsWith("sha256:"))
  }

  @Test
  fun `fingerprint changes when resolution changes`() {
    val baseline = ThreadSyncFingerprint.compute(baseThread(isResolved = false))
    val changed = ThreadSyncFingerprint.compute(baseThread(isResolved = true))

    assertNotEquals(baseline, changed)
  }

  @Test
  fun `delta derive append and resolve`() {
    val remote = baseThread(isResolved = false)
    val local = remote.copy(
      isResolved = true,
      comments = remote.comments + ThreadCommentSnapshot(null, null, "new reply", null, null, null)
    )

    val delta = ThreadDeltaDeriver.derive(remote, local)

    assertEquals(listOf("new reply"), delta.appendedBodies)
    assertEquals(ResolutionOpType.RESOLVE, delta.resolutionOp)
  }

  @Test
  fun `delta derive unresolve and noop`() {
    val remote = baseThread(isResolved = true)
    val local = remote.copy(isResolved = false)
    val delta = ThreadDeltaDeriver.derive(remote, local)

    assertEquals(emptyList(), delta.appendedBodies)
    assertEquals(ResolutionOpType.UNRESOLVE, delta.resolutionOp)

    val noop = ThreadDeltaDeriver.derive(remote, remote)
    assertEquals(emptyList(), noop.appendedBodies)
    assertEquals(null, noop.resolutionOp)
  }

  @Test
  fun `delta derive rejects modification of existing comment`() {
    val remote = baseThread()
    val local = remote.copy(
      comments = listOf(remote.comments.first().copy(body = "changed"))
    )

    val error = assertFailsWith<CliException> { ThreadDeltaDeriver.derive(remote, local) }
    assertEquals(2, error.exitCode)
  }

  @Test
  fun `delta derive rejects deletion of existing comment`() {
    val remote = baseThread(
      comments = listOf(
        ThreadCommentSnapshot(1, "alice", "a", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null),
        ThreadCommentSnapshot(2, "bob", "b", "2026-01-01T00:01:00Z", "2026-01-01T00:01:00Z", null)
      )
    )
    val local = remote.copy(comments = listOf(remote.comments.first()))

    val error = assertFailsWith<CliException> { ThreadDeltaDeriver.derive(remote, local) }
    assertEquals(2, error.exitCode)
  }

  @Test
  fun `delta derive rejects reordering of existing comments`() {
    val remote = baseThread(
      comments = listOf(
        ThreadCommentSnapshot(1, "alice", "a", "2026-01-01T00:00:00Z", "2026-01-01T00:00:00Z", null),
        ThreadCommentSnapshot(2, "bob", "b", "2026-01-01T00:01:00Z", "2026-01-01T00:01:00Z", null)
      )
    )
    val local = remote.copy(comments = remote.comments.reversed())

    val error = assertFailsWith<CliException> { ThreadDeltaDeriver.derive(remote, local) }
    assertEquals(2, error.exitCode)
  }

  private fun baseThread(
    isResolved: Boolean = false,
    comments: List<ThreadCommentSnapshot> = listOf(
      ThreadCommentSnapshot(
        id = 101,
        author = "alice",
        body = "root",
        createdAt = "2026-01-01T00:00:00Z",
        updatedAt = "2026-01-01T00:00:00Z",
        url = "https://example.test/c/101"
      )
    )
  ) = ThreadSnapshot(
    id = "THREAD_1",
    isResolved = isResolved,
    isOutdated = false,
    path = "src/Foo.kt",
    line = 42,
    comments = comments
  )
}

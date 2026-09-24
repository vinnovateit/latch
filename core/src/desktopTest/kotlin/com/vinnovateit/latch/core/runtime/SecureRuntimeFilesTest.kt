package com.vinnovateit.latch.core.runtime

import com.vinnovateit.latch.desktop.platform.SecureFileWriter
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The IPC token and owner metadata decide who owns the runtime, so they get
 * the credential stores' all-or-nothing replacement. OS-agnostic, so the
 * Windows CI job can run it against a real Windows filesystem too.
 */
class SecureRuntimeFilesTest {
    private lateinit var directory: File

    @BeforeTest
    fun setUp() {
        directory = createTempDirectory("latch-runtime-files-").toFile()
    }

    @AfterTest
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun `a replaced token and metadata are what a fresh instance reads`() {
        val files = SecureRuntimeFiles(directory)
        files.writeToken("first-token")
        files.writeMetadata(metadata(port = 1111))

        files.writeToken("second-token")
        files.writeMetadata(metadata(port = 2222))

        val reopened = SecureRuntimeFiles(directory)
        assertEquals("second-token", reopened.readToken())
        assertEquals(2222, reopened.readMetadata()?.port)
        assertEquals(emptyList(), leftoverTempFiles())
    }

    @Test
    fun `without atomic rename the write fails and the previous token survives`() {
        SecureRuntimeFiles(directory).writeToken("current-token")
        val files = SecureRuntimeFiles(
            directory,
            SecureFileWriter(ownerOnly = false, move = { source, target ->
                throw AtomicMoveNotSupportedException(source.toString(), target.toString(), "simulated")
            }),
        )

        assertFailsWith<AtomicMoveNotSupportedException> { files.writeToken("replacement-token") }

        assertEquals("current-token", SecureRuntimeFiles(directory).readToken())
        assertEquals(emptyList(), leftoverTempFiles())
    }

    @Test
    fun `a temp file that cannot be restricted to its owner fails the write and keeps the previous token`() {
        if (!Files.getFileStore(directory.toPath()).supportsFileAttributeView("posix")) return
        SecureRuntimeFiles(directory).writeToken("current-token")
        val files = SecureRuntimeFiles(
            directory,
            SecureFileWriter(ownerOnly = true, createTemp = { dir: Path, prefix: String ->
                Files.createTempFile(dir, prefix, ".tmp", PosixFilePermissions.asFileAttribute(WORLD_READABLE))
            }),
        )

        assertFailsWith<IllegalStateException> { files.writeToken("replacement-token") }

        assertEquals("current-token", SecureRuntimeFiles(directory).readToken())
        assertEquals(emptyList(), leftoverTempFiles())
    }

    private fun metadata(port: Int) = OwnerMetadata(
        version = INSTANCE_PROTOCOL_VERSION,
        ownerKind = OwnerKind.CLI_ONESHOT,
        port = port,
        pid = ProcessHandle.current().pid(),
        startedAt = 0,
    )

    private fun leftoverTempFiles(): List<String> =
        directory.listFiles().orEmpty().map { it.name }.filter { it.endsWith(".tmp") }

    private companion object {
        val WORLD_READABLE = PosixFilePermissions.fromString("rw-r--r--")
    }
}

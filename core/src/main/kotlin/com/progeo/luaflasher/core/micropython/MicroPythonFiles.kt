package com.progeo.luaflasher.core.micropython

import com.progeo.luaflasher.core.FlasherException
import com.progeo.luaflasher.core.io.sha256Hex
import com.progeo.luaflasher.core.m5.pyString
import java.io.ByteArrayOutputStream
import java.util.Base64

class FileTransferException(path: String, detail: String) : FlasherException(
    "Copying $path to the M5 failed. Retry; the update can be repeated safely.",
    "transfer of $path: $detail",
)

/**
 * Files on a MicroPython board over the [RawRepl]. [init] defines a few helpers on the board once
 * per session (all names start with `_pg_`); every other call is one short line using them, so the
 * code sent per call stays small and fixed in shape (the test fake recognises exactly these calls).
 * Content travels as base64; writes are checked with SHA-256 computed on the board.
 */
class MicroPythonFiles(private val repl: RawRepl) {

    suspend fun init() {
        repl.exec(HELPERS)
    }

    /** Size in bytes, or null if [path] does not exist. */
    suspend fun size(path: String): Long? =
        repl.exec("_pg_s(${pyString(path)})").trim().toLongOrNull()?.takeIf { it >= 0 }

    /** Creates [dir] and its parents. */
    suspend fun mkdirs(dir: String) {
        repl.exec("_pg_md(${pyString(dir)})")
    }

    /** SHA-256 (hex) and size of [path] as the board reads it; null if missing. */
    suspend fun sha256(path: String): Pair<String, Long>? {
        val out = repl.exec("_pg_h(${pyString(path)})", HASH_TIMEOUT_MS).trim()
        if (out == "-") return null
        val parts = out.split(' ')
        if (parts.size != 2) throw FileTransferException(path, "bad hash answer '$out'")
        return parts[0] to parts[1].toLong()
    }

    /** Writes [data] to [path] (replacing it) and checks it on the board. */
    suspend fun write(path: String, data: ByteArray, onProgress: (Int) -> Unit = {}) {
        val b64 = Base64.getEncoder()
        if (data.isEmpty()) repl.exec("_pg_w(${pyString(path)},'',0)")
        var off = 0
        while (off < data.size) {
            val n = minOf(WRITE_CHUNK, data.size - off)
            val chunk = b64.encodeToString(data.copyOfRange(off, off + n))
            repl.exec("_pg_w(${pyString(path)},'$chunk',${if (off == 0) 0 else 1})")
            off += n
            onProgress(n)
        }
        val (sha, size) = sha256(path) ?: throw FileTransferException(path, "missing after write")
        if (size != data.size.toLong() || !sha.equals(sha256Hex(data), ignoreCase = true)) {
            throw FileTransferException(path, "check failed: $size bytes, sha $sha")
        }
    }

    /** Content of [path], or null if it does not exist. */
    suspend fun read(path: String): ByteArray? {
        val size = size(path) ?: return null
        val out = ByteArrayOutputStream(size.toInt())
        val b64 = Base64.getDecoder()
        var off = 0L
        while (off < size) {
            val text = repl.exec("_pg_r(${pyString(path)},$off,$READ_CHUNK)").trim()
            val bytes = try {
                b64.decode(text)
            } catch (e: IllegalArgumentException) {
                throw FileTransferException(path, "bad base64 at $off")
            }
            if (bytes.isEmpty()) break
            out.write(bytes)
            off += bytes.size
        }
        if (out.size().toLong() != size) throw FileTransferException(path, "read ${out.size()} of $size bytes")
        return out.toByteArray()
    }

    /** Renames each `from` to its `to` in one call, replacing existing files (short window). */
    suspend fun moveAll(moves: List<Pair<String, String>>) {
        if (moves.isEmpty()) return
        val list = moves.joinToString(",", "[", "]") { (a, b) -> "(${pyString(a)},${pyString(b)})" }
        repl.exec("_pg_mvall($list)")
    }

    suspend fun remove(path: String) {
        repl.exec("_pg_rm(${pyString(path)})")
    }

    companion object {
        /** Raw bytes per write call: 1368 base64 chars, about 1.4 KB per exec. */
        const val WRITE_CHUNK = 1024
        const val READ_CHUNK = 1536
        const val HASH_TIMEOUT_MS = 30_000L

        /**
         * Helpers defined on the board. binascii/hashlib/os: the plain names exist in every
         * MicroPython since 1.19 (the u-prefixed ones are gone in newer builds).
         */
        val HELPERS = """
            import os, binascii, hashlib
            def _pg_w(p, d, a):
                with open(p, 'ab' if a else 'wb') as f:
                    f.write(binascii.a2b_base64(d))
            def _pg_h(p):
                try:
                    f = open(p, 'rb')
                except OSError:
                    print('-')
                    return
                h = hashlib.sha256()
                n = 0
                with f:
                    while True:
                        b = f.read(1024)
                        if not b:
                            break
                        h.update(b)
                        n += len(b)
                print(binascii.hexlify(h.digest()).decode(), n)
            def _pg_r(p, o, n):
                with open(p, 'rb') as f:
                    f.seek(o)
                    print(binascii.b2a_base64(f.read(n)).decode().strip())
            def _pg_s(p):
                try:
                    print(os.stat(p)[6])
                except OSError:
                    print(-1)
            def _pg_md(p):
                q = ''
                for s in p.strip('/').split('/'):
                    q += '/' + s
                    try:
                        os.mkdir(q)
                    except OSError:
                        pass
            def _pg_rm(p):
                try:
                    os.remove(p)
                except OSError:
                    pass
            def _pg_mvall(m):
                for a, b in m:
                    _pg_rm(b)
                    os.rename(a, b)
        """.trimIndent() + "\n"
    }
}

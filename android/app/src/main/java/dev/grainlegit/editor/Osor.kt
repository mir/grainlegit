package dev.grainlegit.editor

import android.content.Context
import android.os.Build
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipInputStream

internal class OsorAssets(private val context: Context) {
    private data class Graph(val pack: Int, val name: String, val size: Long, val sha256: String) {
        val wrapper get() = name.removeSuffix("_qnn.bin") + ".onnx"
        fun installed(dir: File) = File(dir, name).let { it.isFile && it.length() == size }
    }

    val directory = File(context.noBackupFilesDir, "osor-sm8850-phase2-1")
    private val graphs by lazy {
        context.assets.open("$ASSETS/contexts.tsv").bufferedReader().useLines { lines ->
            lines.map { line ->
                val (pack, name, bytes, sha256) = line.split('\t')
                Graph(pack.toInt(), name, bytes.toLong(), sha256)
            }.toList()
        }
    }
    private val wrappers by lazy { graphs.map { it.wrapper } + "pipeline.json" }
    val installed: Boolean
        get() = wrappers.all { File(directory, it).isFile } && graphs.all { it.installed(directory) }

    fun license(): String = context.assets.open("licenses/OSOR-SDXL-Model.txt").bufferedReader().use { it.readText() }

    suspend fun download(onProgress: suspend (Int) -> Unit) = withContext(Dispatchers.IO) {
        installLock.withLock {
            if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Could not create model storage")
            val missing = graphs.filterNot { it.installed(directory) }
            val bytes = missing.sumOf { it.size }
            if (bytes > directory.usableSpace) throw IOException("Free more space and try the OSOR download again")
            val total = graphs.sumOf { it.size }
            var received = total - bytes
            var percentage = -1
            suspend fun progress(count: Int) {
                received += count
                val next = (received * 100 / total).toInt()
                if (next != percentage) {
                    percentage = next
                    onProgress(next)
                }
            }
            progress(0)
            for ((pack, entries) in missing.groupBy { it.pack }) {
                downloadPack(pack, entries, ::progress)
            }
            for (name in wrappers) {
                val file = File(directory, name)
                context.assets.open("$ASSETS/$name").use { input ->
                    val temporary = File(directory, "$name.part")
                    try {
                        temporary.outputStream().use { input.copyTo(it) }
                        if (!temporary.renameTo(file)) throw IOException("Could not install $name")
                    } finally {
                        temporary.delete()
                    }
                }
            }
            check(installed) { "The OSOR model download is incomplete" }
        }
    }

    private suspend fun downloadPack(pack: Int, entries: List<Graph>, progress: suspend (Int) -> Unit) {
        val connection = URI("$RELEASE/grainlegit-osor-sm8850-$pack.zip").toURL()
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 20_000
        val pending = entries.associateBy { it.name }.toMutableMap()
        val buffer = ByteArray(256 * 1024)
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("OSOR download returned HTTP ${connection.responseCode}")
            }
            ZipInputStream(connection.inputStream.buffered()).use { zip ->
                while (pending.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: throw IOException("An OSOR download is incomplete")
                    val graph = pending.remove(entry.name)
                    if (graph == null) {
                        while (zip.read(buffer) != -1) currentCoroutineContext().ensureActive()
                    } else {
                        val destination = File(directory, graph.name)
                        val temporary = File(directory, "${graph.name}.part")
                        val digest = MessageDigest.getInstance("SHA-256")
                        try {
                            var written = 0L
                            temporary.outputStream().use { output ->
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val count = zip.read(buffer)
                                    if (count == -1) break
                                    written += count
                                    if (written > graph.size) throw IOException("Invalid size for ${graph.name}")
                                    output.write(buffer, 0, count)
                                    digest.update(buffer, 0, count)
                                    progress(count)
                                }
                            }
                            val actual = digest.digest().joinToString("") { "%02x".format(it) }
                            if (written != graph.size || actual != graph.sha256 || !temporary.renameTo(destination)) {
                                throw IOException("Could not verify and install ${graph.name}")
                            }
                        } finally {
                            temporary.delete()
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private const val ASSETS = "models/osor-sm8850"
        private const val RELEASE = "https://github.com/mir/grainlegit/releases/download/v0.6"
        private val installLock = Mutex()
        val supported get() = Build.SOC_MODEL.equals("SM8850", ignoreCase = true)
    }
}

internal class Osor(context: Context, val directory: File) : Inpainter {
    private val start = SystemClock.elapsedRealtimeNanos()
    private val model = OsorModel(directory, File(context.applicationInfo.nativeLibraryDir))
    override val id = "osor-phase2"
    override val label = "OSOR"
    override val size = OsorModel.SIDE
    override val backend = Backend.NPU
    override val loadMs = elapsed(start)
    override var lastMs = 0.0
        private set
    /** Throws when the caller's work is cancelled; checked between graphs. */
    var checkActive: () -> Unit = {}

    fun preload() = model.preload(checkActive)

    override fun inpaint(pixels: IntArray, hole: BooleanArray): IntArray {
        val started = SystemClock.elapsedRealtimeNanos()
        val result = model.erase(pixels, hole, checkActive)
        lastMs = elapsed(started)
        return result
    }

    override fun close() = model.close()

    private fun elapsed(start: Long) = (SystemClock.elapsedRealtimeNanos() - start) / 1e6
}

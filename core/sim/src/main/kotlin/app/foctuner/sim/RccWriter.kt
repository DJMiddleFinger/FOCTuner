package app.foctuner.sim

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/**
 * Builds Qt resource bundles (rcc format 3, see `RccIndex`) for the simulated archive and tests: header,
 * data, names, tree, in the order Qt's own `rcc` writes them.
 */
object RccWriter {
    class File(val path: String, val data: ByteArray, val compress: Boolean = false)

    private class Node(val name: String) {
        val children = LinkedHashMap<String, Node>()
        var file: File? = null
        var nameOffset = 0
        var dataOffset = 0
        var index = 0
    }

    fun build(files: List<File>): ByteArray {
        val root = Node("")
        for (file in files) {
            var node = root
            for (part in file.path.trim('/').split('/')) node = node.children.getOrPut(part) { Node(part) }
            node.file = file
        }

        val data = ByteArrayOutputStream()
        val names = ByteArrayOutputStream()
        val nameOffsets = HashMap<String, Int>()

        val ordered = ArrayList<Node>()
        val queue = ArrayDeque(listOf(root))
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            node.index = ordered.size
            ordered += node
            node.children.values.forEach(queue::addLast)
        }
        for (node in ordered.drop(1)) {
            node.nameOffset = nameOffsets.getOrPut(node.name) {
                val at = names.size()
                names.write(ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN).putShort(node.name.length.toShort()).putInt(node.name.hashCode()).array())
                names.write(node.name.toByteArray(Charsets.UTF_16BE))
                at
            }
            node.file?.let { file ->
                node.dataOffset = data.size()
                val stored = if (file.compress) qCompress(file.data) else file.data
                data.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(stored.size).array())
                data.write(stored)
            }
        }

        val tree = ByteBuffer.allocate(ordered.size * 22).order(ByteOrder.BIG_ENDIAN)
        var nextChild = 1
        for (node in ordered) {
            tree.putInt(node.nameOffset)
            val file = node.file
            if (file == null) {
                tree.putShort(2).putInt(node.children.size).putInt(nextChild)
                nextChild += node.children.size
            } else {
                tree.putShort(if (file.compress) 1 else 0).putShort(0).putShort(0).putInt(node.dataOffset)
            }
            tree.putLong(0)
        }

        val dataStart = 24
        val namesStart = dataStart + data.size()
        val treeStart = namesStart + names.size()
        val header = ByteBuffer.allocate(24).order(ByteOrder.BIG_ENDIAN)
            .putInt(0x71726573).putInt(3).putInt(treeStart).putInt(dataStart).putInt(namesStart).putInt(0)
        return header.array() + data.toByteArray() + names.toByteArray() + tree.array()
    }

    private fun qCompress(bytes: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(bytes)
        deflater.finish()
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(bytes.size).array())
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }
}

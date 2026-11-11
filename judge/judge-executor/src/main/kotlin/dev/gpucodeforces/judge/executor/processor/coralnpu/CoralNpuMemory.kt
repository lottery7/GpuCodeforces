package dev.gpucodeforces.judge.executor.processor.coralnpu

import dev.gpucodeforces.judge.api.Signature
import java.nio.file.Files
import java.nio.file.Path

/**
 * The memories of the Coral NPU core in its default configuration, which is what Renode models: 8 KB the core only
 * executes from and 32 KB of data. A program sees nothing else; an access outside them raises the core's FAULT.
 */
object CoralNpuMemory {
    const val INSTRUCTION_START = 0x0L
    const val INSTRUCTION_BYTES = 0x2000L
    const val DATA_START = 0x10000L
    const val DATA_BYTES = 0x8000L
    const val DATA_END = DATA_START + DATA_BYTES

    /** Of the data memory's top, left to the stack whatever the test's arrays take. */
    const val STACK_RESERVE = 1024L

    /** Arrays start at multiples of this: a vector register is 16 bytes. */
    const val ARRAY_ALIGN = 16L

    fun inInstructionMemory(start: Long, bytes: Long) = start >= INSTRUCTION_START && start + bytes <= INSTRUCTION_START + INSTRUCTION_BYTES

    fun inDataMemory(start: Long, bytes: Long) = start >= DATA_START && start + bytes <= DATA_END
}

/** A loadable segment of an executable: [bytes] of memory from [address]. */
data class ElfSegment(val address: Long, val bytes: Long)

/** Reads what a 32-bit little-endian ELF executable occupies in memory. The file is the contestant's linker output. */
object Elf32 {
    private const val HEADER_BYTES = 52
    private const val PROGRAM_HEADER_BYTES = 32
    private const val PT_LOAD = 1L

    /** Throws [IllegalArgumentException] for anything that is not such an executable. */
    fun loadSegments(file: Path): List<ElfSegment> {
        val data = Files.readAllBytes(file)
        require(data.size >= HEADER_BYTES && data[0] == 0x7F.toByte() && data[1] == 'E'.code.toByte() && data[2] == 'L'.code.toByte() && data[3] == 'F'.code.toByte()) { "not an ELF file" }
        require(data[4] == 1.toByte() && data[5] == 1.toByte()) { "not a 32-bit little-endian ELF file" }
        fun u16(at: Int): Int = (data[at].toInt() and 0xFF) or ((data[at + 1].toInt() and 0xFF) shl 8)
        fun u32(at: Int): Long = u16(at).toLong() or (u16(at + 2).toLong() shl 16)
        val headers = u32(28)
        val entryBytes = u16(42)
        val count = u16(44)
        require(count == 0 || entryBytes >= PROGRAM_HEADER_BYTES) { "program headers of $entryBytes bytes" }
        require(headers + count.toLong() * entryBytes <= data.size) { "program headers beyond the end of the file" }
        return (0 until count).mapNotNull { i ->
            val at = (headers + i.toLong() * entryBytes).toInt()
            val bytes = u32(at + 20)
            if (u32(at) == PT_LOAD && bytes > 0) ElfSegment(u32(at + 8), bytes) else null
        }
    }
}

/** Where the arrays of one test lie in the data memory: param name -> address. */
class CoralNpuPlacement(val addresses: Map<String, Long>, val end: Long) {
    /** Whether the arrays leave the stack its reserve. */
    val fits: Boolean get() = end + CoralNpuMemory.STACK_RESERVE <= CoralNpuMemory.DATA_END

    companion object {
        /** The arrays of [sig] with [counts] elements each, one after another from [dataEnd] (the end of the program's own data). */
        fun of(sig: Signature, counts: Map<String, Long>, dataEnd: Long): CoralNpuPlacement {
            var next = dataEnd
            val addresses = sig.arrays.associate { p ->
                val address = (next + CoralNpuMemory.ARRAY_ALIGN - 1) / CoralNpuMemory.ARRAY_ALIGN * CoralNpuMemory.ARRAY_ALIGN
                next = address + counts.getValue(p.name) * p.type.bytes
                p.name to address
            }
            return CoralNpuPlacement(addresses, next)
        }
    }
}

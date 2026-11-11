package dev.gpucodeforces.judge.executor

import dev.gpucodeforces.judge.executor.processor.gpucuda.HarnessLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HarnessLineTest {
    @Test
    fun `lines of a normal run`() {
        assertEquals(HarnessLine.Ready, HarnessLine.parse("ready"))
        assertEquals(HarnessLine.Load(2), HarnessLine.parse("load 2"))
        assertEquals(HarnessLine.Run(2, first = true), HarnessLine.parse("run 2 0"))
        assertEquals(HarnessLine.Run(2, first = false), HarnessLine.parse("run 2 1"))
        assertEquals(HarnessLine.TestOk(2, timeNs = 1500, wallNs = 2500, runs = 3), HarnessLine.parse("test 2 ok time_ns 1500 wall_ns 2500 runs 3"))
        assertEquals(HarnessLine.Done, HarnessLine.parse("done"))
    }

    @Test
    fun `the number of runs is optional`() {
        assertEquals(HarnessLine.TestOk(1, timeNs = 7, wallNs = 9, runs = null), HarnessLine.parse("test 1 ok wall_ns 9 time_ns 7"))
    }

    @Test
    fun `failures keep their text`() {
        assertEquals(HarnessLine.RuntimeError(3, "an illegal memory access"), HarnessLine.parse("test 3 runtime_error an illegal memory access"))
        assertEquals(HarnessLine.AllocFailed(1, "C: 4096 bytes"), HarnessLine.parse("test 1 alloc_failed C: 4096 bytes"))
        assertEquals(HarnessLine.Fatal("cannot open /data/t1/A.bin"), HarnessLine.parse("fatal cannot open /data/t1/A.bin"))
    }

    @Test
    fun `lines the harness never writes are not parsed`() {
        assertNull(HarnessLine.parse(""))
        assertNull(HarnessLine.parse("passed 1"))
        assertNull(HarnessLine.parse("load"))
        assertNull(HarnessLine.parse("run first"))
        assertNull(HarnessLine.parse("test 1"))
        assertNull(HarnessLine.parse("test 1 accepted"))
        assertNull(HarnessLine.parse("test x ok time_ns 1 wall_ns 1"))
        // a result without its times cannot be checked against the limits
        assertNull(HarnessLine.parse("test 1 ok time_ns 1"))
        assertNull(HarnessLine.parse("test 1 ok time_ns fast wall_ns 1"))
    }
}

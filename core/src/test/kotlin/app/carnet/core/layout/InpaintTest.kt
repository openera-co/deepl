package app.carnet.core.layout

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

class InpaintTest {
    private fun naive(mask: BooleanArray, w: Int, h: Int, r: Int) = BooleanArray(mask.size).also { g ->
        for (y in 0 until h) for (x in 0 until w) if (mask[y * w + x])
            for (dy in -r..r) for (dx in -r..r) { val yy = y + dy; val xx = x + dx; if (yy in 0 until h && xx in 0 until w) g[yy * w + xx] = true }
    }

    @Test
    fun dilationMatchesNaive() {
        val rnd = Random(1)
        for (case in 0 until 30) {
            val w = rnd.nextInt(1, 90); val h = rnd.nextInt(1, 60); val r = rnd.nextInt(1, 8)
            val m = BooleanArray(w * h) { rnd.nextInt(100) < 7 }
            assertContentEquals(naive(m, w, h, r), Inpaint.dilateSquare(m, w, h, r), "case $case ${w}x$h r=$r")
        }
    }

    @Test
    fun speed() {
        val w = 2400; val h = 120; val r = 6
        val m = BooleanArray(w * h) { (it % 7 == 0) || (it / w % 9 < 3 && it % 3 == 0) }
        val t0 = System.nanoTime(); repeat(20) { naive(m, w, h, r) }; val a = (System.nanoTime() - t0) / 20_000_000.0
        val t1 = System.nanoTime(); repeat(20) { Inpaint.dilateSquare(m, w, h, r) }; val b = (System.nanoTime() - t1) / 20_000_000.0
        println("DILATE one line ${w}x$h r=$r: naive ${"%.1f".format(a)} ms, separable ${"%.1f".format(b)} ms")
    }
}

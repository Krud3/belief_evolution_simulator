package rng

import benchmarking.OptimizedRdtsc

import java.util.UUID
import scala.util.Random

class Xoshiro(seed: Long) {
    private enum XoroshiroType:
        case Xoroshiro512
        case Xoroshiro256
        case Xoroshiro128
        
    private var curType: XoroshiroType = XoroshiroType.Xoroshiro256
    private val s: Array[Long] = new Array[Long](16)
    init(XoroshiroType.Xoroshiro512)
    
    private def splitMix64(x: Long): Long = {
        var result = x + 0x9e3779b97f4a7c15L
        result = (result ^ (result >>> 30)) * 0xbf58476d1ce4e5b9L
        result = (result ^ (result >>> 27)) * 0x94d049bb133111ebL
        result ^ (result >>> 31)
    }
    
    private def init(xoroshiroType: XoroshiroType): Unit = {
        curType = xoroshiroType
        xoroshiroType match {
            case XoroshiroType.Xoroshiro512 =>
                s(0) = splitMix64(seed)
                var i = 1
                while (i < 8) {
                    s(i) = splitMix64(s(i - 1))
                    i += 1
                }
            case XoroshiroType.Xoroshiro256 =>
                s(0) = splitMix64(seed)
                s(1) = splitMix64(s(0))
                s(2) = splitMix64(s(1))
                s(3) = splitMix64(s(2))
                
            case XoroshiroType.Xoroshiro128 =>
                s(0) = splitMix64(seed)
                s(1) = splitMix64(s(0))
        }
    }
    
    inline private def rotateLeft(inline x: Long, inline k: Int): Long =
        (x << k) | (x >>> (64 - k))
    
    def xoshiro256StarStar(): Long = {
        val result = rotateLeft(s(1) * 5L, 7) * 9L
        
        val t = s(1) << 17
        
        s(2) ^= s(0)
        s(3) ^= s(1)
        s(1) ^= s(2)
        s(0) ^= s(3)
        
        s(2) ^= t
        s(3) = rotateLeft(s(3), 45)
        
        result
    }
    
    def xoshiro256StarStar(offSet: Int): Long = {
        val result = rotateLeft(s(1 + offSet) * 5L, 7) * 9L
        
        val t = s(1 + offSet) << 17
        
        s(2 + offSet) ^= s(0 + offSet)
        s(3 + offSet) ^= s(1 + offSet)
        s(1 + offSet) ^= s(2 + offSet)
        s(0 + offSet) ^= s(3 + offSet)
        
        s(2 + offSet) ^= t
        
        result
    }
    
    def bulkRNG256(destArr: Array[Long]): Unit = {
        var i = 0
        while (i < destArr.length) {
            destArr(i) = xoshiro256StarStar()
            i += 1
        }
    }
    
    def crazyTest(destArr: Array[UUID]): Unit = {
        var i = 0
        while (i < destArr.length) {
            destArr(i) = UUID(xoshiro256StarStar(), xoshiro256StarStar())
            i += 1
        }
    }
    
    def bulkRNGComp(destArr: Array[Long]): Unit = {
        val random = Random(seed)
        var i = 0
        while (i < destArr.length) {
            destArr(i) = random.nextLong()
            i += 1
        }
    }
    
}

object Tester extends App {
    def estimateCPUTimerFreq(millisToWait: Long = 100L): Long = {
        val osFreq = 1_000_000_000L
        val cpuStart = OptimizedRdtsc.getRdtsc()
        val osStart = System.nanoTime()
        
        var osEnd = 0L
        var osElapsed = 0L
        val osWaitTime = osFreq * millisToWait / 1000
        while (osElapsed < osWaitTime) {
            osEnd = System.nanoTime()
            osElapsed = osEnd - osStart
        }
        val cpuEnd = OptimizedRdtsc.getRdtsc()
        val cpuElapsed = cpuEnd - cpuStart
        var cpuFreq = 0L
        if (osElapsed > 0) {
            cpuFreq = osFreq * cpuElapsed / osElapsed
        }
        
        cpuFreq
    }
    
    def formatNanos(nanos: Long): String = {
        if (nanos < 1000) {
            f"$nanos%.2f ns"
        } else if (nanos < 1000000) {
            f"${nanos / 1000.0}%.2f µs" // using µs for microseconds
        } else if (nanos < 1000000000) {
            f"${nanos / 1000000.0}%.2f ms"
        } else {
            f"${nanos / 1000000000.0}%.2f s"
        }
    }
    
    def findDuplicates[T](arr: Array[T]): Unit = {
        // Create a map to store elements and their index lists
        var elementMap = Map[T, List[Int]]()
        
        // Iterate through array and build the map
        arr.zipWithIndex.foreach { case (element, index) =>
            elementMap = elementMap.updated(
                element,
                index :: elementMap.getOrElse(element, List())
                )
        }
        
        // Filter for elements that appear more than once and print them
        elementMap.filter(_._2.length > 1).foreach { case (element, indexes) =>
            println(s"Element '$element' appears at indexes: ${indexes.reverse.mkString(", ")}")
        }
    }
    
    def test(iters: Int, arrSize: Int): Unit = {
        var max = Long.MinValue
        var min = Long.MaxValue
        val cpuFreq = estimateCPUTimerFreq()
        val randsArr: Array[Long] = new Array[Long](arrSize)
        val randsArr2: Array[Long] = new Array[Long](arrSize)
        
        // Warmup
        val seed = System.nanoTime()
        val rand = Xoshiro(seed)
        val rand2 = Random(seed)
        var i = 0
        while (i < arrSize) {
            rand.xoshiro256StarStar()
            i += 1
        }
        
        var cumSum = 0L

        i = 0
        while (i < iters) {
            val start = OptimizedRdtsc.getRdtsc()
            // rand.bulkRNG256(randsArr)
            var j = 0
            while (j < arrSize) {
                randsArr(j) = rand.xoshiro256StarStar()
                j += 1
            }
            val stop = OptimizedRdtsc.getRdtsc()
            
            val time = ((stop - start).toDouble / cpuFreq * 1_000_000_000).toLong
            max = math.max(max, time)
            min = math.min(min, time)
            cumSum += time
            i += 1
        }
        
        println(s"For native xoshiro\n" +
                  s"Total Time: ${formatNanos(cumSum)}\n" +
                  s"Avg Time: ${formatNanos((cumSum.toDouble / iters).toLong)}\n" +
                  s"Max Time: ${formatNanos(max)}\n" +
                  s"Min Time: ${formatNanos(min)}\n")
        
        max = Long.MinValue
        min = Long.MaxValue
        cumSum = 0L
        
        i = 0
        while (i < iters) {
            val start = OptimizedRdtsc.getRdtsc()
            // rand.bulkRNGComp(randsArr2)
            var j = 0
            while (j < arrSize) {
                randsArr2(j) = rand2.nextLong()
                j += 1
            }
            val stop = OptimizedRdtsc.getRdtsc()
            
            val time = ((stop - start).toDouble / cpuFreq * 1_000_000_000).toLong
            max = math.max(max, time)
            min = math.min(min, time)
            cumSum += time
            
            i += 1
        }
        
        // Your print statement would become:
        println(s"For native random\n" +
                  s"Total Time: ${formatNanos(cumSum)}\n" +
                  s"Avg Time: ${formatNanos((cumSum.toDouble / iters).toLong)}\n" +
                  s"Max Time: ${formatNanos(max)}\n" +
                  s"Min Time: ${formatNanos(min)}\n")
        
        //println(randsArr.mkString("Array1(", ", ", ")\n\n"))
        //println(randsArr2.mkString("Array2(", ", ", ")"))
        findDuplicates(randsArr)
    }
    
    //test(100, 131072)
    
}

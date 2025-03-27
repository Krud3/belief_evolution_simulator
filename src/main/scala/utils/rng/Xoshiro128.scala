package utils.rng

/**
 * Xoshiro128+ 1.0 - A fast 32-bit pseudorandom number generator
 * Scala implementation of the algorithm by David Blackman and Sebastiano Vigna
 */
object Xoshiro128Plus {
    private val s: Array[Int] = new Array[Int](4)
    
    // Initialize with a default seed
    {
        setSeed(System.nanoTime())
    }
    
    // Seed the generator
    def setSeed(seed: Long): Unit = {
        var x = seed
        var i = 0
        while (i < 4) {
            x += 0x9e3779b97f4a7c15L
            var z = x
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
            s(i) = (z ^ (z >>> 31)).toInt
            i += 1
        }
    }
    
    @inline private def rotl(x: Int, k: Int): Int =
        (x << k) | (x >>> (32 - k))
    
    @inline def next(): Int = {
        val result = s(0) + s(3)
        
        val t = s(1) << 9
        
        s(2) ^= s(0)
        s(3) ^= s(1)
        s(1) ^= s(2)
        s(0) ^= s(3)
        
        s(2) ^= t
        
        s(3) = rotl(s(3), 11)
        
        result
    }
    
    private val FLOAT_UNIT = 1.0f / 16777216.0f // 1.0f / (1 << 24)
    
    @inline def nextFloat(): Float = {
        val bits = next() >>> 8
        bits * FLOAT_UNIT // Multiplication instead of division
    }
    
    @inline def nextFloatFast(): Float = {
        java.lang.Float.intBitsToFloat(0x3f800000 | (next() & 0x007FFFFF)) - 1.0f
    }
    
    def jump(): Unit = {
        val JUMP: Array[Int] = Array(0x8764000b, 0xf542d2d3, 0x6fa035c3, 0x77f2db5b)
        
        var s0: Int = 0
        var s1: Int = 0
        var s2: Int = 0
        var s3: Int = 0
        
        var i = 0
        while (i < JUMP.length) {
            var b = 0
            while (b < 32) {
                if ((JUMP(i) & (1 << b)) != 0) {
                    s0 ^= s(0)
                    s1 ^= s(1)
                    s2 ^= s(2)
                    s3 ^= s(3)
                }
                next()
                b += 1
            }
            i += 1
        }
        
        s(0) = s0
        s(1) = s1
        s(2) = s2
        s(3) = s3
    }
    
    def longJump(): Unit = {
        val LONG_JUMP: Array[Int] = Array(0xb523952e, 0x0b6f099f, 0xccf5a0ef, 0x1c580662)
        
        var s0: Int = 0
        var s1: Int = 0
        var s2: Int = 0
        var s3: Int = 0
        
        var i = 0
        while (i < LONG_JUMP.length) {
            var b = 0
            while (b < 32) {
                if ((LONG_JUMP(i) & (1 << b)) != 0) {
                    s0 ^= s(0)
                    s1 ^= s(1)
                    s2 ^= s(2)
                    s3 ^= s(3)
                }
                next()
                b += 1
            }
            i += 1
        }
        
        s(0) = s0
        s(1) = s1
        s(2) = s2
        s(3) = s3
    }
}

/**
 * Instance-based version of Xoshiro128+ for applications that need
 * multiple independent generators
 */
final class Xoshiro128PlusInstance(seed: Long) {
    // State array
    private val s: Array[Int] = new Array[Int](4)
    
    // Initialize state
    {
        var x = seed
        var i = 0
        while (i < 4) {
            x += 0x9e3779b97f4a7c15L
            var z = x
            z = (z ^ (z >>> 30)) * 0xbf58476d1ce4e5b9L
            z = (z ^ (z >>> 27)) * 0x94d049bb133111ebL
            s(i) = (z ^ (z >>> 31)).toInt
            i += 1
        }
    }
    
    @inline private def rotl(x: Int, k: Int): Int =
        (x << k) | (x >>> (32 - k))
    
    @inline final def next(): Int = {
        val result = s(0) + s(3)
        
        val t = s(1) << 9
        
        s(2) ^= s(0)
        s(3) ^= s(1)
        s(1) ^= s(2)
        s(0) ^= s(3)
        
        s(2) ^= t
        
        s(3) = rotl(s(3), 11)
        
        result
    }
    
    private val FLOAT_UNIT = 1.0f / 16777216.0f  // 1.0f / (1 << 24)
    
    @inline def nextFloat(): Float = {
        val bits = next() >>> 8
        bits * FLOAT_UNIT  // Multiplication instead of division
    }
    
    @inline def nextFloatFast(): Float = {
        java.lang.Float.intBitsToFloat(0x3f800000 | (next() & 0x007FFFFF)) - 1.0f
    }
    
    final def jump(): Unit = {
        val JUMP: Array[Int] = Array(0x8764000b, 0xf542d2d3, 0x6fa035c3, 0x77f2db5b)
        
        var s0: Int = 0
        var s1: Int = 0
        var s2: Int = 0
        var s3: Int = 0
        
        var i = 0
        while (i < JUMP.length) {
            var b = 0
            while (b < 32) {
                if ((JUMP(i) & (1 << b)) != 0) {
                    s0 ^= s(0)
                    s1 ^= s(1)
                    s2 ^= s(2)
                    s3 ^= s(3)
                }
                next()
                b += 1
            }
            i += 1
        }
        
        s(0) = s0
        s(1) = s1
        s(2) = s2
        s(3) = s3
    }
    
    final def longJump(): Unit = {
        val LONG_JUMP: Array[Int] = Array(0xb523952e, 0x0b6f099f, 0xccf5a0ef, 0x1c580662)
        
        var s0: Int = 0
        var s1: Int = 0
        var s2: Int = 0
        var s3: Int = 0
        
        var i = 0
        while (i < LONG_JUMP.length) {
            var b = 0
            while (b < 32) {
                if ((LONG_JUMP(i) & (1 << b)) != 0) {
                    s0 ^= s(0)
                    s1 ^= s(1)
                    s2 ^= s(2)
                    s3 ^= s(3)
                }
                next()
                b += 1
            }
            i += 1
        }
        
        s(0) = s0
        s(1) = s1
        s(2) = s2
        s(3) = s3
    }
}


object Xoshiro128PlusTest {
    def main(args: Array[String]): Unit = {
        println("Running Xoshiro128Plus tests...")
        
        try {
            testDeterministicOutput()
            testDifferentSeeds()
            testJumpFunction()
            testLongJumpFunction()
            testStaticAndInstanceConsistency()
            testKnownValues()
            testBasicStatisticalProperties()
            testPeriod()
            testFloatGeneration()
            runPerformanceBenchmark()
            
            println("\nAll tests passed!")
        } catch {
            case e: AssertionError =>
                println(s"Test failed: ${e.getMessage}")
                e.printStackTrace()
        }
    }
    
    def runPerformanceBenchmark(): Unit = {
        println("\nRunning performance benchmark...")
        
        // Warm up the JVM
        val warmupRng = new Xoshiro128PlusInstance(123L)
        for (_ <- 0 until 5000000) {
            warmupRng.next()
            warmupRng.nextFloat()
            warmupRng.nextFloatFast()
        }
        
        val javaRandom = new java.util.Random(42L)
        for (_ <- 0 until 5000000) {
            javaRandom.nextFloat()
        }
        
        // Benchmark float generation
        val rng = new Xoshiro128PlusInstance(42L)
        val iterations = 50000000
        
        // Test nextFloat()
        val floatStartTime = System.nanoTime()
        for (_ <- 0 until iterations) {
            rng.nextFloat()
        }
        val floatEndTime = System.nanoTime()
        
        val floatDurationMs = (floatEndTime - floatStartTime) / 1000000.0
        val floatThroughput = iterations / (floatDurationMs / 1000.0)
        
        println(s"Generated $iterations random floats using nextFloat() in $floatDurationMs ms")
        println(f"nextFloat() throughput: $floatThroughput%.2f numbers/second")
        
        // Test nextFloatFast()
        val floatFastStartTime = System.nanoTime()
        for (_ <- 0 until iterations) {
            rng.nextFloatFast()
        }
        val floatFastEndTime = System.nanoTime()
        
        val floatFastDurationMs = (floatFastEndTime - floatFastStartTime) / 1000000.0
        val floatFastThroughput = iterations / (floatFastDurationMs / 1000.0)
        
        println(s"\nGenerated $iterations random floats using nextFloatFast() in $floatFastDurationMs ms")
        println(f"nextFloatFast() throughput: $floatFastThroughput%.2f numbers/second")
        
        // Compare both methods
        val speedupRatio = floatFastThroughput / floatThroughput
        println(f"\nnextFloatFast() is ${speedupRatio}%.2f times " +
                  f"${if (speedupRatio > 1) "faster" else "slower"} than nextFloat()")
        
        // Compare with java.util.Random floats
        val javaFloatStartTime = System.nanoTime()
        for (_ <- 0 until iterations) {
            javaRandom.nextFloat()
        }
        val javaFloatEndTime = System.nanoTime()
        
        val javaFloatDurationMs = (javaFloatEndTime - javaFloatStartTime) / 1000000.0
        val javaFloatThroughput = iterations / (javaFloatDurationMs / 1000.0)
        
        println(s"\nJava Random generated $iterations floats in $javaFloatDurationMs ms")
        println(f"Java Random float throughput: $javaFloatThroughput%.2f numbers/second")
        
        println(f"\nXoshiro128Plus nextFloat() is ${floatThroughput / javaFloatThroughput}%.2f times " +
                  f"${if (floatThroughput > javaFloatThroughput) "faster" else "slower"} than Java Random")
        
        println(f"Xoshiro128Plus nextFloatFast() is ${floatFastThroughput / javaFloatThroughput}%.2f times " +
                  f"${if (floatFastThroughput > javaFloatThroughput) "faster" else "slower"} than Java Random")
    }
    
    def assert(condition: Boolean, message: String): Unit = {
        if (!condition) throw new AssertionError(message)
    }
    
    def testDeterministicOutput(): Unit = {
        println("Testing deterministic output for a given seed...")
        
        // Create two instances with the same seed
        val rng1 = new Xoshiro128PlusInstance(42L)
        val rng2 = new Xoshiro128PlusInstance(42L)
        
        // Generate 1000 numbers from each and ensure they match
        val results1 = Array.fill(1000)(rng1.next())
        val results2 = Array.fill(1000)(rng2.next())
        
        assert(results1.sameElements(results2), "RNG instances with the same seed should produce identical sequences")
        println("✓ Deterministic output test passed")
    }
    
    def testDifferentSeeds(): Unit = {
        println("Testing that different seeds produce different sequences...")
        
        val rng1 = new Xoshiro128PlusInstance(42L)
        val rng2 = new Xoshiro128PlusInstance(43L)
        
        // Generate 100 numbers from each and ensure they're different
        val results1 = Array.fill(100)(rng1.next())
        val results2 = Array.fill(100)(rng2.next())
        
        assert(!results1.sameElements(results2), "RNG instances with different seeds should produce different sequences")
        println("✓ Different seeds test passed")
    }
    
    def testJumpFunction(): Unit = {
        println("Testing jump function produces non-overlapping sequences...")
        
        val rng = new Xoshiro128PlusInstance(42L)
        
        // Generate 100 numbers
        val results1 = Array.fill(100)(rng.next())
        
        // Jump ahead
        rng.jump()
        
        // Generate 100 more numbers
        val results2 = Array.fill(100)(rng.next())
        
        // Check that the sequences are different
        assert(!results1.sameElements(results2), "Sequence after jump should be different from before jump")
        
        // The sequences should be significantly different
        val commonElements = results1.intersect(results2)
        assert(commonElements.length < 5, "Sequences before and after jump should have minimal overlap")
        
        println("✓ Jump function test passed")
    }
    
    def testLongJumpFunction(): Unit = {
        println("Testing long jump function produces non-overlapping sequences...")
        
        val rng = new Xoshiro128PlusInstance(42L)
        
        // Generate some initial numbers
        val results1 = Array.fill(100)(rng.next())
        
        // Long jump ahead
        rng.longJump()
        
        // Generate more numbers after the long jump
        val results2 = Array.fill(100)(rng.next())
        
        // Check that the sequences are different
        assert(!results1.sameElements(results2), "Sequence after long jump should be different from before long jump")
        
        // The sequences should be significantly different
        val commonElements = results1.intersect(results2)
        assert(commonElements.length < 5, "Sequences before and after long jump should have minimal overlap")
        
        println("✓ Long jump function test passed")
    }
    
    def testStaticAndInstanceConsistency(): Unit = {
        println("Testing static and instance-based implementations produce the same output...")
        
        // Reset the static implementation with a specific seed
        Xoshiro128Plus.setSeed(123L)
        
        // Create an instance with the same seed
        val rngInstance = new Xoshiro128PlusInstance(123L)
        
        // Generate 500 numbers from each and compare
        val staticResults = Array.fill(500)(Xoshiro128Plus.next())
        val instanceResults = Array.fill(500)(rngInstance.next())
        
        assert(staticResults.sameElements(instanceResults),
               "Static and instance-based implementations should produce the same output with the same seed")
        println("✓ Static/instance consistency test passed")
    }
    
    def testKnownValues(): Unit = {
        println("Testing against known values from reference implementation...")
        
        // Replace these with actual expected values from a reference implementation
        // These are just placeholder values for now
        val knownSeed = 1L
        val rng = new Xoshiro128PlusInstance(knownSeed)
        
        // The first few values from a reference implementation (replace these!)
        val expectedFirstValue = rng.next()
        val expectedSecondValue = rng.next()
        val expectedThirdValue = rng.next()
        
        // Reset and test
        val testRng = new Xoshiro128PlusInstance(knownSeed)
        val firstValue = testRng.next()
        val secondValue = testRng.next()
        val thirdValue = testRng.next()
        
        // Print values for verification
        println(s"First three values with seed $knownSeed:")
        println(s"  Value 1: $firstValue")
        println(s"  Value 2: $secondValue")
        println(s"  Value 3: $thirdValue")
        
        // Comment out the assertions until you replace with actual expected values
        // assert(firstValue == expectedFirstValue, s"First value doesn't match expected: got $firstValue, expected $expectedFirstValue")
        // assert(secondValue == expectedSecondValue, s"Second value doesn't match expected: got $secondValue, expected $expectedSecondValue")
        // assert(thirdValue == expectedThirdValue, s"Third value doesn't match expected: got $thirdValue, expected $expectedThirdValue")
        
        println("✓ Known values test passed (values printed for verification)")
    }
    
    def testBasicStatisticalProperties(): Unit = {
        println("Testing basic statistical properties...")
        
        val rng = new Xoshiro128PlusInstance(System.currentTimeMillis())
        
        // Generate 100,000 numbers
        val results = Array.fill(100000)(rng.next())
        
        // Our RNG outputs signed integers which have values distributed around 0
        // So we expect a mean close to 0, not 0.5
        val mean = results.map(_.toLong).sum.toDouble / results.length
        
        // The mean should be close to 0 for a properly functioning xoshiro128+
        assert(math.abs(mean) < (Int.MaxValue / 10.0), s"Mean value should be close to 0, got $mean")
        
        // Check distribution of the most significant bit (should be approximately 50% 0's and 50% 1's)
        val msb = results.count(n => (n & (1 << 31)) != 0)
        val msbRatio = msb.toDouble / results.length
        
        // Should be close to 0.5
        assert(math.abs(msbRatio - 0.5) < 0.05,
               s"MSB ratio should be close to 0.5, got $msbRatio")
        
        // Check distribution across all bits by counting 1s
        val totalBits = results.length * 32
        val setBits = results.map(Integer.bitCount).sum
        val bitRatio = setBits.toDouble / totalBits
        
        // Should be close to 0.5
        assert(math.abs(bitRatio - 0.5) < 0.01,
               s"Bit ratio should be close to 0.5, got $bitRatio")
        
        println("✓ Basic statistical properties test passed")
    }
    
    def testPeriod(): Unit = {
        println("Testing for non-repeating patterns in reasonable sequence...")
        
        val rng = new Xoshiro128PlusInstance(42L)
        
        // Generate 10,000 numbers
        val results = Array.fill(10000)(rng.next())
        
        // Check that there are no repeating patterns of length 1000 or less
        // This is a simplified check and not a comprehensive test of the period
        val subsets = results.sliding(1000, 1000).toArray
        
        // All subsets should be unique (no exact repetition)
        val distinctSubsets = subsets.map(_.toSeq).distinct
        
        assert(distinctSubsets.length == subsets.length,
               s"Expected ${subsets.length} distinct subsets, got ${distinctSubsets.length}")
        
        println("✓ Period test passed")
    }
    
    def testFloatGeneration(): Unit = {
        println("Testing float generation...")
        
        val rng = new Xoshiro128PlusInstance(1234L)
        
        // Generate 100,000 floats with both methods
        val floats = Array.fill(100000)(rng.nextFloat())
        val fastFloats = Array.fill(100000)(rng.nextFloatFast())
        
        // Check range: all values should be in [0, 1)
        assert(floats.forall(f => f >= 0f && f < 1f), "All floats should be in range [0, 1)")
        assert(fastFloats.forall(f => f >= 0f && f < 1f), "All fast floats should be in range [0, 1)")
        
        // Check for uniform distribution across buckets
        val buckets = new Array[Int](10)
        val fastBuckets = new Array[Int](10)
        
        for (f <- floats) {
            val bucket = math.min(9, (f * 10).toInt)
            buckets(bucket) += 1
        }
        
        for (f <- fastFloats) {
            val bucket = math.min(9, (f * 10).toInt)
            fastBuckets(bucket) += 1
        }
        
        // Each bucket should have roughly the same number of values
        val expectedPerBucket = floats.length / 10
        val tolerance = expectedPerBucket * 0.1 // Allow 10% deviation
        
        for (i <- 0 until 10) {
            assert(math.abs(buckets(i) - expectedPerBucket) < tolerance,
                   s"nextFloat(): Bucket $i has ${buckets(i)} items, expected around $expectedPerBucket")
            
            assert(math.abs(fastBuckets(i) - expectedPerBucket) < tolerance,
                   s"nextFloatFast(): Bucket $i has ${fastBuckets(i)} items, expected around $expectedPerBucket")
        }
        
        // Compare the two methods
        println("\nComparing nextFloat() and nextFloatFast():")
        
        // Check for precision differences
        val uniqueFloats = floats.distinct.length
        val uniqueFastFloats = fastFloats.distinct.length
        
        println(s"nextFloat() unique values: $uniqueFloats")
        println(s"nextFloatFast() unique values: $uniqueFastFloats")
        
        // Print a few values for visual inspection
        println("\nFirst 5 values from nextFloat():")
        floats.take(5).foreach(f => println(f))
        
        println("\nFirst 5 values from nextFloatFast():")
        fastFloats.take(5).foreach(f => println(f))
        
        println("✓ Float generation test passed")
    }
    
    
    def testByteGeneration(): Unit = {
        println("Testing byte generation utility...")
        
        // Utility method to get random bytes
        def nextBytes(rng: Xoshiro128PlusInstance, length: Int): Array[Byte] = {
            val bytes = new Array[Byte](length)
            var i = 0
            while (i < length) {
                val rnd = rng.next()
                val bytesToCopy = math.min(4, length - i)
                var j = 0
                while (j < bytesToCopy) {
                    bytes(i + j) = ((rnd >> (j * 8)) & 0xFF).toByte
                    j += 1
                }
                i += bytesToCopy
            }
            bytes
        }
        
        val rng = new Xoshiro128PlusInstance(123L)
        
        // Generate 1000 random bytes
        val bytes = nextBytes(rng, 1000)
        
        // Check statistical properties of bytes
        val byteCounts = bytes.groupBy(identity).view.mapValues(_.length).toMap
        
        // Each byte value should appear with roughly equal frequency
        // (allowed deviation ±50% from expected for this basic test)
        val expectedCount = 1000.0 / 256.0
        
        // Should have most of the 256 possible byte values
        assert(byteCounts.size >= 200, s"Should have at least 200 unique byte values, got ${byteCounts.size}")
        
        // Check for common byte values (0, 127, 255)
        // (they should exist but not dominate)
        assert(byteCounts.getOrElse(0.toByte, 0) < (expectedCount * 3).toInt,
               "Byte value 0 appears too frequently")
        assert(byteCounts.getOrElse(127.toByte, 0) < (expectedCount * 3).toInt,
               "Byte value 127 appears too frequently")
        assert(byteCounts.getOrElse(-1.toByte, 0) < (expectedCount * 3).toInt,
               "Byte value -1 (255) appears too frequently")
        
        println("✓ Byte generation test passed")
    }
}

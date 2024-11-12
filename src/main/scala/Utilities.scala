import scala.util.Random

import java.io.{BufferedWriter, File, FileWriter}

import cats.effect.IO
import tech.ant8e.uuid4cats.UUIDv7

def roundToNDecimals(number: Double, n: Int): Double = {
    val bd = BigDecimal(number)
    bd.setScale(n, BigDecimal.RoundingMode.HALF_UP).toDouble
}

def roundToNDecimalsF(number: Float, n: Int): Float = {
    val bd = BigDecimal(number)
    bd.setScale(n, BigDecimal.RoundingMode.HALF_UP).toFloat
}

def randomBetween(lower: Double = 0, upper: Double = 1, decimals: Int = 8): Double = {
    val random = new Random()
    roundToNDecimals(random.nextDouble() * (upper - lower) + lower, decimals)
}

def randomBetweenF(lower: Float = 0, upper: Float = 1, decimals: Int = 8): Float = {
    val random = new Random()
    roundToNDecimalsF(random.nextFloat() * (upper - lower) + lower, decimals)
}

def randomIntBetween(lower: Int = 0, upper: Int = 1): Int = {
    val random = new Random()
    lower + random.nextInt(upper + 1)
}

def saveToCsv[T](filePath: String, header: String, data: Seq[T], formatData: T => String): Unit = {
    val file = new File(filePath)
    val append = file.exists()
    val writer = new BufferedWriter(new FileWriter(file, append))
    try {
        if (!append) {
            writer.write(header + "\n")
        }
        data.foreach { d =>
            writer.write(formatData(d) + "\n")
        }
    } finally {
        writer.close()
    }
}

def reverseConfidence(c: Float): Float = {
    if (c > 0.9999) {
        10f
    } else {
        -math.log(-((c - 1) / (c + 1))).toFloat
    }
}

object UUIDGenerator {
    private val generator = UUIDv7.generator[IO]
    
    def generateUUID(): IO[java.util.UUID] = for {
        gen <- generator
        uuid <- gen.uuid
    } yield uuid
}

class Utilities {
    
}

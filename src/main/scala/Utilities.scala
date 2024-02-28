import java.io.{BufferedWriter, FileWriter}
import scala.util.Random

def roundToNDecimals(number: Double, n: Int): Double = {
  val bd = BigDecimal(number)
  bd.setScale(n, BigDecimal.RoundingMode.HALF_UP).toDouble
}

def randomBetween(lower : Double = 0, upper : Double = 1, decimals: Int = 8): Double = {
  val random = new Random()
  roundToNDecimals(random.nextDouble() * (upper - lower) + lower, decimals)
}

def saveToCsv[T](filePath: String, header: String, data: Seq[T], formatData: T => String): Unit = {
  val writer = new BufferedWriter(new FileWriter(filePath))
  try {
    writer.write(header + "\n")
    data.foreach { d =>
      writer.write(formatData(d) + "\n")
    }
  } finally {
    writer.close()
  }
}

class Utilities {

}

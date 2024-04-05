import scala.collection.mutable

class FenwickTree(size: Int, density: Int, setValue: Double, countFreqs: Boolean = false) {
  private val tree = Array.ofDim[Double](size + 1) //Array.fill(8)(0)
  private val scoresArr = Array.ofDim[Double](size + 1)
  private val minScore = density + (density * setValue)
  private var curMax: Double = density * minScore
  private var curLength: Int = density
  private val freqs = Array.ofDim[Double](size + 1)

  for (i <- 0 until density) {
    update(i: Int, minScore: Double)
  }

  private def update(i: Int, score: Double): Unit = {
    scoresArr(i + 1) += score
    updateTree(i, score)
  }

  private def updateTree(i: Int, score: Double): Unit = {
    var index = i + 1
    while (index <= size) {
      tree(index) += score
      index += index & -index
    }
  }

  // Compute the prefix sum from 1 to `i`
  private def query(i: Int): Double = {
    var sum = 0.0
    var index = i + 1
    while (index > 0) {
      sum += tree(index)
      index -= index & -index
    }
    sum
  }

  // Find the position for a given value using binary search
  private def findRange(value: Double): Int = {
    var low = 0
    var high = curLength-1
    while (low < high) {
      val mid = (low + high) / 2
      if (query(mid) < value) low = mid + 1
      else high = mid
    }
    low
  }

  def initWithScores(scores: Array[Double]): Unit = {
    curLength = scores.length
    for (i <- scores.indices) {
      update(i, scores(i))
    }
    curMax = query(scores.length)
  }

  // O(d * log(n) + d)
  def pickRandoms(): Array[Int] = {
    val nodesPicked = Array.ofDim[Int](density)
    var curLocalMax = curMax
    for (i <- 0 until density) {
      val randomValue = randomBetween(0, curLocalMax)
      nodesPicked(i) = findRange(randomValue)
      curLocalMax -= scoresArr(nodesPicked(i) + 1)
      updateTree(nodesPicked(i), -scoresArr(nodesPicked(i) + 1))
    }

    // Re assign the values and correct for index moved values
    for (i <- 0 until density) {
      updateTree(nodesPicked(i), scoresArr(nodesPicked(i) + 1))
      update(nodesPicked(i), 1)
      if (countFreqs) freqs(nodesPicked(i) + 1) += 1
    }

    curMax += minScore + density
    curLength += 1
    update(curLength-1, minScore)
    nodesPicked
  }

}
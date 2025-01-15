import akka.actor.{ActorContext, ActorRef, Props}

import java.util.concurrent.atomic.AtomicInteger
import scala.jdk.CollectionConverters.*
import scala.reflect.ClassTag



// Data Storing
case object FinishedSaving

object RoundRouter {
    private val cores = Runtime.getRuntime.availableProcessors()
    private val adjustedCores = 1 << (32 - Integer.numberOfLeadingZeros(cores - 1))
    private val roundSavers = Array.ofDim[ActorRef](adjustedCores)
    private val mask = adjustedCores - 1
    
    private val counter = new AtomicInteger(0)
    private val index = new AtomicInteger(0)
    private var threshold: Int = 2_000_000
    
    def setSavers(context: ActorContext, threshold: Int): Unit = {
        this.threshold = threshold
        var i = 0
        println(s"The adjusted cores are: $adjustedCores")
        while (adjustedCores > i) {
            val tableNum = i
            roundSavers(i) = context.actorOf(Props(new RoundDataCollector(tableNum, adjustedCores)), s"RoundSaver$i")
            i += 1
        }
    }
    
    def getRoute: ActorRef = {
        val currentCount = counter.getAndIncrement()
        if (currentCount >= threshold) {
            counter.set(0)
            // Bitwise AND for fast wraparound eqv to modulo but only for powers of 2
            index.getAndUpdate(current => (current + 1) & mask)
        }
        
        roundSavers(index.get())
    }
    
    def saveRemainingData(): Unit = {
        roundSavers.foreach(_ ! SaveRemainingData)
    }
}


package utils.datastructures

import scala.reflect.ClassTag

class ArrayList[T: ClassTag](initialSize: Int = 16) {
    var arr = new Array[T](initialSize)
    var size = 0
    
    def add(item: T): Unit = {
        // Check capacity
        if (size >= arr.length)
            arr = Array.copyOf(arr, arr.length * 2)
        
        // Add item
        arr(size) = item
        size += 1
    }
    
    def toArray: Array[T] = {
        val result = new Array[T](size)
        Array.copy(arr, 0, result, 0, size)
        result
    }
}

class ArrayListInt(initialCapacity: Int = 16) {
    var arr = new Array[Int](initialCapacity)
    var size = 0
    
    @inline
    def add(item: Int): Unit = {
        // Check capacity
        if (size >= arr.length)
            arr = Array.copyOf(arr, arr.length * 2)
            
        // Add item
        arr(size) = item
        size += 1
    }
    
    @inline
    def add(items: Array[Int]): Unit = {
        // Check capacity
        if (size + items.length >= arr.length)
            arr = Array.copyOf(arr, math.max((size + items.length) * 2, arr.length * 2))
        
        // Copy all items at once
        Array.copy(items, 0, arr, size, items.length)
        size += items.length
    }
    
    def trim(): Unit = {
        val newArr = new Array[Int](size)
        Array.copy(arr, 0, newArr, 0, size)
        arr = newArr
    }
    
    def toArray: Array[Int] = {
        val result = new Array[Int](size) 
        Array.copy(arr, 0, result, 0, size)
        result
    }
    
    @inline
    def copyToArray(target: Array[Int], targetOffset: Int): Unit = {
        Array.copy(arr, 0, target, targetOffset, size)
    }
}
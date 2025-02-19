package datastructures

class ArrayList[T](initialSize: Int = 16) {
    var arr = new Array[Int](initialSize)
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
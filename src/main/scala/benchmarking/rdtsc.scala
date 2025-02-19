package benchmarking

import java.io.File
import java.nio.file.{Files, StandardCopyOption}

class RdtscNative {
    @native def rdtscNative(): Long
}

object OptimizedRdtsc {
    // Initialize the native library
    try {
        val libraryPath = "/native/rdtsc.dll"
        val tmpDir = new File(System.getProperty("java.io.tmpdir"))
        val libraryFile = new File(tmpDir, "rdtsc.dll")
        
        // Extract DLL from resources to temp directory
        val input = getClass.getResourceAsStream(libraryPath)
        if (input == null) throw new RuntimeException(s"Could not find native library at $libraryPath")
        
        Files.copy(input, libraryFile.toPath, StandardCopyOption.REPLACE_EXISTING)
        input.close()
        System.load(libraryFile.getAbsolutePath)
    } catch {
        case e: Exception =>
            throw new RuntimeException(s"Failed to load native library: ${e.getMessage}", e)
    }
    
    private val instance = new RdtscNative()
    
    @inline def getRdtsc(): Long = {
        instance.rdtscNative()
    }
}
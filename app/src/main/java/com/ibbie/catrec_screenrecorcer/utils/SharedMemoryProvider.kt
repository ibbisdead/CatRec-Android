package com.ibbie.catrec_screenrecorcer.utils

import android.os.Build
import android.os.MemoryFile
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.SharedMemory
import android.system.ErrnoException
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.Closeable
import java.io.FileDescriptor
import java.io.IOException
import java.nio.ByteBuffer

/**
 * A unified wrapper for Shared Memory that handles:
 * 1. Tiny buffers (< 64KB) -> Uses simple DirectByteBuffer (fast, crash-proof).
 * 2. Large buffers (>= 64KB) -> Uses System Shared Memory.
 *    - API < 29: MemoryFile (Ashmem)
 *    - API >= 29: android.os.SharedMemory (ASharedMemory)
 *
 * This fixes crashes on Android 10+ where SharedMemory.create() fails for sizes smaller than
 * the system page size (usually 4KB) or non-aligned sizes.
 */
class SharedMemoryProvider private constructor(
    val mapName: String,
    val size: Int,
    val fileDescriptor: Int, // -1 if using direct buffer fallback or if FD extraction is blocked
    val byteBuffer: ByteBuffer?, // Always non-null for small buffers; nullable/lazy for large ones
    private val closeAction: () -> Unit,
) : Closeable {
    companion object {
        private const val TAG = "SharedMemProvider"

        // Threshold for switching strategies.
        // 64KB is a safe bet; avoids system call overhead for small audio/DSP chunks.
        private const val SMALL_BUFFER_THRESHOLD = 65536

        /**
         * Creates a shared memory region or a direct buffer.
         *
         * @param name The debug name of the region.
         * @param size The size in bytes.
         */
        @Throws(IOException::class)
        fun create(
            name: String,
            size: Int,
        ): SharedMemoryProvider {
            // PATH 1: Small Buffer Optimization
            // SharedMemory (Android 10+) often crashes or throws for very small sizes
            // (e.g. < 4KB page size). DirectByteBuffer is faster and safer here.
            if (size <= SMALL_BUFFER_THRESHOLD) {
                return createDirectBuffer(name, size)
            }

            // PATH 2: Large Buffer (System Shared Memory)
            // Used for framebuffers (1MB+), large textures, etc.
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                createModern(name, size)
            } else {
                createLegacy(name, size)
            }
        }

        /**
         * FALLBACK PATH: Direct Buffer
         * Used for small sizes. Does NOT provide a FileDescriptor (-1).
         * Native code must check for fd < 0 and use the pointer/address directly.
         */
        private fun createDirectBuffer(
            name: String,
            size: Int,
        ): SharedMemoryProvider {
            val buffer = ByteBuffer.allocateDirect(size)
            return SharedMemoryProvider(
                mapName = name,
                size = size,
                fileDescriptor = -1, // No FD for direct buffers
                byteBuffer = buffer,
                closeAction = { /* No-op, GC handles direct buffers */ },
            )
        }

        /**
         * MODERN PATH: API 29+ (Android 10+)
         * Uses android.os.SharedMemory.
         */
        @RequiresApi(Build.VERSION_CODES.Q)
        private fun createModern(
            name: String,
            size: Int,
        ): SharedMemoryProvider {
            try {
                val sharedMemory = SharedMemory.create(name, size)
                val buffer = sharedMemory.mapReadWrite()

                // Extract FD using Parcel to avoid reflection restrictions on API 35+
                // SharedMemory implements Parcelable and writes the FD to the destination.
                // We can exploit this to get a legitimate dup() of the FD via public APIs.
                var fd = -1
                try {
                    val parcel = Parcel.obtain()
                    try {
                        sharedMemory.writeToParcel(parcel, 0)
                        parcel.setDataPosition(0)
                        val pfd = parcel.readFileDescriptor()
                        if (pfd != null) {
                            fd = pfd.detachFd() // Takes ownership of the FD
                        }
                    } finally {
                        parcel.recycle()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to extract FD via Parcel: ${e.message}")
                    // Proceed with fd = -1. The buffer is still valid.
                }

                return SharedMemoryProvider(
                    mapName = name,
                    size = size,
                    fileDescriptor = fd,
                    byteBuffer = buffer,
                    closeAction = {
                        SharedMemory.unmap(buffer)
                        sharedMemory.close()
                        // If we extracted a duped FD, we must close it.
                        if (fd != -1) {
                            try {
                                ParcelFileDescriptor.adoptFd(fd).close()
                            } catch (e: Exception) {
                                Log.w(TAG, "Error closing duped FD: ${e.message}")
                            }
                        }
                    },
                )
            } catch (e: ErrnoException) {
                throw IOException("Failed to create SharedMemory (Modern): ${e.message}", e)
            } catch (e: IllegalArgumentException) {
                // Fallback if size was weirdly rejected despite check
                Log.w(TAG, "SharedMemory rejected size $size, falling back to DirectBuffer")
                return createDirectBuffer(name, size)
            }
        }

        /**
         * LEGACY PATH: API 26-28 (Android 8.0 - 9.0)
         * Uses MemoryFile (Ashmem) via reflection.
         */
        private fun createLegacy(
            name: String,
            size: Int,
        ): SharedMemoryProvider {
            val memoryFile = MemoryFile(name, size)
            var rawFd = -1

            try {
                val getFileDescriptorMethod = MemoryFile::class.java.getDeclaredMethod("getFileDescriptor")
                val fileDescriptorObj = getFileDescriptorMethod.invoke(memoryFile) as FileDescriptor

                val fdField = FileDescriptor::class.java.getDeclaredField("descriptor")
                fdField.isAccessible = true
                rawFd = fdField.getInt(fileDescriptorObj)
            } catch (e: Exception) {
                memoryFile.close()
                throw IOException("Failed to reflect MemoryFile FD: ${e.message}", e)
            }

            return SharedMemoryProvider(
                mapName = name,
                size = size,
                fileDescriptor = rawFd,
                byteBuffer = null, // Legacy path relies on FD mmap in native
                closeAction = {
                    memoryFile.close()
                },
            )
        }
    }

    override fun close() {
        try {
            closeAction()
            // Log.d(TAG, "Closed: $mapName")
        } catch (e: Exception) {
            Log.e(TAG, "Error closing $mapName", e)
        }
    }
}

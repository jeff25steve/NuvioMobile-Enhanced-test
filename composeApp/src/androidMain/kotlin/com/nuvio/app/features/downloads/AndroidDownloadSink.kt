package com.nuvio.app.features.downloads

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileNotFoundException
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

internal interface DownloadSink {
    fun length(): Long

    fun open(append: Boolean): FileOutputStream

    fun truncate(length: Long)
}

internal class FileDownloadSink(private val file: File) : DownloadSink {
    override fun length(): Long = file.takeIf(File::isFile)?.length() ?: 0L

    override fun open(append: Boolean): FileOutputStream {
        val parent = file.parentFile
        check(parent == null || parent.isDirectory || parent.mkdirs()) { "Cannot create downloads directory" }
        return FileOutputStream(file, append)
    }

    override fun truncate(length: Long) {
        require(length >= 0L)
        RandomAccessFile(file, "rw").use { output ->
            if (output.length() < length) {
                throw IOException("Cannot restore the previous partial download length")
            }
            output.setLength(length)
            output.fd.sync()
        }
    }
}

internal class DocumentDownloadSink(
    private val context: Context,
    private val uri: Uri,
) : DownloadSink {
    override fun length(): Long = try {
        openDescriptor("r").use { it.statSize.coerceAtLeast(0L) }
    } catch (_: FileNotFoundException) {
        0L
    }

    override fun open(append: Boolean): FileOutputStream {
        val pfd = openDescriptor("rw")
        val output = DescriptorOutputStream(pfd)
        try {
            val channel = output.channel
            if (append) channel.position(pfd.statSize.coerceAtLeast(0L)) else channel.truncate(0L)
        } catch (error: Throwable) {
            output.close()
            throw error
        }
        return output
    }

    override fun truncate(length: Long) {
        require(length >= 0L)
        open(append = true).use { output ->
            if (output.channel.size() < length) {
                throw IOException("Cannot restore the previous partial download length")
            }
            output.channel.truncate(length)
            if (output.channel.size() != length) {
                throw IOException("Could not restore the previous partial download length")
            }
            output.fd.sync()
        }
    }

    private fun openDescriptor(mode: String): ParcelFileDescriptor = try {
        context.contentResolver.openFileDescriptor(uri, mode)
            ?: throw IOException("Could not open the file in the selected download location")
    } catch (denied: SecurityException) {
        throw IOException("Access to the selected download location was revoked", denied)
    }

    private class DescriptorOutputStream(
        private val pfd: ParcelFileDescriptor,
    ) : FileOutputStream(pfd.fileDescriptor) {
        private var closed = false

        override fun close() {
            if (closed) return
            closed = true
            try {
                super.close()
            } finally {
                pfd.close()
            }
        }
    }
}

package com.dashcam.blackbox.recorder

import android.content.Context
import android.util.Log
import java.io.File

class RollingBufferManager(private val context: Context) {

    companion object {
        const val TAG = "RollingBuffer"
        const val MAX_ROLLING_CHUNKS = 4 // Keep up to 4 rolling chunks before recycling
    }

    private val recordsDir: File by lazy {
        File(context.getExternalFilesDir(null), "dashcam_rolling").apply { mkdirs() }
    }

    private val incidentsDir: File by lazy {
        File(context.getExternalFilesDir(null), "dashcam_incidents").apply { mkdirs() }
    }

    fun getNewChunkFile(): File {
        cleanupOldChunks()
        val filename = "chunk_${System.currentTimeMillis()}.mp4"
        return File(recordsDir, filename)
    }

    fun lockIncidentFile(file: File?): File? {
        if (file == null || !file.exists()) return null
        val incidentFile = File(incidentsDir, "INCIDENT_${System.currentTimeMillis()}_${file.name}")
        return try {
            file.copyTo(incidentFile, overwrite = true)
            Log.i(TAG, "Incident clip permanently locked at: ${incidentFile.absolutePath}")
            incidentFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to lock incident: ${e.message}", e)
            null
        }
    }

    private fun cleanupOldChunks() {
        val files = recordsDir.listFiles { f -> f.extension == "mp4" }?.sortedBy { it.lastModified() } ?: return
        if (files.size > MAX_ROLLING_CHUNKS) {
            val toDeleteCount = files.size - MAX_ROLLING_CHUNKS
            for (i in 0 until toDeleteCount) {
                files[i].delete()
                Log.i(TAG, "Purged old rolling chunk: ${files[i].name}")
            }
        }
    }

    fun getIncidentCount(): Int {
        return incidentsDir.listFiles { f -> f.extension == "mp4" }?.size ?: 0
    }
}

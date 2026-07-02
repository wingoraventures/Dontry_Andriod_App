package com.dontry.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File
import java.io.FileOutputStream

data class SavedLook(
    val id: String,
    val filePath: String,
    val savedAt: Long
)

class LooksManager(private val context: Context) {

    private val prefs = context.getSharedPreferences("tryvue_looks", Context.MODE_PRIVATE)
    private val looksDir = File(context.filesDir, "saved_looks").also { it.mkdirs() }


    fun saveLook(bitmap: Bitmap): SavedLook {
        val id = "look_${System.currentTimeMillis()}"
        val file = File(looksDir, "$id.jpg")

        // Save bitmap to file
        val outputStream = FileOutputStream(file)
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, outputStream)
        outputStream.flush()
        outputStream.close()

        // Add to list
        val ids = getLookIds().toMutableList()
        ids.add(id)
        prefs.edit()
            .putString("look_ids", ids.joinToString(","))
            .putLong("time_$id", System.currentTimeMillis())
            .apply()

        // Keep only latest 3 — delete oldest if more than 3
        val allIds = getLookIds()
        if (allIds.size > 3) {
            val oldest = allIds.first()
            deleteLook(oldest)
        }

        return SavedLook(id, file.absolutePath, System.currentTimeMillis())
    }

    // ── Get all saved looks ────────────────────────────────────────
    fun getAllLooks(): List<SavedLook> {
        return getLookIds().mapNotNull { id ->
            val file = File(looksDir, "$id.jpg")
            if (file.exists()) {
                SavedLook(
                    id = id,
                    filePath = file.absolutePath,
                    savedAt = prefs.getLong("time_$id", 0)
                )
            } else null
        }.reversed() // newest first
    }

    // ── Delete a look ──────────────────────────────────────────────
    fun deleteLook(id: String) {
        // Delete file
        val file = File(looksDir, "$id.jpg")
        if (file.exists()) file.delete()

        // Remove from list
        val ids = getLookIds().toMutableList()
        ids.remove(id)
        prefs.edit()
            .putString("look_ids", ids.joinToString(","))
            .remove("time_$id")
            .apply()

        // If this was selected → reset to original
        val selectedPath = getSelectedLookPath()
        if (selectedPath == file.absolutePath) {
            clearSelectedLook()
        }
    }

    // ── Select a look as base photo ────────────────────────────────
    fun selectLook(look: SavedLook) {
        prefs.edit().putString("selected_look_path", look.filePath).apply()
    }

    // ── Clear selected look → use original profile photo ──────────
    fun clearSelectedLook() {
        prefs.edit().remove("selected_look_path").apply()
    }

    // ── Get selected look path ─────────────────────────────────────
    fun getSelectedLookPath(): String? {
        val path = prefs.getString("selected_look_path", null)
        // Verify file still exists
        return if (path != null && File(path).exists()) path else null
    }

    // ── Get photo path to use for try-on ──────────────────────────
    fun getPhotoForTryOn(): String? {
        // Use selected look if available
        val selectedPath = getSelectedLookPath()
        if (selectedPath != null) return selectedPath

        // Otherwise use original profile photo
        return context.getSharedPreferences("Dontry", Context.MODE_PRIVATE)
            .getString("profile_photo_path", null)
    }

    // ── Load bitmap from look ──────────────────────────────────────
    fun loadBitmap(look: SavedLook): Bitmap? {
        return try {
            BitmapFactory.decodeFile(look.filePath)
        } catch (e: Exception) {
            null
        }
    }

    // ── Check if a look is currently selected ─────────────────────
    fun isSelected(look: SavedLook): Boolean {
        return getSelectedLookPath() == look.filePath
    }

    // ── Private helper ─────────────────────────────────────────────
    private fun getLookIds(): List<String> {
        val raw = prefs.getString("look_ids", "") ?: ""
        return if (raw.isEmpty()) emptyList()
        else raw.split(",").filter { it.isNotEmpty() }
    }
}
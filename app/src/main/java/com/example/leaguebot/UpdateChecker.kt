package com.example.leaguebot

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    private const val GITHUB_API_URL = "https://api.github.com/repos/katahu/mobileBot/releases/latest"
    private const val CURRENT_VERSION = "1.1"

    fun checkForUpdate(activity: Activity) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val connection = URL(GITHUB_API_URL).openConnection() as HttpURLConnection
                connection.setRequestProperty("User-Agent", "AndroidApp")
                val response = connection.inputStream.bufferedReader().readText()
                val json = JSONObject(response)

                val latestVersion = json.getString("tag_name").removePrefix("v")
                val assets = json.getJSONArray("assets")
                if (assets.length() == 0) return@launch

                val apkUrl = assets.getJSONObject(0).getString("browser_download_url")

                if (latestVersion > CURRENT_VERSION) {
                    val apkFile = File(activity.cacheDir, "update.apk")
                    downloadApk(apkUrl, apkFile)

                    withContext(Dispatchers.Main) {
                        promptInstall(activity, apkFile)
                    }
                }

            } catch (e: Exception) {
                Log.e("UpdateChecker", "Ошибка обновления: ${e.message}")
            }
        }
    }

    private fun downloadApk(url: String, outputFile: File) {
        try {
            val connection = URL(url).openConnection()
            val input = connection.getInputStream()
            val output = FileOutputStream(outputFile)
            input.copyTo(output)
            input.close()
            output.close()
        } catch (e: Exception) {
            Log.e("UpdateChecker", "Ошибка загрузки: ${e.message}")
        }
    }

    private fun promptInstall(activity: Activity, apkFile: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.provider", apkFile)

        AlertDialog.Builder(activity)
            .setTitle("Обновление загружено")
            .setMessage("Установить новую версию сейчас?")
            .setPositiveButton("Установить") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
                }
                activity.startActivity(intent)
            }
            .setNegativeButton("Позже", null)
            .show()
    }
}

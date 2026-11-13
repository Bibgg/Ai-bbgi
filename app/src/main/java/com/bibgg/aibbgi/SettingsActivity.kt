package com.bibgg.aibbgi

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bibgg.aibbgi.databinding.ActivitySettingsBinding
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val prefs = SecurePrefs.get(this)

        binding.backendUrlInput.setText(
            prefs.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL)
        )
        binding.apiKeyInput.setText(prefs.getString(KEY_API_KEY, ""))
        binding.localModeCheck.isChecked = prefs.getBoolean(KEY_LOCAL_MODE, false)
        binding.llamaUrlInput.setText(prefs.getString(KEY_LLAMA_URL, ""))
        binding.repoInput.setText(prefs.getString(KEY_GITHUB_REPO, "Bibgg/Ai-bbgi"))
        binding.githubTokenInput.setText(prefs.getString(KEY_GITHUB_TOKEN, ""))

        binding.settingsToolbar.setNavigationIcon(android.R.drawable.ic_media_previous)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }

        binding.testBackendButton.setOnClickListener { testBackend() }
        binding.saveSettingsButton.setOnClickListener {
            val backend = binding.backendUrlInput.text?.toString()?.trim().orEmpty().trimEnd('/')
            val repo = binding.repoInput.text?.toString()?.trim().orEmpty()
            if (backend.isNotEmpty() && !backend.startsWith("https://")) {
                binding.backendUrlInput.error = "Use an HTTPS backend URL"
                return@setOnClickListener
            }
            if (repo.isNotEmpty() && !repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
                binding.repoInput.error = "Use owner/repository format"
                return@setOnClickListener
            }
            prefs.edit()
                .putString(KEY_BACKEND_URL, backend.ifBlank { DEFAULT_BACKEND_URL })
                .putString(KEY_API_KEY, binding.apiKeyInput.text?.toString()?.trim().orEmpty())
                .putBoolean(KEY_LOCAL_MODE, binding.localModeCheck.isChecked)
                .putString(KEY_LLAMA_URL, binding.llamaUrlInput.text?.toString()?.trim().orEmpty().trimEnd('/'))
                .putString(KEY_GITHUB_REPO, repo.ifBlank { "Bibgg/Ai-bbgi" })
                .putString(KEY_GITHUB_TOKEN, binding.githubTokenInput.text?.toString()?.trim().orEmpty())
                .apply()
            Toast.makeText(this, "Settings saved securely", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun testBackend() {
        val backend = binding.backendUrlInput.text?.toString()?.trim().orEmpty().trimEnd('/')
        if (!backend.startsWith("https://")) {
            binding.backendUrlInput.error = "Enter a valid HTTPS backend URL"
            return
        }
        binding.testBackendButton.isEnabled = false
        binding.backendStatusText.text = "Checking backend…"
        Thread {
            try {
                val request = Request.Builder().url("$backend/health").get().build()
                http.newCall(request).execute().use { response ->
                    val body = response.body?.string().orEmpty()
                    if (!response.isSuccessful) throw IllegalStateException("HTTP ${response.code}")
                    val data = JSONObject(body)
                    val database = data.optString("database", "unknown")
                    val message = if (database == "connected") {
                        "Backend online · Database connected"
                    } else {
                        "Backend online · Database not connected yet"
                    }
                    runOnUiThread { binding.backendStatusText.text = message }
                }
            } catch (error: Exception) {
                runOnUiThread {
                    binding.backendStatusText.text =
                        "Connection failed: ${error.message ?: "Check URL or network"}"
                }
            } finally {
                runOnUiThread { binding.testBackendButton.isEnabled = true }
            }
        }.start()
    }

    companion object {
        const val DEFAULT_BACKEND_URL = "https://ai-bbgi-privateai-api.onrender.com"
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_LOCAL_MODE = "local_mode"
        const val KEY_LLAMA_URL = "llama_url"
        const val KEY_GITHUB_REPO = "github_repo"
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_CLIENT_ID = "client_id"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_ON_DEVICE_MODEL_PATH = "on_device_model_path"
    }
}

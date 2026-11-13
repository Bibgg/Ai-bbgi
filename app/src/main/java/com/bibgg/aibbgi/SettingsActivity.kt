package com.bibgg.aibbgi

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bibgg.aibbgi.databinding.ActivitySettingsBinding

class SettingsActivity : AppCompatActivity() {
    private lateinit var binding: ActivitySettingsBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        val prefs = SecurePrefs.get(this)

        binding.backendUrlInput.setText(prefs.getString(KEY_BACKEND_URL, ""))
        binding.apiKeyInput.setText(prefs.getString(KEY_API_KEY, ""))
        binding.localModeCheck.isChecked = prefs.getBoolean(KEY_LOCAL_MODE, false)
        binding.llamaUrlInput.setText(prefs.getString(KEY_LLAMA_URL, ""))
        binding.repoInput.setText(prefs.getString(KEY_GITHUB_REPO, "Bibgg/Ai-bbgi"))
        binding.githubTokenInput.setText(prefs.getString(KEY_GITHUB_TOKEN, ""))

        binding.settingsToolbar.setNavigationIcon(android.R.drawable.ic_media_previous)
        binding.settingsToolbar.setNavigationOnClickListener { finish() }
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
                .putString(KEY_BACKEND_URL, backend)
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

    companion object {
        const val KEY_BACKEND_URL = "backend_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_LOCAL_MODE = "local_mode"
        const val KEY_LLAMA_URL = "llama_url"
        const val KEY_GITHUB_REPO = "github_repo"
        const val KEY_GITHUB_TOKEN = "github_token"
        const val KEY_CLIENT_ID = "client_id"
    }
}

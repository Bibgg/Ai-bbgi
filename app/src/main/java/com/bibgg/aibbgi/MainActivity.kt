package com.bibgg.aibbgi

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.net.Uri
import android.os.StatFs
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.arm.aichat.AiChat
import com.arm.aichat.InferenceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import android.view.Gravity
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bibgg.aibbgi.databinding.ActivityMainBinding
import com.google.android.material.button.MaterialButton
import io.noties.markwon.Markwon
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var markdown: Markwon
    private val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS).writeTimeout(30, TimeUnit.SECONDS).build()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private var sessionId: String? = null
    private val localHistory = mutableListOf<Pair<String, String>>()
    private var busy = false
    @Volatile private var localModelReady = false
    @Volatile private var localEngine: InferenceEngine? = null
    private var modelSetupBusy = false

    private val downloadHttp = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    companion object {
        private const val MODEL_URL =
            "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val MODEL_FILE = "qwen2.5-0.5b-instruct-q4_k_m.gguf"
        private const val MODEL_SHA256 =
            "74a4da8c9fdbcd15bd1f6d01d621410d31c6fc00986f5eb687824e7b93d7a9db"
        private const val ON_DEVICE_SYSTEM_PROMPT =
            "You are AI-BBGI, a helpful private assistant. Reply clearly and accurately. " +
            "The user prefers Hindi; answer in the language used by the user. " +
            "Do not invent facts. Say when you are unsure."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        markdown = Markwon.create(this)
        setSupportActionBar(binding.toolbar)
        binding.sendButton.setOnClickListener { sendMessage() }
        sessionId = SecurePrefs.get(this).getString(SettingsActivity.KEY_SESSION_ID, null)
        showText("assistant", "नमस्ते! Settings में Render Backend URL और API Key सेट करें। Local Mode के लिए llama.cpp server URL भी दें।")
        restoreSessionHistory()
        restoreOnDeviceModel()
    }

    override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean {
        menuInflater.inflate(R.menu.main_menu, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_download_model) {
            downloadAndLoadModel()
            return true
        }
        if (item.itemId == R.id.action_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onResume() { super.onResume(); updateStatus() }

    private fun updateStatus() {
        val local = SecurePrefs.get(this).getBoolean(SettingsActivity.KEY_LOCAL_MODE, false)
        binding.providerStatus.text = when {
            localModelReady -> "On-device AI ready · Qwen2.5 0.5B"
            local -> "Local llama.cpp server preferred · Cloud fallback"
            else -> "Cloud mode · Render backend"
        }
    }

    private fun sendMessage() {
        if (busy) return
        val prompt = binding.messageInput.text?.toString()?.trim().orEmpty()
        if (prompt.isBlank()) return
        val prefs = SecurePrefs.get(this)
        val backend = prefs.getString(SettingsActivity.KEY_BACKEND_URL, "").orEmpty()
        val key = prefs.getString(SettingsActivity.KEY_API_KEY, "").orEmpty()
        val localModeSelected = prefs.getBoolean(SettingsActivity.KEY_LOCAL_MODE, false)
        val localServerSelected = prefs.getString(SettingsActivity.KEY_LLAMA_URL, "").orEmpty().isNotBlank()
        if (!localModelReady && (!localModeSelected || !localServerSelected) && (backend.isBlank() || key.isBlank())) {
            Toast.makeText(this, "Cloud chat के लिए Settings में Backend URL और API Key सेट करें।", Toast.LENGTH_LONG).show()
            return
        }
        binding.messageInput.setText("")
        showText("user", prompt)
        busy = true
        binding.sendButton.isEnabled = false
        binding.providerStatus.text = "Thinking…"
        Thread {
            try {
                val localMode = prefs.getBoolean(SettingsActivity.KEY_LOCAL_MODE, false)
                val localUrl = prefs.getString(SettingsActivity.KEY_LLAMA_URL, "").orEmpty()
                val result = if (localModelReady) {
                    val answer = callOnDevice(prompt)
                    persistLocalIfPossible(backend, key, prompt, answer)
                    AiReply(answer, "on-device Qwen2.5 0.5B")
                } else if (localMode && localUrl.isNotBlank()) {
                    try {
                        val memory = if (backend.isNotBlank() && key.isNotBlank()) {
                            try { getJson("$backend/api/memory", key).optString("memory") } catch (_: Exception) { "" }
                        } else ""
                        val answer = callLocal(localUrl, prompt, memory)
                        persistLocalIfPossible(backend, key, prompt, answer)
                        AiReply(answer, "local")
                    } catch (localError: Exception) {
                        if (backend.isBlank() || key.isBlank()) throw localError
                        callBackend(backend, key, prompt, "auto")
                    }
                } else callBackend(backend, key, prompt, "cloud")
                runOnUiThread {
                    binding.providerStatus.text = "Connected · ${result.provider}"
                    showAssistant(result.text)
                }
            } catch (error: Exception) {
                runOnUiThread {
                    binding.providerStatus.text = "Connection error"
                    showText("assistant", "Error: ${error.message ?: "Unable to connect"}")
                }
            } finally {
                runOnUiThread { busy = false; binding.sendButton.isEnabled = true }
            }
        }.start()
    }


    private fun restoreOnDeviceModel() {
        val path = SecurePrefs.get(this)
            .getString(SettingsActivity.KEY_ON_DEVICE_MODEL_PATH, null) ?: return
        val file = File(path)
        if (!file.isFile || file.length() < 100L * 1024L * 1024L) {
            SecurePrefs.get(this).edit()
                .remove(SettingsActivity.KEY_ON_DEVICE_MODEL_PATH).apply()
            return
        }
        lifecycleScope.launch {
            try {
                loadOnDeviceModel(file)
                Toast.makeText(this@MainActivity, "Local AI model loaded", Toast.LENGTH_SHORT).show()
            } catch (error: Exception) {
                localModelReady = false
                binding.providerStatus.text = "Local model could not load: ${error.message}"
            }
        }
    }

    private fun downloadAndLoadModel() {
        if (modelSetupBusy || localModelReady) return
        modelSetupBusy = true
        binding.providerStatus.text = "Preparing local AI model…"
        invalidateOptionsMenu()
        lifecycleScope.launch {
            try {
                val modelFile = withContext(Dispatchers.IO) { downloadModel() }
                loadOnDeviceModel(modelFile)
                Toast.makeText(
                    this@MainActivity,
                    "AI-BBGI local model is ready",
                    Toast.LENGTH_LONG
                ).show()
            } catch (error: Exception) {
                binding.providerStatus.text = "Model setup failed: ${error.message ?: "unknown error"}"
                Toast.makeText(
                    this@MainActivity,
                    "Model setup failed: ${error.message ?: "unknown error"}",
                    Toast.LENGTH_LONG
                ).show()
            } finally {
                modelSetupBusy = false
                updateStatus()
                invalidateOptionsMenu()
            }
        }
    }

    private suspend fun downloadModel(): File = withContext(Dispatchers.IO) {
        val directory = File(filesDir, "models")
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Cannot create private model storage")
        }
        val destination = File(directory, MODEL_FILE)
        if (destination.isFile && sha256(destination) == MODEL_SHA256) {
            return@withContext destination
        }

        val available = StatFs(filesDir.absolutePath).availableBytes
        val minimumFree = 650L * 1024L * 1024L
        if (available < minimumFree) {
            throw IllegalStateException("कम-से-कम 650 MB खाली स्टोरेज चाहिए")
        }

        val temporary = File(directory, "$MODEL_FILE.part")
        temporary.delete()
        val request = Request.Builder().url(MODEL_URL)
            .header("User-Agent", "AI-BBGI-Android")
            .get().build()
        downloadHttp.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("Hugging Face download HTTP ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("Empty model download")
            val contentLength = body.contentLength()
            if (contentLength > 0 && available < contentLength + 150L * 1024L * 1024L) {
                throw IllegalStateException("मॉडल के लिए पर्याप्त खाली स्टोरेज नहीं है")
            }

            val digest = MessageDigest.getInstance("SHA-256")
            var copied = 0L
            var lastUiUpdate = 0L
            val buffer = ByteArray(64 * 1024)
            body.byteStream().use { input ->
                FileOutputStream(temporary).use { output ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                        copied += count
                        val now = System.currentTimeMillis()
                        if (now - lastUiUpdate >= 700L) {
                            lastUiUpdate = now
                            val downloadedMb = copied / (1024L * 1024L)
                            val totalMb = if (contentLength > 0) contentLength / (1024L * 1024L) else 0L
                            runOnUiThread {
                                binding.providerStatus.text =
                                    if (totalMb > 0) "Downloading local AI · $downloadedMb / $totalMb MB"
                                    else "Downloading local AI · $downloadedMb MB"
                            }
                        }
                    }
                    output.fd.sync()
                }
            }

            val actualHash = digest.digest().joinToString("") { "%02x".format(it) }
            if (actualHash != MODEL_SHA256) {
                temporary.delete()
                throw IllegalStateException("Model SHA-256 verification failed")
            }
            if (destination.exists() && !destination.delete()) {
                temporary.delete()
                throw IllegalStateException("Cannot replace old model file")
            }
            if (!temporary.renameTo(destination)) {
                temporary.delete()
                throw IllegalStateException("Cannot save downloaded model")
            }
        }
        destination
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun obtainLocalEngine(): InferenceEngine = withContext(Dispatchers.IO) {
        localEngine ?: run {
            val candidate = AiChat.getInferenceEngine(applicationContext)
            val state = withTimeout(120_000L) {
                candidate.state.first {
                    it is InferenceEngine.State.Initialized || it is InferenceEngine.State.Error
                }
            }
            if (state is InferenceEngine.State.Error) {
                throw state.exception
            }
            candidate.also { localEngine = it }
        }
    }

    private suspend fun loadOnDeviceModel(file: File) {
        val engine = obtainLocalEngine()
        withContext(Dispatchers.IO) {
            engine.loadModel(file.absolutePath)
            engine.setSystemPrompt(ON_DEVICE_SYSTEM_PROMPT)
        }
        SecurePrefs.get(this).edit()
            .putString(SettingsActivity.KEY_ON_DEVICE_MODEL_PATH, file.absolutePath)
            .apply()
        localModelReady = true
        updateStatus()
        invalidateOptionsMenu()
    }

    private fun callOnDevice(prompt: String): String {
        val engine = localEngine ?: throw IllegalStateException("Local AI engine is not initialized")
        val response = StringBuilder()
        runBlocking(Dispatchers.IO) {
            engine.sendUserPrompt(prompt, 256).collect { token ->
                response.append(token)
            }
        }
        return response.toString().trim().ifBlank {
            throw IllegalStateException("Local model returned an empty response")
        }
    }

    private fun clientId(): String {
        val prefs = SecurePrefs.get(this)
        return prefs.getString(SettingsActivity.KEY_CLIENT_ID, null) ?: UUID.randomUUID().toString().also {
            prefs.edit().putString(SettingsActivity.KEY_CLIENT_ID, it).apply()
        }
    }

    private fun getJson(url: String, key: String): JSONObject {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key")
            .header("X-Client-Id", clientId()).get().build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val data = if (body.isNotBlank()) JSONObject(body) else JSONObject()
            if (!response.isSuccessful) throw IllegalStateException(data.optString("error", "HTTP ${response.code}"))
            return data
        }
    }

    private fun restoreSessionHistory() {
        val id = sessionId ?: return
        val prefs = SecurePrefs.get(this)
        val backend = prefs.getString(SettingsActivity.KEY_BACKEND_URL, "").orEmpty()
        val key = prefs.getString(SettingsActivity.KEY_API_KEY, "").orEmpty()
        if (backend.isBlank() || key.isBlank()) return
        Thread {
            try {
                val data = getJson("$backend/api/sessions/$id/messages", key)
                val rows = data.optJSONArray("messages") ?: JSONArray()
                val restored = mutableListOf<Pair<String, String>>()
                for (index in 0 until rows.length()) {
                    val row = rows.optJSONObject(index) ?: continue
                    val role = row.optString("role")
                    val content = row.optString("content")
                    if ((role == "assistant" || role == "user") && content.isNotBlank()) restored.add(role to content)
                }
                runOnUiThread {
                    localHistory.clear()
                    localHistory.addAll(restored)
                    binding.messageList.removeAllViews()
                    restored.forEach { (role, content) ->
                        if (role == "assistant") showAssistant(content) else showText("user", content)
                    }
                }
            } catch (_: Exception) {
                // Keep the welcome message when the previous session cannot be restored.
            }
        }.start()
    }

    private fun request(url: String, key: String, payload: JSONObject): JSONObject {
        val request = Request.Builder().url(url).header("Authorization", "Bearer $key")
            .header("X-Client-Id", clientId())
            .post(payload.toString().toRequestBody(jsonType)).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val data = if (body.isNotBlank()) JSONObject(body) else JSONObject()
            if (!response.isSuccessful) throw IllegalStateException(data.optString("error", "HTTP ${response.code}"))
            return data
        }
    }

    private fun callBackend(backend: String, key: String, prompt: String, mode: String): AiReply {
        val payload = JSONObject().put("message", prompt).put("mode", mode)
        sessionId?.let { payload.put("sessionId", it) }
        val data = request("$backend/api/chat", key, payload)
        sessionId = data.optString("sessionId").takeIf { it.isNotBlank() }
        sessionId?.let { SecurePrefs.get(this).edit().putString(SettingsActivity.KEY_SESSION_ID, it).apply() }
        return AiReply(data.getString("reply"), data.optString("provider", "cloud"))
    }

    private fun callLocal(baseUrl: String, prompt: String, memory: String): String {
        val endpoint = if (baseUrl.endsWith("/v1/chat/completions")) baseUrl else baseUrl.trimEnd('/') + "/v1/chat/completions"
        val history = JSONArray()
        if (memory.isNotBlank()) {
            history.put(JSONObject().put("role", "system").put("content", "Long-term user context:\n$memory"))
        }
        localHistory.forEach { (role, content) ->
            history.put(JSONObject().put("role", role).put("content", content))
        }
        history.put(JSONObject().put("role", "user").put("content", prompt))
        val payload = JSONObject().put("model", "local-model")
            .put("messages", history).put("temperature", 0.7).put("stream", false)
        val request = Request.Builder().url(endpoint).post(payload.toString().toRequestBody(jsonType)).build()
        http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Local llama.cpp HTTP ${response.code}")
            val answer = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            if (answer.isBlank()) throw IllegalStateException("Local model returned an empty response")
            localHistory.add("user" to prompt)
            localHistory.add("assistant" to answer)
            return answer
        }
    }

    private fun persistLocalIfPossible(backend: String, key: String, prompt: String, answer: String) {
        if (backend.isBlank() || key.isBlank()) return
        try {
            var id = sessionId
            if (id == null) id = request("$backend/api/sessions", key, JSONObject().put("title", prompt.take(80))).getString("sessionId")
            sessionId = id
            SecurePrefs.get(this).edit().putString(SettingsActivity.KEY_SESSION_ID, id).apply()
            request("$backend/api/sessions/$id/messages", key,
                JSONObject().put("role", "user").put("content", prompt).put("provider", "client-local"))
            request("$backend/api/sessions/$id/messages", key,
                JSONObject().put("role", "assistant").put("content", answer).put("provider", "client-local"))
        } catch (_: Exception) { /* Keep local chat available during cloud outages. */ }
    }

    private fun showText(role: String, text: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 14, 14, 14)
            setBackgroundColor(if (role == "user") Color.rgb(42, 48, 62) else Color.rgb(29, 33, 42))
        }
        card.addView(TextView(this).apply {
            this.text = if (role == "user") "YOU" else "AI-BBGI"
            textSize = 11f; setTextColor(Color.rgb(185, 166, 255))
        })
        card.addView(TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); setPadding(0, 7, 0, 0)
            markdown.setMarkdown(this, text)
        })
        binding.messageList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        scrollToBottom()
    }

    private fun showAssistant(answer: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(14, 14, 14, 14)
            setBackgroundColor(Color.rgb(29, 33, 42))
        }
        card.addView(TextView(this).apply {
            text = "AI-BBGI"; textSize = 11f; setTextColor(Color.rgb(185, 166, 255))
        })
        val codeRegex = Regex("(?s)```([\\w.+-]*)\\s*\\n(.*?)```")
        var cursor = 0
        codeRegex.findAll(answer).forEach { match ->
            val before = answer.substring(cursor, match.range.first).trim()
            if (before.isNotEmpty()) addMarkdown(card, before)
            addCodeBlock(card, match.groupValues[1].ifBlank { "text" }, match.groupValues[2].trimEnd())
            cursor = match.range.last + 1
        }
        val after = answer.substring(cursor).trim()
        if (after.isNotEmpty()) addMarkdown(card, after)
        binding.messageList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 })
        scrollToBottom()
    }

    private fun addMarkdown(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); setPadding(0, 7, 0, 7)
            markdown.setMarkdown(this, text)
        })
    }

    private fun addCodeBlock(parent: LinearLayout, language: String, source: String) {
        val block = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(10, 10, 10, 10)
            setBackgroundColor(Color.rgb(17, 20, 27))
        }
        block.addView(TextView(this).apply {
            text = language.uppercase(); textSize = 10f; setTextColor(Color.rgb(156, 203, 189))
        })
        block.addView(TextView(this).apply {
            text = highlightCode(source); textSize = 12.5f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(Color.rgb(232, 235, 242)); setTextIsSelectable(true)
            setPadding(0, 8, 0, 8)
        })
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        buttons.addView(MaterialButton(this).apply {
            text = "Copy Code"
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Ai-BBGI code", source))
                Toast.makeText(this@MainActivity, "Code copied", Toast.LENGTH_SHORT).show()
            }
        })
        buttons.addView(MaterialButton(this).apply {
            text = "Push to GitHub"; setOnClickListener { pushCode(language, source) }
        })
        block.addView(buttons)
        parent.addView(block, LinearLayout.LayoutParams(-1, -2).apply { topMargin = 8; bottomMargin = 8 })
    }

    private fun highlightCode(source: String): CharSequence {
        val styled = android.text.SpannableString(source)
        val keywords = Regex("\\b(fun|val|var|class|object|return|if|else|for|while|import|package|const|let|function|def|async|await|public|private|static|new|true|false|null)\\b")
        keywords.findAll(source).forEach {
            styled.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(190, 160, 255)),
                it.range.first, it.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("\"[^\"\\n]*\"|'[^'\\n]*'").findAll(source).forEach {
            styled.setSpan(android.text.style.ForegroundColorSpan(Color.rgb(151, 207, 161)),
                it.range.first, it.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        Regex("//[^\\n]*|#[^\\n]*").findAll(source).forEach {
            styled.setSpan(android.text.style.ForegroundColorSpan(Color.GRAY),
                it.range.first, it.range.last + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return styled
    }

    private fun pushCode(language: String, source: String) {
        val prefs = SecurePrefs.get(this)
        val backend = prefs.getString(SettingsActivity.KEY_BACKEND_URL, "").orEmpty()
        val key = prefs.getString(SettingsActivity.KEY_API_KEY, "").orEmpty()
        val token = prefs.getString(SettingsActivity.KEY_GITHUB_TOKEN, "").orEmpty()
        val repo = prefs.getString(SettingsActivity.KEY_GITHUB_REPO, "Bibgg/Ai-bbgi").orEmpty()
        if (backend.isBlank() || key.isBlank() || token.isBlank()) {
            Toast.makeText(this, "Settings में Backend, API Key और GitHub Token भरें।", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            try {
                val path = "generated/ai-output-${System.currentTimeMillis()}.${extension(language)}"
                val payload = JSONObject().put("repo", repo).put("filePath", path)
                    .put("content", source).put("commitMessage", "Add AI-generated ${language} code")
                    .put("githubToken", token)
                request("$backend/api/github/push", key, payload)
                runOnUiThread { Toast.makeText(this, "Committed: $path", Toast.LENGTH_LONG).show() }
            } catch (error: Exception) {
                runOnUiThread { Toast.makeText(this, "GitHub push failed: ${error.message}", Toast.LENGTH_LONG).show() }
            }
        }.start()
    }

    private fun extension(language: String): String = when (language.lowercase()) {
        "kotlin", "kt" -> "kt"; "java" -> "java"; "python", "py" -> "py"
        "javascript", "js" -> "js"; "typescript", "ts" -> "ts"; "json" -> "json"
        "xml" -> "xml"; "html" -> "html"; "css" -> "css"; "bash", "sh", "shell" -> "sh"
        "yaml", "yml" -> "yml"; "markdown", "md" -> "md"; else -> "txt"
    }

    private fun scrollToBottom() { binding.chatScroll.post { binding.chatScroll.fullScroll(View.FOCUS_DOWN) } }
    private data class AiReply(val text: String, val provider: String)
}

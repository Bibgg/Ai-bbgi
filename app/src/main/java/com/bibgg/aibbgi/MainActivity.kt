package com.bibgg.aibbgi

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.graphics.Color
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {
    private val native = NativeBridge()
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var prefs: android.content.SharedPreferences
    private var modelReady = false
    private var latestCode: String? = null
    private val pickModel = 41

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val master = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        prefs = EncryptedSharedPreferences.create(
            this, "secure_settings", master,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
        buildUi()
        addMessage("Ai-BBGI", "नमस्ते! Settings से अपनी .gguf मॉडल फाइल चुनें और Load Model दबाएँ।")
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 14, 16, 12)
            setBackgroundColor(Color.rgb(16,17,20))
        }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply {
            text = "Ai-BBGI"; textSize = 22f; setTextColor(Color.WHITE)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Button(this).apply { text = "Settings"; setOnClickListener { settingsDialog() } })
        root.addView(header)

        val scroll = ScrollView(this)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 12, 0, 12) }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        input = EditText(this).apply {
            hint = "अपना सवाल या coding task लिखें…"
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY)
            setBackgroundColor(Color.rgb(35,37,43)); minLines = 2; maxLines = 5
        }
        root.addView(input)
        val actions = LinearLayout(this).apply { gravity = Gravity.END }
        actions.addView(Button(this).apply {
            text = "Send"
            setOnClickListener {
                val prompt = input.text.toString().trim()
                if (prompt.isNotEmpty()) {
                    input.text.clear()
                    addMessage("You", prompt)
                    if (!modelReady) addMessage("Ai-BBGI", "पहले Settings में GGUF मॉडल लोड करें।")
                    else generate(prompt)
                }
            }
        })
        actions.addView(Button(this).apply {
            text = "Push to GitHub"
            setOnClickListener { pushLatestCode() }
        })
        root.addView(actions)
        setContentView(root)
    }

    private fun addMessage(who: String, body: String) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(12, 10, 12, 10)
            setBackgroundColor(Color.rgb(35,37,43))
        }
        card.addView(TextView(this).apply {
            text = who; textSize = 12f; setTextColor(Color.rgb(180,160,255))
        })
        val code = Regex("```(?:[a-zA-Z0-9_+.-]+)?\\s*([\\s\\S]*?)```")
        val match = code.find(body)
        if (match != null) {
            val before = body.substringBefore(match.value).trim()
            if (before.isNotEmpty()) card.addView(messageText(before))
            val source = match.groupValues[1].trim()
            latestCode = source
            val codeView = TextView(this).apply {
                text = highlight(source); textSize = 13f
                setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(20,21,25))
                setPadding(10, 10, 10, 10); typeface = android.graphics.Typeface.MONOSPACE
                textIsSelectable = true
            }
            card.addView(codeView)
            card.addView(Button(this).apply {
                text = "Copy code"
                setOnClickListener {
                    val clip = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clip.setPrimaryClip(android.content.ClipData.newPlainText("AI code", source))
                    Toast.makeText(this@MainActivity, "Code copied", Toast.LENGTH_SHORT).show()
                }
            })
            val after = body.substringAfter(match.value).trim()
            if (after.isNotEmpty()) card.addView(messageText(after))
        } else card.addView(messageText(body))
        val lp = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = 10 }
        messages.addView(card, lp)
    }

    private fun messageText(value: String) = TextView(this).apply {
        text = value; textSize = 15f; setTextColor(Color.WHITE); setPadding(0, 6, 0, 6)
    }

    private fun highlight(source: String): SpannableString {
        val s = SpannableString(source)
        val patterns = listOf(
            Regex("\\b(class|fun|val|var|if|else|return|import|package|public|private|const|let|function)\\b") to Color.rgb(190,150,255),
            Regex("\"[^\"\\n]*\"|'[^'\\n]*'") to Color.rgb(150,210,150),
            Regex("//[^\\n]*|#[^\\n]*") to Color.GRAY
        )
        patterns.forEach { (regex, color) ->
            regex.findAll(source).forEach { s.setSpan(ForegroundColorSpan(color), it.range.first, it.range.last + 1, 0) }
        }
        return s
    }

    private fun generate(prompt: String) {
        addMessage("Ai-BBGI", "सोच रहा हूँ…")
        thread {
            val answer = try { native.generate(prompt) } catch (e: Exception) { "Error: ${e.message}" }
            runOnUiThread {
                if (messages.childCount > 0) messages.removeViewAt(messages.childCount - 1)
                addMessage("Ai-BBGI", answer)
            }
        }
    }

    private fun settingsDialog() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 8, 24, 8) }
        val token = EditText(this).apply {
            hint = "GitHub Personal Access Token"; setSingleLine(true)
            inputType = 129; setText(prefs.getString("github_token", ""))
        }
        val repo = EditText(this).apply {
            hint = "Repository: owner/name"; setSingleLine(true)
            setText(prefs.getString("github_repo", "Bibgg/Ai-bbgi"))
        }
        val path = EditText(this).apply {
            hint = "New file path (e.g. generated/main.py)"; setSingleLine(true)
            setText("generated/ai-output.txt")
        }
        box.addView(Button(this).apply { text = "Choose .gguf model"; setOnClickListener {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); type = "*/*"
            }, pickModel)
        } })
        box.addView(token); box.addView(repo); box.addView(path)
        AlertDialogCompat.show(this, box) {
            prefs.edit().putString("github_token", token.text.toString().trim())
                .putString("github_repo", repo.text.toString().trim())
                .putString("github_path", path.text.toString().trim()).apply()
            Toast.makeText(this, "Settings saved", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickModel && resultCode == Activity.RESULT_OK) {
            val uri = data?.data ?: return
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            thread {
                try {
                    val modelDir = File(filesDir, "models").apply { mkdirs() }
                    val modelFile = File(modelDir, "selected-model.gguf")
                    contentResolver.openInputStream(uri).use { input ->
                        modelFile.outputStream().use { output -> input!!.copyTo(output) }
                    }
                    val loaded = native.loadModel(modelFile.absolutePath)
                    runOnUiThread {
                        modelReady = loaded
                        Toast.makeText(this, if (loaded) "GGUF model loaded" else "Model load failed", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    runOnUiThread { Toast.makeText(this, "Model error: ${e.message}", Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    private fun pushLatestCode() {
        val source = latestCode
        if (source.isNullOrBlank()) {
            Toast.makeText(this, "पहले AI से code generate करें।", Toast.LENGTH_SHORT).show()
            return
        }
        val token = prefs.getString("github_token", "").orEmpty()
        val repo = prefs.getString("github_repo", "").orEmpty()
        val path = prefs.getString("github_path", "generated/ai-output.txt").orEmpty()
        if (token.isBlank() || !repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")) || path.isBlank()) {
            Toast.makeText(this, "Settings में Token, Repository और file path भरें।", Toast.LENGTH_LONG).show()
            return
        }
        thread {
            try {
                val api = URL("https://api.github.com/repos/$repo/contents/$path")
                val conn = (api.openConnection() as HttpURLConnection).apply {
                    requestMethod = "PUT"; doOutput = true; connectTimeout = 15000; readTimeout = 20000
                    setRequestProperty("Authorization", "Bearer $token")
                    setRequestProperty("Accept", "application/vnd.github+json")
                    setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
                    setRequestProperty("Content-Type", "application/json")
                }
                val payload = JSONObject().put("message", "Add AI generated code")
                    .put("content", Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8)))
                conn.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
                val status = conn.responseCode
                val response = (if (status in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (status !in 200..299) throw IllegalStateException("GitHub HTTP $status: $response")
                runOnUiThread { Toast.makeText(this, "GitHub commit successful", Toast.LENGTH_LONG).show() }
                conn.disconnect()
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "Push failed: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        }
    }

    override fun onDestroy() {
        if (::prefs.isInitialized) native.unload()
        super.onDestroy()
    }
}

private object AlertDialogCompat {
    fun show(activity: Activity, content: LinearLayout, save: () -> Unit) {
        android.app.AlertDialog.Builder(activity)
            .setTitle("Ai-BBGI Settings").setView(content)
            .setPositiveButton("Save") { _, _ -> save() }
            .setNegativeButton("Cancel", null).show()
    }
}

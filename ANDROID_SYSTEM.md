# Ai-BBGI full system

Ai-BBGI is split into three runtime components plus CI:

1. **Android client (app/):** Kotlin, Material 3, OkHttp, Markwon and encrypted settings. It supports cloud chat through Render and direct local chat against a llama.cpp OpenAI-compatible HTTP server.
2. **Backend (backend/):** Node.js 20, Express, Mongoose and Octokit. It stores chat sessions/messages, per-device long-term memory and code snippets in MongoDB Atlas; routes AI requests to a reachable llama.cpp server and then an OpenAI-compatible cloud provider; and commits code through GitHub Contents API.
3. **llama.cpp (repository root):** upstream engine source. Build/run its llama-server separately on the phone or a trusted LAN host, load a local GGUF file there, then set the server URL in Android Settings. The Android APK does not bundle model weights or launch the llama-server process itself.
4. **GitHub Actions (.github/workflows/build-android.yml):** builds the debug APK on pushes to main, uploads it as an Actions artifact, and attaches it to a prerelease.

## Android project

- Package: com.bibgg.aibbgi
- Minimum Android: API 26
- Compile/target SDK: 34
- JDK: 17
- Gradle: 8.9
- APK output: app/build/outputs/apk/debug/app-debug.apk

The release build currently uses the debug signing key for device testing only. Do not publish it to Play Store. Configure a private upload key in CI before a Play Store release.

## Render deployment

Use backend/render.yaml as a Render Blueprint, or create a Node web service manually with:
- Repository: https://github.com/Bibgg/Ai-bbgi
- Branch: main
- Root directory: backend
- Build command: npm install
- Start command: npm start
- Health check: /health

Required environment:
- MONGODB_URI: Atlas SRV URI for the existing kedar_backend user, database KedarAI
- APP_API_KEY: a strong random bearer secret; enter the same value in Android Settings

Optional:
- GITHUB_TOKEN: fine-grained server-side fallback token
- LLAMA_SERVER_URL: publicly reachable HTTPS tunnel to llama.cpp if you want the Render backend itself to try local inference
- CLOUD_API_KEY: cloud provider key for fallback
- CLOUD_API_URL, CLOUD_MODEL: OpenAI-compatible endpoint and model
- CORS_ORIGINS: comma-separated allowed browser origins

The server starts in degraded mode if MongoDB is not configured, but all protected API routes return 503 until MongoDB and APP_API_KEY are configured. Health is public and reports the database connection state.

## MongoDB network security

The current Atlas project has an empty access list. Do not add 0.0.0.0/0 as a permanent rule. Once the Render service is created, inspect its outbound IP addresses and allow only the required Render egress IP/CIDR ranges in Atlas Network Access. If the selected Render plan does not provide stable egress ranges, use an appropriate static-egress plan or a secure private networking option.

## Local llama.cpp

Build and run a llama.cpp server on the device or a reachable trusted machine using the upstream llama.cpp instructions. Example command on a compatible host:

    ./llama-server -m /absolute/path/to/model.gguf --host 0.0.0.0 --port 8080 -c 4096

Use the host's LAN address in Android Settings, e.g. http://192.168.1.20:8080. For access over the internet, use a private authenticated HTTPS tunnel; never expose an unauthenticated model server publicly. Local mode calls /v1/chat/completions directly from the phone and falls back to Render if local inference fails. The local model conversation remains on-device; if persistence to Render is enabled, the app sends the prompt and response to MongoDB.

## GitHub push

Create a fine-grained GitHub PAT restricted to the intended repository and grant Contents: Read and write. Save it in Android Settings. The app sends it over HTTPS to the backend /api/github/push endpoint; the backend never logs or persists it. Use a unique filename per generation to avoid overwriting files.

## First deployment checklist

1. Configure Render MONGODB_URI and APP_API_KEY.
2. Add the Render outbound IP ranges to Atlas Network Access (not 0.0.0.0/0).
3. Set Android Backend URL and the same APP_API_KEY.
4. Configure a cloud API key for fallback, or configure a reachable llama.cpp endpoint.
5. Push to main; GitHub Actions builds the APK. Download it from Actions → run → Artifacts or from the generated prerelease.

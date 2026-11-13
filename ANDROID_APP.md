# Ai-BBGI Android system

The current Android architecture and deployment instructions are maintained in ANDROID_SYSTEM.md.

The Android client is a native Kotlin app under app/; the Node.js/Mongoose backend is under backend/. The app calls a local llama.cpp HTTP server directly in Local Mode and uses the Render backend for cloud routing, persistence, memory and GitHub commits. The APK does not package GGUF model weights or embed a running llama-server process.

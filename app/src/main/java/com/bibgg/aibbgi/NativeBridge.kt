package com.bibgg.aibbgi

internal class NativeBridge {
    external fun loadModel(path: String): Boolean
    external fun generate(prompt: String): String
    external fun unload()

    companion object {
        init { System.loadLibrary("aibbgi_jni") }
    }
}

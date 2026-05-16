package com.manga.translate

import android.util.Log

class LocalVlmClient {

    companion object {
        init {
            System.loadLibrary("minicpm_v_jni")
        }
    }

    external fun initModel(modelPath: String, mmprojPath: String, numThreads: Int): Boolean
    
    external fun freeModel()

    // external fun processImage(...) : String
}
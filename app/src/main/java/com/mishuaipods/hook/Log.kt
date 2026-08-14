package com.mishuaipods.hook

import android.util.Log

object Log {
    private const val TAG_PREFIX = "MiShuaiPods-"

    fun d(tag: String, msg: String) {
        Log.d("$TAG_PREFIX$tag", msg)
    }

    fun i(tag: String, msg: String) {
        Log.i("$TAG_PREFIX$tag", msg)
    }

    fun w(tag: String, msg: String) {
        Log.w("$TAG_PREFIX$tag", msg)
    }

    fun e(tag: String, msg: String, tr: Throwable? = null) {
        Log.e("$TAG_PREFIX$tag", msg, tr)
    }

    fun v(tag: String, msg: String) {
        Log.v("$TAG_PREFIX$tag", msg)
    }
}

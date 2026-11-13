package com.kiongozi.app.game

/** Lets MainActivity.onPause() tell the running game to pause itself (home button, incoming call...). */
object AppLifecycle {
    @Volatile
    var onPause: (() -> Unit)? = null
}

package com.resequencetwin.control.drift

/** Port: a live source of the plant's current lane configuration. Implementations may do I/O. */
interface LiveConfigSource {
    fun readConfig(): ObservedConfig
}

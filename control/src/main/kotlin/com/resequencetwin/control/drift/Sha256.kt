package com.resequencetwin.control.drift
import java.security.MessageDigest
object Sha256 { fun hex(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) } }

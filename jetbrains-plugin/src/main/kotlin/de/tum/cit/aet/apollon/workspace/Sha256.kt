package de.tum.cit.aet.apollon.workspace

import java.security.MessageDigest

/** Pure JDK, unit-testable without an IDE — used to detect whether a `.puml` source changed
 *  since it was last synced (plan §A5). A stored hash survives IDE restarts and a `git checkout`
 *  that restores identical bytes, unlike a filesystem timestamp. */
fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString("") { "%02x".format(it) }
}

fun sourceHash(bytes: ByteArray): String = "sha256:${sha256Hex(bytes)}"

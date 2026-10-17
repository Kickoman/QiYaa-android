package io.github.kickoman.qiyaa.telemetry

import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object MachineId {
    const val KEY = "QiYaa telemetry"
    private const val ALGORITHM = "HmacSHA256"
    private const val BYTES = 16

    fun of(systemId: String): String {
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(SecretKeySpec(KEY.toByteArray(Charsets.UTF_8), ALGORITHM))
        return mac.doFinal(systemId.toByteArray(Charsets.UTF_8))
            .take(BYTES)
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
    }

    fun resolve(
        androidId: String?,
        stored: String?,
        store: (String) -> Unit,
        newId: () -> String = { UUID.randomUUID().toString() },
    ): String {
        if (!androidId.isNullOrBlank()) return of(androidId)
        if (!stored.isNullOrBlank()) return of(stored)
        val created = newId()
        store(created)
        return of(created)
    }
}

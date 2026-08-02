package com.example.testandroidenv.agent.loop

import com.example.testandroidenv.agent.contract.ActionResultCode
import com.example.testandroidenv.agent.contract.PlannerAction
import java.security.MessageDigest

data class ActionSignature(
    val sourceFingerprint: String,
    val action: PlannerAction,
    val semanticTarget: String,
    val redactedValueMarker: String?,
    val resultCode: ActionResultCode
)

class LoopProtection {
    private val recent = ArrayDeque<ActionSignature>()

    /** Returns true once the same failed immediate action is seen twice. */
    fun record(signature: ActionSignature): Boolean {
        recent.addLast(signature)
        while (recent.size > 4) recent.removeFirst()
        val sameFailures = recent.count { it == signature }
        val alternatingLoop = recent.size == 4 &&
            recent[0] == recent[2] &&
            recent[1] == recent[3]
        return sameFailures >= 2 || alternatingLoop
    }

    companion object {
        fun redactedValueMarker(value: String?): String? {
            if (value == null) return null
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .take(4)
                .joinToString("") { "%02x".format(it) }
            return "value:${value.length}:$digest"
        }
    }
}

package com.example.testandroidenv

import com.example.testandroidenv.agent.loop.AgentRuntimeState
import com.example.testandroidenv.agent.loop.AgentStopCode

internal object ArabicAgentErrorMessages {
    const val PLANNER_UNAVAILABLE = "تعذر الاتصال بخدمة التخطيط. حاول مرة ثانية."
    const val SCREEN_UNAVAILABLE = "ما قدرت أقرأ الشاشة الحالية. حاول مرة ثانية."
    const val SCREEN_CHANGED = "تغيّرت الشاشة قبل تنفيذ الخطوة. حاول مرة ثانية."
    const val UNSAFE_ACTION = "ما أقدر أنفذ هذه الخطوة بأمان."

    fun forFailure(state: AgentRuntimeState.Failed): String {
        if (
            state.message.contains("STALE", ignoreCase = true) ||
            state.message.contains("SNAPSHOT", ignoreCase = true)
        ) {
            return SCREEN_CHANGED
        }
        return when (state.code) {
            AgentStopCode.PLANNER_ERROR -> PLANNER_UNAVAILABLE
            AgentStopCode.ACCESSIBILITY_UNAVAILABLE,
            AgentStopCode.EMPTY_UI_STATE,
            AgentStopCode.OBSERVATION_ERROR -> SCREEN_UNAVAILABLE
            AgentStopCode.INVALID_DECISION,
            AgentStopCode.EXECUTOR_ERROR,
            AgentStopCode.REPEATED_ACTION_LOOP,
            AgentStopCode.MAX_STEPS_REACHED,
            AgentStopCode.CANCELLED -> UNSAFE_ACTION
        }
    }
}

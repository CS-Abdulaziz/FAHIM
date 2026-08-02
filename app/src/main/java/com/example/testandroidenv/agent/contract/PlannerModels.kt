package com.example.testandroidenv.agent.contract

enum class TaskStatus(val wireValue: String) {
    CONTINUE("continue"),
    TASK_COMPLETED("task_completed"),
    NEEDS_USER_INPUT("needs_user_input"),
    NEEDS_CONFIRMATION("needs_confirmation"),
    FAILED("failed"),
    END_SESSION("end_session")
}

enum class PlannerAction(val wireValue: String) {
    OPEN_APP("open_app"),
    TAP("tap"),
    TYPE("type"),
    SCROLL("scroll"),
    BACK("back"),
    READ_ALOUD("read_aloud"),
    ASK_USER("ask_user"),
    CONFIRM_WITH_USER("confirm_with_user"),
    NONE("none")
}

enum class UiRole(val wireValue: String) {
    BUTTON("button"),
    TEXT_FIELD("text_field"),
    LIST_ITEM("list_item"),
    TEXT("text"),
    HEADING("heading"),
    TAB("tab"),
    APP_ICON("app_icon"),
    SCROLLABLE("scroll_container"),
    CHECKBOX("checkbox"),
    SWITCH("switch"),
    IMAGE("image"),
    UNKNOWN("unknown")
}

enum class ActionResultCode(val wireValue: String) {
    ACTION_SUCCEEDED("ACTION_SUCCEEDED"),
    TARGET_NOT_FOUND("TARGET_NOT_FOUND"),
    STALE_TARGET("STALE_TARGET"),
    ACTION_NOT_SUPPORTED("ACTION_NOT_SUPPORTED"),
    TARGET_NOT_VISIBLE("TARGET_NOT_VISIBLE"),
    TARGET_NOT_ENABLED("TARGET_NOT_ENABLED"),
    SCREEN_UNCHANGED("SCREEN_UNCHANGED"),
    APP_NOT_FOUND("APP_NOT_FOUND"),
    TEXT_INPUT_FAILED("TEXT_INPUT_FAILED"),
    TIMEOUT("TIMEOUT"),
    EXECUTOR_ERROR("EXECUTOR_ERROR"),
    CANCELLED("CANCELLED"),
    USER_DENIED_CONFIRMATION("USER_DENIED_CONFIRMATION")
}

data class CurrentApp(
    val packageName: String?,
    val displayName: String?,
    val appId: String = defaultLogicalAppId(packageName),
    val screenName: String? = null
)

private fun defaultLogicalAppId(packageName: String?): String = when {
    packageName == "com.whatsapp" -> "whatsapp"
    packageName == "com.whatsapp.w4b" -> "whatsapp_business"
    packageName == "android" ||
        packageName?.startsWith("com.android.systemui") == true ||
        packageName?.startsWith("com.android.settings") == true -> "android_system"
    else -> "unknown"
}

data class CompactUiElement(
    val targetId: String,
    val role: UiRole,
    val label: String,
    val actions: List<PlannerAction>,
    val contentDescription: String? = null,
    val enabled: Boolean = true
)

data class CompactUiState(
    val screenVersion: Long,
    val elements: List<CompactUiElement>,
    val sourcePackageName: String? = null,
    val sourceWindowId: Int? = null
)

data class ConversationContextEntry(
    val role: String,
    val message: String
)

data class ActionResult(
    val action: PlannerAction,
    val success: Boolean,
    val resultCode: ActionResultCode,
    val details: String,
    val target: String? = null,
    val targetId: String? = null
)

data class ActionHistoryRecord(
    val action: PlannerAction,
    val target: String?,
    val targetId: String?,
    val value: String?,
    val success: Boolean,
    val resultCode: ActionResultCode,
    val screenVersion: Long,
    val screenFingerprint: String
)

data class PlannerRequest(
    val userInput: String?,
    val activeGoal: String,
    val currentApp: CurrentApp,
    val uiState: CompactUiState,
    val conversationContext: List<ConversationContextEntry>,
    val lastActionResult: ActionResult?,
    val actionHistory: List<ActionHistoryRecord>
)

/** Exactly the seven logical fields returned by the remote AI Planner. */
data class PlannerDecision(
    val reason: String?,
    val status: TaskStatus,
    val action: PlannerAction,
    val target: String?,
    val targetId: String?,
    val value: String?,
    val message: String?
)

/** Runtime metadata binding. It is never part of Planner input/output JSON. */
data class BoundPlannerDecision(
    val sourceScreenVersion: Long,
    val sourceFingerprint: String,
    val decision: PlannerDecision,
    val sourceAppId: String? = null,
    val sourcePackageName: String? = null,
    val sourceWindowId: Int? = null,
    val sourceResolvedHomePackageName: String? = null,
    val requestId: String? = null
)

# Arabic Mobile Agent for Blind Users
## Corrected Architecture and Planner Contract

**Document version:** 0.2  
**Status:** Agreed MVP implementation baseline  
**Primary audience:** Planner developer, Android developer, and integration team  
**Initial Planner model:** `Qwen3-4B-Instruct-2507`

---

## 1. Purpose

This document defines the agreed architecture for the Arabic mobile agent and, most importantly, the exact responsibility, input, and output of the Planner.

The central rule is:

> The Planner decides only the next single action. Android validates it, executes it, observes the new screen, and then calls the Planner again.

This document intentionally separates:

- the Qwen Planner;
- the backend code surrounding the model;
- the Android UI State Builder;
- the Validator;
- the Android Executor;
- voice and session management.

The Planner must not take over the responsibilities of these surrounding components.

---

## 2. System Goal

The system helps Arabic-speaking blind users complete mobile tasks through natural voice commands.

Example:

> افتح محادثة أحمد

The complete runtime flow is:

```text
Arabic speech
→ Speech-to-Text
→ active user goal
→ capture the current Android screen
→ build a Compact UI State
→ ask the Planner for one next action
→ validate the decision
→ execute the action on the real phone
→ observe the new screen
→ ask the Planner again
```

The loop continues until:

- the task is completed;
- more information is required from the user;
- confirmation is required;
- the task fails safely; or
- the user ends the session.

---

## 3. What the Planner Does

The Planner is the decision-making component for the next step only.

On every call, it:

1. reads the active user goal;
2. reads the latest user input, if there is one;
3. inspects the current application;
4. inspects the current Compact UI State;
5. considers the most recent action result and short history;
6. selects exactly one next action;
7. returns one structured JSON decision.

The Planner follows this reasoning pattern:

```text
Understand the active goal
→ inspect the current state
→ use recent results to avoid repetition
→ choose one safe action
→ return the structured decision
```

After that, the Planner stops. It does not execute anything.

---

## 4. What the Planner Does Not Do

The Planner does not:

- receive raw audio;
- perform Speech-to-Text or Text-to-Speech;
- receive the raw Android `AccessibilityNodeInfo` tree;
- build or clean the Compact UI State;
- create `target_id` values;
- tap, type, scroll, or open applications directly;
- execute several actions in one response;
- assume that an action succeeded;
- manage the full voice session;
- bypass the Validator;
- use a stale UI target;
- directly access the Android Target Registry.

The Planner proposes the next action. Other components validate and execute it.

---

## 5. Core Agent Loop

The final architecture uses a repeated:

```text
Plan → Validate → Execute → Observe → Re-plan
```

Detailed sequence:

1. The user gives a command.
2. STT converts the command to text.
3. Android captures the foreground application and Accessibility Tree.
4. Android converts the raw tree into a Compact UI State.
5. The backend sends the Planner input to Qwen.
6. Qwen returns one Planner decision.
7. The backend validates the JSON and decision semantics.
8. Android performs a final live validation.
9. Android executes one action.
10. Android waits for the result or screen change.
11. Android captures a new Compact UI State.
12. The Planner is called again.

The Planner never returns an unobserved sequence such as:

```text
open WhatsApp → tap Search → type Ahmed → tap first result
```

Instead, it returns one action, waits for observation, and then decides again.

---

## 6. Component Responsibilities

| Component | Responsibility |
|---|---|
| STT | Converts Arabic speech into text. |
| Session/Goal Manager | Maintains the active goal, short dialogue context, and confirmation state. |
| Accessibility Service | Captures the current Android interface. |
| UI State Builder | Converts the raw tree into a small, clean, safe representation. |
| Target Registry | Maps current `target_id` values to real Android nodes locally. |
| Planner | Selects exactly one next action. |
| Backend Validator | Validates the model JSON, fields, targets, and safety rules. |
| Android Validator | Checks the current screen and live node immediately before execution. |
| Android Executor | Performs the real action on the phone. |
| TTS | Speaks questions, confirmations, results, and failures. |

Only the Planner decision logic is replaced when moving from the Mock Planner to Qwen.

---

## 7. Exact Planner Input

The Planner receives one JSON object with these fields:

```json
{
  "user_input": "افتح محادثة أحمد",
  "active_goal": "فتح محادثة أحمد في واتساب",
  "current_app": {
    "package_name": "com.whatsapp",
    "app_name": "WhatsApp"
  },
  "ui_state": {
    "screen_version": 12,
    "elements": [
      {
        "target_id": "e17",
        "role": "list_item",
        "label": "أحمد محمد",
        "actions": ["tap"]
      }
    ]
  },
  "conversation_context": [],
  "last_action_result": null,
  "action_history": []
}
```

These are the logical inputs required by the Planner. The backend may format them into a model prompt, but it must preserve their meaning.

### 7.1 Input Field Definitions

| Field | Type | Required | Meaning |
|---|---|---:|---|
| `user_input` | string or null | Yes | The latest user command, answer, correction, or confirmation. It is null when re-planning after an action without new speech. |
| `active_goal` | string | Yes | The stable task currently being completed. |
| `current_app` | object | Yes | The foreground application at the time of observation. |
| `ui_state` | object | Yes | The latest Compact UI State generated on Android. |
| `conversation_context` | array | Yes | The last two or three relevant dialogue turns. |
| `last_action_result` | object or null | Yes | The outcome of the immediately preceding action. |
| `action_history` | array | Yes | A short list of recent decisions and outcomes. |

The fields should always be present. When no value exists, use `null` or an empty array as appropriate.

---

## 8. Input Details

### 8.1 `user_input`

`user_input` is the most recent text received from the user.

Initial command:

`"user_input": "افتح محادثة أحمد"`

Clarification answer:

`"user_input": "أقصد أحمد محمد"`

Confirmation answer:

`"user_input": "نعم أرسلها"`

Automatic re-planning after Android executes an action:

`"user_input": null`

The Planner must not replace the full goal with a short answer such as `"نعم"`. It must interpret the answer together with `active_goal`, `conversation_context`, and recent history.

### 8.2 `active_goal`

`active_goal` is the stable task being completed.

Example:

`"active_goal": "إرسال رسالة إلى أحمد محمد تقول: بتأخر عشر دقائق"`

The goal remains stable across all action steps. It changes only if:

- the user modifies the task;
- the user cancels it;
- the task is completed; or
- a new task begins.

The Planner reads the goal but does not own the goal lifecycle.

### 8.3 `current_app`

Example:

```json
{
  "package_name": "com.whatsapp",
  "app_name": "WhatsApp"
}
```

Required fields:

| Field | Type | Meaning |
|---|---|---|
| `package_name` | string or null | Android package of the current application. |
| `app_name` | string or null | Human-readable application name. |

If the foreground application cannot be identified, both fields may be null.

### 8.4 `ui_state`

The Planner receives a compact representation, not the raw Accessibility Tree.

Example:

```json
{
  "screen_version": 12,
  "elements": [
    {
      "target_id": "e01",
      "role": "button",
      "label": "بحث",
      "actions": ["tap"]
    },
    {
      "target_id": "e17",
      "role": "list_item",
      "label": "أحمد محمد",
      "actions": ["tap"]
    },
    {
      "target_id": "e18",
      "role": "list_item",
      "label": "أحمد خالد",
      "actions": ["tap"]
    }
  ]
}
```

Required UI State fields:

| Field | Type | Meaning |
|---|---|---|
| `screen_version` | integer | Version assigned by Android to the current screen snapshot. |
| `elements` | array | The relevant visible and actionable elements. |

Required element fields:

| Field | Type | Meaning |
|---|---|---|
| `target_id` | string | Snapshot-scoped identifier generated by Android. |
| `role` | string | Simple role such as `button`, `text_field`, `list_item`, `text`, or `unknown`. |
| `label` | string | Normalized user-relevant label. |
| `actions` | array | Actions supported by the element, such as `tap`, `type`, or `scroll`. |

Android may later add optional descriptive fields such as `secondary_text`, but the Planner must not depend on them unless both teams explicitly update this contract.

### 8.5 `conversation_context`

This field contains only the last relevant dialogue turns.

Example:

```json
[
  {
    "role": "assistant",
    "text": "وجدت أحمد محمد وأحمد خالد، أي واحد تقصد؟"
  },
  {
    "role": "user",
    "text": "أحمد محمد"
  }
]
```

Recommended size:

- two or three turns;
- only turns relevant to the active goal;
- no long-term conversation history in the MVP.

### 8.6 `last_action_result`

This describes the result of the immediately preceding execution attempt.

Success example:

```json
{
  "action": "open_app",
  "success": true,
  "result_code": "ACTION_SUCCEEDED",
  "details": "WhatsApp became the foreground application."
}
```

Failure example:

```json
{
  "action": "tap",
  "success": false,
  "result_code": "SCREEN_UNCHANGED",
  "details": "The tap was dispatched but no screen change was observed."
}
```

The Planner must use this field to avoid assuming success and to recover from failure.

Recommended result codes include:

- `ACTION_SUCCEEDED`
- `TARGET_NOT_FOUND`
- `STALE_TARGET`
- `ACTION_NOT_SUPPORTED`
- `SCREEN_UNCHANGED`
- `APP_NOT_FOUND`
- `TEXT_INPUT_FAILED`
- `TIMEOUT`
- `EXECUTOR_ERROR`
- `USER_DENIED_CONFIRMATION`

### 8.7 `action_history`

This is a bounded history used to prevent loops.

Example:

```json
[
  {
    "action": "open_app",
    "target": "WhatsApp",
    "success": true
  },
  {
    "action": "tap",
    "target_id": "e17",
    "success": false,
    "result_code": "SCREEN_UNCHANGED"
  }
]
```

Keep only the most recent three to five records.

The Planner should not repeat the same failed action indefinitely. After two unsuccessful attempts toward the same immediate objective, it should ask the user for help or return a controlled failure.

---

## 9. Exact Planner Output

The Planner returns one JSON object:

```json
{
  "reason": "محادثة أحمد محمد ظاهرة في الشاشة.",
  "status": "continue",
  "action": "tap",
  "target": null,
  "target_id": "e17",
  "value": null,
  "message": null
}
```

No prose, Markdown, explanation, or second action may appear outside this object.

### 9.1 Output Field Definitions

| Field | Type | Required | Meaning |
|---|---|---:|---|
| `reason` | string | Yes | A short operational reason for the selected decision. |
| `status` | enum | Yes | The state of the active task after this decision. |
| `action` | enum | Yes | Exactly one next action. |
| `target` | string or null | Yes | Semantic target used for non-UI actions, especially an application name. |
| `target_id` | string or null | Yes | UI element identifier copied from the current `ui_state`. |
| `value` | string or null | Yes | Action content, such as text to type or a scroll direction. |
| `message` | string or null | Yes | Arabic text to say to the user when asking, confirming, reporting completion, or reporting failure. |

Every field must be present, even when its value is `null`.

`reason` must be brief. It is not a request for hidden chain-of-thought reasoning.

---

## 10. Output Status Values

### `continue`

The task is still active and Android should execute the returned action.

### `task_completed`

The current task has been completed based on evidence in the latest state.

This completes only the active goal. It does not end the voice session.

### `needs_user_input`

The Planner needs clarification or missing information from the user.

### `needs_confirmation`

The Planner needs explicit user approval before a sensitive or consequential action.

### `failed`

The task cannot be completed safely after reasonable attempts.

### `end_session`

The user explicitly requested that the whole assistant session end.

`task_completed` and `end_session` must never be treated as the same status.

---

## 11. Supported Actions

The MVP action set is:

```text
open_app
tap
type
scroll
back
read_aloud
ask_user
confirm_with_user
none
```

### 11.1 `open_app`

Used to open an application.

Rules:

- `target` contains the application name;
- `target_id` is null;
- `value` is null.

Example:

```json
{
  "reason": "واتساب ليس التطبيق المفتوح حاليًا.",
  "status": "continue",
  "action": "open_app",
  "target": "WhatsApp",
  "target_id": null,
  "value": null,
  "message": null
}
```

### 11.2 `tap`

Used to activate one current UI element.

Rules:

- `target` is null;
- `target_id` is required;
- the ID must be copied from the current `ui_state.elements`;
- that element must support `tap`;
- `value` is null.

### 11.3 `type`

Used to insert text into an editable element.

Rules:

- `target` is null;
- `target_id` is required;
- the element must support `type`;
- `value` contains the exact text to enter.

### 11.4 `scroll`

Used to scroll a current scrollable UI element.

Rules:

- `target` is null;
- `target_id` identifies the scrollable element;
- the element must support `scroll`;
- `value` is normally `up`, `down`, `left`, or `right`.

### 11.5 `back`

Used for Android Back.

Rules:

- `target` is null;
- `target_id` is null;
- `value` is null.

### 11.6 `read_aloud`

Used to speak useful information without changing the UI.

Rules:

- `message` is required;
- `target` and `target_id` are normally null.

### 11.7 `ask_user`

Used when information is missing or ambiguous.

Rules:

- `status` must be `needs_user_input`;
- `message` is required;
- no UI target is used.

### 11.8 `confirm_with_user`

Used before a sensitive or consequential action.

Rules:

- `status` must be `needs_confirmation`;
- `message` must describe the intended action clearly;
- the sensitive action is not executed in the same decision.

### 11.9 `none`

Used when no Android action is needed.

Normally paired with:

- `task_completed`;
- `failed`; or
- `end_session`.

### 11.10 No `search_ui` Action

There is no separate `search_ui` action in the MVP.

Searching is represented using normal observed steps:

```text
tap the Search element
→ observe
→ type the query
→ observe
→ tap the correct result
```

This preserves the one-action loop.

---

## 12. `target` Versus `target_id`

This distinction is mandatory.

### `target`

Use `target` for a semantic target that is not a current UI element.

Primary MVP example:

```json
{
  "action": "open_app",
  "target": "WhatsApp",
  "target_id": null
}
```

### `target_id`

Use `target_id` for an action on a real UI element from the current Compact UI State.

Example:

```json
{
  "action": "tap",
  "target": null,
  "target_id": "e17"
}
```

The Planner must copy `e17` from the current `ui_state`. It must never guess or generate an element ID.

---

## 13. Status and Action Compatibility

| Status | Allowed actions |
|---|---|
| `continue` | `open_app`, `tap`, `type`, `scroll`, `back`, `read_aloud` |
| `task_completed` | `none`, `read_aloud` |
| `needs_user_input` | `ask_user` |
| `needs_confirmation` | `confirm_with_user` |
| `failed` | `none`, `read_aloud` |
| `end_session` | `none`, `read_aloud` |

Any incompatible combination must be rejected by the Validator.

### Action Field Matrix

| Action | `target` | `target_id` | `value` | `message` |
|---|---|---|---|---|
| `open_app` | Required | Null | Null | Optional |
| `tap` | Null | Required | Null | Optional |
| `type` | Null | Required | Required | Optional |
| `scroll` | Null | Required | Required | Optional |
| `back` | Null | Null | Null | Optional |
| `read_aloud` | Null | Null | Null | Required |
| `ask_user` | Null | Null | Null | Required |
| `confirm_with_user` | Null | Null | Null | Required |
| `none` | Null | Null | Null | Recommended |

---

## 14. `screen_version` and Target Safety

`screen_version` belongs to the Planner input, not the Qwen output.

Android generates a new screen version whenever it creates a new UI snapshot:

```text
screen_version 12
e17 → Ahmed conversation node
```

After the screen changes:

```text
screen_version 13
old e17 is invalid
new registry is created
```

The backend binds the returned decision to the request that produced it. Android then checks that the active UI snapshot is still the same before executing a UI action.

The model does not need to generate or echo `screen_version`.

Before executing `tap`, `type`, or `scroll`, Android verifies:

1. the decision belongs to the latest Planner request;
2. the active screen version has not changed;
3. `target_id` exists in the current Target Registry;
4. the underlying node is still available and visible;
5. the node supports the requested action.

If any check fails, Android does not execute the action. It captures the latest screen and returns a failure through `last_action_result`.

---

## 15. Fields That Are Not Part of Qwen Output

The Qwen Planner must not generate:

- `schema_version`;
- `request_id`;
- `decision_id`;
- `timestamp`;
- `source_screen_version`;
- session identifiers;
- Android node information;
- backend tracing information.

The backend may maintain identifiers, timestamps, schema versions, and logs internally. These are infrastructure metadata, not part of the model decision defined in this contract.

The exact Qwen output remains:

```text
reason
+ status
+ action
+ target
+ target_id
+ value
+ message
```

---

## 16. Complete Planning Examples

### 16.1 Open WhatsApp

Current input:

```json
{
  "user_input": "افتح محادثة أحمد محمد",
  "active_goal": "فتح محادثة أحمد محمد في واتساب",
  "current_app": {
    "package_name": "com.android.launcher",
    "app_name": "Home"
  },
  "ui_state": {
    "screen_version": 3,
    "elements": []
  },
  "conversation_context": [],
  "last_action_result": null,
  "action_history": []
}
```

Planner output:

```json
{
  "reason": "المحادثة المطلوبة في واتساب وواتساب غير مفتوح.",
  "status": "continue",
  "action": "open_app",
  "target": "WhatsApp",
  "target_id": null,
  "value": null,
  "message": null
}
```

### 16.2 Tap the Visible Conversation

After Android opens WhatsApp, it captures a new state:

```json
{
  "user_input": null,
  "active_goal": "فتح محادثة أحمد محمد في واتساب",
  "current_app": {
    "package_name": "com.whatsapp",
    "app_name": "WhatsApp"
  },
  "ui_state": {
    "screen_version": 4,
    "elements": [
      {
        "target_id": "e05",
        "role": "button",
        "label": "بحث",
        "actions": ["tap"]
      },
      {
        "target_id": "e17",
        "role": "list_item",
        "label": "أحمد محمد",
        "actions": ["tap"]
      }
    ]
  },
  "conversation_context": [],
  "last_action_result": {
    "action": "open_app",
    "success": true,
    "result_code": "ACTION_SUCCEEDED",
    "details": "WhatsApp became the foreground application."
  },
  "action_history": [
    {
      "action": "open_app",
      "target": "WhatsApp",
      "success": true
    }
  ]
}
```

Planner output:

```json
{
  "reason": "محادثة أحمد محمد ظاهرة ويمكن فتحها.",
  "status": "continue",
  "action": "tap",
  "target": null,
  "target_id": "e17",
  "value": null,
  "message": null
}
```

### 16.3 Complete the Task After Observation

After Android taps and observes the conversation screen:

```json
{
  "user_input": null,
  "active_goal": "فتح محادثة أحمد محمد في واتساب",
  "current_app": {
    "package_name": "com.whatsapp",
    "app_name": "WhatsApp"
  },
  "ui_state": {
    "screen_version": 5,
    "elements": [
      {
        "target_id": "e02",
        "role": "text",
        "label": "أحمد محمد",
        "actions": []
      },
      {
        "target_id": "e12",
        "role": "text_field",
        "label": "اكتب رسالة",
        "actions": ["tap", "type"]
      }
    ]
  },
  "conversation_context": [],
  "last_action_result": {
    "action": "tap",
    "success": true,
    "result_code": "ACTION_SUCCEEDED",
    "details": "A new screen was observed."
  },
  "action_history": [
    {
      "action": "open_app",
      "target": "WhatsApp",
      "success": true
    },
    {
      "action": "tap",
      "target_id": "e17",
      "success": true
    }
  ]
}
```

Planner output:

```json
{
  "reason": "عنوان الشاشة يؤكد أن محادثة أحمد محمد مفتوحة.",
  "status": "task_completed",
  "action": "none",
  "target": null,
  "target_id": null,
  "value": null,
  "message": "تم فتح محادثة أحمد محمد. وش تبغى أسوي؟"
}
```

Completion is based on the observed new state, not merely on the successful tap.

---

## 17. Ambiguity Handling

If two elements match the user’s request, the Planner must not guess.

Input excerpt:

```json
{
  "user_input": "افتح محادثة أحمد",
  "active_goal": "فتح محادثة أحمد في واتساب",
  "ui_state": {
    "screen_version": 8,
    "elements": [
      {
        "target_id": "e17",
        "role": "list_item",
        "label": "أحمد محمد",
        "actions": ["tap"]
      },
      {
        "target_id": "e18",
        "role": "list_item",
        "label": "أحمد خالد",
        "actions": ["tap"]
      }
    ]
  }
}
```

Planner output:

```json
{
  "reason": "يوجد أكثر من عنصر يطابق اسم أحمد.",
  "status": "needs_user_input",
  "action": "ask_user",
  "target": null,
  "target_id": null,
  "value": null,
  "message": "وجدت أحمد محمد وأحمد خالد، أي واحد تقصد؟"
}
```

The Session/Goal Manager speaks the message, captures the answer, stores the relevant dialogue turn, and calls the Planner again.

---

## 18. Search Flow

If the target is not visible but a Search button is visible, the Planner uses ordinary actions.

Expected sequence:

```text
Planner returns tap on Search
→ Android executes
→ Android observes the search screen
→ Planner returns type with the contact name
→ Android executes
→ Android observes search results
→ Planner returns tap on the exact result
→ Android executes
→ Android observes the conversation
→ Planner returns task_completed
```

At every step, the Planner uses a `target_id` from the newest UI State.

---

## 19. Message Composition and Confirmation

Example goal:

> أرسل لأحمد محمد أني بتأخر عشر دقائق

The Planner may navigate to the conversation and type the draft. Before tapping Send, it must request confirmation.

### Confirmation Decision

```json
{
  "reason": "الرسالة جاهزة، لكن الإرسال يحتاج موافقة المستخدم.",
  "status": "needs_confirmation",
  "action": "confirm_with_user",
  "target": null,
  "target_id": null,
  "value": null,
  "message": "الرسالة جاهزة لأحمد محمد وتقول: بتأخر عشر دقائق. هل تريد إرسالها؟"
}
```

The Session/Goal Manager:

1. speaks the confirmation question;
2. records the pending sensitive action;
3. captures the user’s answer;
4. adds the relevant turns to context;
5. calls the Planner again.

After explicit approval, if the Send button is still visible in the latest UI State:

```json
{
  "reason": "المستخدم وافق وزر الإرسال ظاهر في الحالة الحالية.",
  "status": "continue",
  "action": "tap",
  "target": null,
  "target_id": "e21",
  "value": null,
  "message": null
}
```

The Validator must reject the final Send tap if there is no valid matching approval.

Sensitive actions include:

- sending a message;
- placing a call;
- submitting an order;
- confirming a purchase or payment;
- deleting content;
- granting permissions;
- sharing personal data;
- changing account or security settings;
- submitting an irreversible form.

---

## 20. Failure and Loop Handling

The Planner must use `last_action_result` and `action_history`.

If a tap produces no change:

1. inspect the new UI State;
2. choose another valid action only if there is clear evidence;
3. do not reuse a missing or stale `target_id`;
4. do not repeat the same failed decision more than twice;
5. ask the user or fail safely.

Controlled question:

```json
{
  "reason": "لم تنجح محاولتا فتح المحادثة ولا يوجد هدف بديل واضح.",
  "status": "needs_user_input",
  "action": "ask_user",
  "target": null,
  "target_id": null,
  "value": null,
  "message": "ما قدرت أفتح المحادثة بعد محاولتين. هل تريد أن أبحث عن الاسم كاملًا؟"
}
```

Controlled failure:

```json
{
  "reason": "لا توجد طريقة آمنة لإكمال المهمة من الحالة الحالية.",
  "status": "failed",
  "action": "none",
  "target": null,
  "target_id": null,
  "value": null,
  "message": "ما قدرت أكمل المهمة بأمان من الشاشة الحالية."
}
```

---

## 21. Compact UI State Builder Requirements

The UI State Builder runs on Android before the Planner call.

Version 1 should:

- keep visible elements;
- keep elements with a meaningful label or supported action;
- combine `text` and `contentDescription` into `label`;
- infer a simple role;
- list supported actions;
- assign a `target_id`;
- maintain the corresponding local node in the Target Registry;
- redact passwords and sensitive text;
- remove unnecessary Android metadata;
- limit the result to approximately 50 relevant elements;
- assign a new `screen_version` for each snapshot.

The first version does not need advanced app-specific semantic grouping. A clean, stable list is sufficient for the MVP.

---

## 22. Target Registry Requirements

The Target Registry is Android-only.

Example:

```text
screen_version 12:
e17 → current AccessibilityNodeInfo for "أحمد محمد"
```

Rules:

- IDs are valid only for one snapshot;
- a new snapshot replaces the old registry;
- the Planner sees IDs but never the underlying nodes;
- Android validates the node immediately before execution;
- old IDs must fail safely;
- stale nodes must never be clicked.

---

## 23. Backend Validation

The backend must validate every Qwen response before returning it for execution.

Validation pipeline:

```text
raw model output
→ parse JSON
→ verify exact fields
→ verify types and enum values
→ verify one action only
→ verify status/action compatibility
→ verify required and null fields
→ verify target_id against the request UI State
→ apply confirmation and safety policy
→ return the validated decision
```

Reject output when:

- JSON parsing fails;
- a required field is absent;
- unsupported fields are added;
- more than one action is returned;
- `target_id` is invented;
- `target_id` does not exist in the current request;
- the element does not support the requested action;
- `open_app` incorrectly uses `target_id`;
- `tap`, `type`, or `scroll` lacks `target_id`;
- `type` lacks `value`;
- status and action are incompatible;
- the decision attempts a sensitive action without approval.

The backend may repair simple formatting problems once, but it must never invent a target, change the user’s goal, or infer user confirmation.

---

## 24. Android Validation and Execution

Even after backend validation, Android performs the final live check.

For a UI action, Android verifies:

1. the decision was created from the latest request;
2. the screen has not changed since that request;
3. `target_id` exists in the active Target Registry;
4. the node is still visible and enabled;
5. the node supports the requested action;
6. local safety rules allow execution.

After execution, Android:

1. waits for the action result or timeout;
2. captures the current application;
3. builds a new UI State;
4. constructs `last_action_result`;
5. appends a short history record;
6. calls the Planner again.

---

## 25. Qwen Prompt Requirements

The Qwen system prompt must clearly state:

1. You are the next-action Planner for an Arabic Android accessibility agent.
2. Return exactly one valid JSON object.
3. Use the exact seven output fields.
4. Select exactly one action.
5. Use the active goal across all steps.
6. Use only the latest Compact UI State.
7. Never invent a `target_id`.
8. Copy `target_id` only from the current elements.
9. Use `target` for semantic non-UI targets such as an application name.
10. Do not return multi-step plans.
11. Never assume that an action succeeded.
12. Use the latest observed state and last result.
13. Ask the user when information is missing or ambiguous.
14. Request confirmation before sensitive actions.
15. Mark a task complete only when the current state contains evidence.
16. Distinguish task completion from ending the session.
17. Use recent history to avoid loops.
18. After two failed attempts, ask the user or fail safely.
19. Keep `reason` short and operational.
20. Write user-facing `message` text in natural Arabic.
21. Do not output Markdown or text outside the JSON object.

Recommended generation settings:

- deterministic or near-deterministic decoding;
- low temperature, preferably `0.0`;
- small output token limit;
- JSON Schema or grammar-constrained decoding when supported;
- strict generation timeout.

---

## 26. Recommended Planner Interface

The Mock Planner and Qwen Planner must expose the same logical interface:

```kotlin
interface Planner {
    suspend fun plan(input: PlannerInput): PlannerDecision
}
```

Implementations:

```kotlin
class MockPlanner : Planner
class RemoteQwenPlanner : Planner
```

The following components must not change when switching implementations:

- `PlannerInput`;
- `PlannerDecision`;
- UI State Builder;
- Target Registry;
- Validator;
- Android Executor;
- Agent Loop;
- action result reporting;
- observation after every action.

---

## 27. Mock Planner Role

The Mock Planner is temporary only as a decision source.

It returns deterministic decisions using the exact same input and output contract as Qwen.

Example logic:

```kotlin
override suspend fun plan(input: PlannerInput): PlannerDecision {
    if (input.currentApp.packageName != "com.whatsapp") {
        return PlannerDecision(
            reason = "واتساب غير مفتوح.",
            status = "continue",
            action = "open_app",
            target = "WhatsApp",
            targetId = null,
            value = null,
            message = null
        )
    }

    val matches = input.uiState.elements.filter {
        it.label.contains("أحمد") && "tap" in it.actions
    }

    if (matches.size == 1) {
        return PlannerDecision(
            reason = "المحادثة المطلوبة ظاهرة.",
            status = "continue",
            action = "tap",
            target = null,
            targetId = matches.first().targetId,
            value = null,
            message = null
        )
    }

    return PlannerDecision(
        reason = "لا يوجد تطابق واحد واضح.",
        status = "needs_user_input",
        action = "ask_user",
        target = null,
        targetId = null,
        value = null,
        message = "وجدت أكثر من نتيجة أو لم أجد المحادثة. ممكن توضح الاسم؟"
    )
}
```

With the Mock Planner:

- screen capture is real;
- UI State generation is real;
- validation is real;
- Android execution is real;
- the new observation is real;
- only the decision logic is mocked.

When Qwen is ready, only the Planner implementation is replaced.

---

## 28. Primary MVP Scenario

The first end-to-end scenario is:

> افتح محادثة أحمد محمد

Acceptance sequence:

1. The command is available as text.
2. Android captures the current state.
3. The Planner returns `open_app` if WhatsApp is not open.
4. Android opens WhatsApp.
5. Android observes the Chats screen.
6. The Planner returns `tap` using the current conversation `target_id`.
7. Android validates and taps the real node.
8. Android observes the conversation screen.
9. The Planner detects evidence that the correct conversation is open.
10. The Planner returns `task_completed`.
11. TTS reports completion.
12. The session remains ready for another command.

This must work first with the Mock Planner and later with Qwen without changing the surrounding architecture.

---

## 29. Implementation Plan

### Phase 1: Freeze Shared Data Models

- implement the exact Planner input fields;
- implement the exact seven Planner output fields;
- implement status and action enums;
- agree on null handling;
- create shared valid and invalid JSON fixtures.

### Phase 2: Android Perception

- implement Compact UI State v1;
- generate snapshot-scoped `target_id` values;
- implement Target Registry;
- add `screen_version`;
- redact sensitive fields;
- limit the element list.

### Phase 3: Real Execution with Mock Planner

- implement the Planner interface;
- implement the deterministic Mock Planner;
- implement backend and Android validation;
- connect the Android Executor;
- observe after every action;
- complete the primary WhatsApp scenario on a real phone.

### Phase 4: Qwen Planner

- build the Qwen system prompt;
- serialize the agreed Planner input;
- enforce structured output;
- parse the exact Planner decision;
- add validation and timeout handling;
- test against saved UI fixtures.

### Phase 5: Replace Mock with Qwen

- connect `RemoteQwenPlanner`;
- reuse the same input and output classes;
- compare Qwen results with expected Mock decisions;
- test malformed output and hallucinated targets;
- measure Planner latency.

### Phase 6: Additional Scenarios

- search for a contact not currently visible;
- compose a message;
- request confirmation;
- send after explicit approval;
- resolve duplicate contact names;
- recover from a failed action.

### Phase 7: Stabilization

- repeat real-device trials;
- test stale target rejection;
- test screen changes during planning;
- test backend timeout;
- test user denial;
- measure task completion and latency;
- record a backup demonstration video.

---

## 30. Planner Test Cases

| Situation | Expected decision |
|---|---|
| WhatsApp is not foreground | `continue + open_app + target="WhatsApp"` |
| Exact conversation is visible | `continue + tap + current target_id` |
| Two contacts match | `needs_user_input + ask_user` |
| Contact is not visible but Search exists | `continue + tap` on Search |
| Search field is visible | `continue + type` with the contact name |
| Previous target became stale | Select from the new state or ask/fail; never reuse the old ID |
| Previous action failed once | Reassess the new state before retrying |
| Same immediate objective failed twice | Ask the user or fail safely |
| Message is prepared but not approved | `needs_confirmation + confirm_with_user` |
| User approved and Send is visible | `continue + tap` on the current Send ID |
| Sent message is visible | `task_completed + none` |
| User asks to stop the assistant | `end_session + none` |

Required validation tests:

- invalid JSON is rejected;
- missing output field is rejected;
- extra output field is rejected;
- unsupported enum is rejected;
- invented `target_id` is rejected;
- `tap` without `target_id` is rejected;
- `type` without `value` is rejected;
- incompatible status/action is rejected;
- sensitive execution without confirmation is rejected;
- multiple actions are rejected.

---

## 31. Planner Definition of Done

The Planner is ready for Android integration when:

- it accepts the exact input defined in this document;
- it returns the exact seven output fields;
- it returns valid JSON only;
- it produces one action per call;
- it never invents `target_id`;
- it uses current UI elements only;
- it distinguishes `target` from `target_id`;
- it handles null `user_input` during automatic re-planning;
- it uses the stable `active_goal`;
- it uses recent results to avoid loops;
- it requests clarification for ambiguity;
- it requests confirmation before sensitive actions;
- it does not claim completion without observed evidence;
- it distinguishes `task_completed` from `end_session`;
- all shared Planner fixtures and Validator tests pass.

---

## 32. Final Contract Summary

### Planner Input

```text
user_input
+ active_goal
+ current_app
+ Compact UI State
+ short conversation_context
+ last_action_result
+ short action_history
```

### Planner Output

```text
reason
+ status
+ one action
+ target or target_id
+ value
+ message
```

### Execution Rule

```text
One action
→ validate
→ execute on Android
→ observe the new state
→ call the Planner again
```

### Non-Negotiable Rules

```text
The Planner decides only the next action.

The Planner never executes Android actions.

The Planner never receives the raw Accessibility Tree.

The Planner never invents target_id.

target is for semantic targets such as an application name.

target_id is for a current real UI element.

screen_version is input state managed by Android, not model output.

Infrastructure metadata is not part of the Qwen decision.

Sensitive actions require user confirmation.

Task completion does not end the voice session.

The Mock Planner and Qwen use the same contract.
```

This corrected contract allows the Android and Planner developers to work independently and integrate without changing the agent loop.

# Codex Master Implementation Prompt
## Real Android Agent Runtime with a Mock Planner

**Document version:** 1.0  
**Target project:** Existing Android application for an Arabic mobile agent for blind users  
**Implementation language:** Follow the existing project; prefer Kotlin when the project is Kotlin-based  
**Today’s objective:** Build a real, testable Android agent runtime while the remote Qwen Planner is still being developed  
**Planner used in this task:** Deterministic local `MockPlanner`  
**Final integration target:** Replace `MockPlanner` with `RemoteQwenPlanner` without changing the agent loop, execution layer, UI-state contract, or validation rules

---

# Instructions to Codex

You are working inside an existing Android repository. Your task is to inspect that repository, understand the implementation already present, and then implement the Android agent runtime described in this document.

This is not a request for a high-level explanation, sample snippets, pseudocode-only output, or an isolated demo that does not control the real phone. You must make the changes inside the repository, run the relevant Gradle builds and tests, fix failures caused by your changes, review the final diff, and report what still requires physical-device verification.

Do not invent facts about the repository. Inspect before deciding:

- the project modules;
- package names;
- Kotlin versus Java usage;
- UI framework, such as Jetpack Compose or XML views;
- minimum and target Android SDK versions;
- dependency-injection approach, if any;
- serialization and networking libraries already used;
- current `AccessibilityService`;
- current UI-tree capture code;
- current speech, planner, and demo components;
- existing tests;
- build variants and Gradle commands;
- repository-level or nested `AGENTS.md` instructions.

If this document uses a conceptual class name but the repository already contains an equivalent component, extend or refactor the existing component instead of creating a duplicate. Preserve unrelated functionality and user changes.

Do not merely describe what you would implement. Implement it.

---

# 1. Product Context

The product is an Arabic voice-controlled mobile agent designed to help blind or low-vision users complete multi-step tasks on Android.

The intended complete system is:

```text
Arabic speech
→ Speech-to-Text
→ stable active goal
→ observe the current Android UI
→ build a Compact UI State
→ ask the Planner for one next action
→ validate the Planner decision
→ execute one real Android action
→ observe the resulting UI
→ call the Planner again
```

The teammate responsible for the Planner is building a Qwen-based decision service separately. This task must not implement or redesign that remote Planner. Instead, build the Android-side runtime using a deterministic `MockPlanner` that obeys exactly the same logical input and output contract.

Everything in this task must be real except the decision-making intelligence:

| Part | Required behavior in this task |
|---|---|
| Current foreground app | Real |
| Accessibility UI observation | Real |
| Compact UI State | Built from the real accessibility tree |
| Target IDs and registry | Real and snapshot-scoped |
| Planner decision | Mocked with deterministic rules |
| Decision validation | Real |
| App opening | Real |
| Tapping | Real |
| Typing | Real where supported |
| Scrolling | Real where supported |
| Android Back | Real |
| Post-action observation | Real |
| Agent loop | Real |
| Qwen/LLM inference | Not part of this task |

The primary first scenario is:

> `افتح محادثة أحمد محمد`

The expected real-device behavior is:

```text
Receive the command as text
→ open WhatsApp if it is not foreground
→ observe the actual WhatsApp UI
→ locate the current element representing أحمد محمد
→ return a MockPlanner tap decision using that element’s real target_id
→ validate the target against the current snapshot
→ tap the real accessibility node
→ observe the new UI
→ verify that the requested conversation is actually open
→ finish the active task
```

The system must not declare completion only because a tap returned `true`. Completion requires evidence in a newly observed UI state.

---

# 2. Exact Scope of This Task

Implement or complete the following:

1. Repository inspection and integration plan.
2. Shared Planner input and decision models.
3. A replaceable `Planner` interface.
4. Compact UI-state extraction from the current accessibility tree.
5. Snapshot-scoped target registration.
6. Strict decision validation.
7. Real Android action execution.
8. A deterministic `MockPlanner`.
9. A one-action-at-a-time `AgentLoop`.
10. UI-change observation and action result reporting.
11. Cancellation, timeouts, step limits, failure handling, and loop protection.
12. Structured and privacy-aware logging.
13. A minimal way to start and cancel the scenario from the existing app UI.
14. Unit tests and any practical Android tests.
15. Successful Gradle build and test execution.
16. A final implementation and physical-device test report.

If the repository already implements part of this list, verify it against the requirements and reuse it. Do not replace correct working code just to match the conceptual names in this document.

---

# 3. Explicit Non-Goals

Do not add or implement any of the following unless a tiny compatibility change is strictly required:

- a real Qwen integration;
- another LLM or VLM;
- a new speech-recognition model;
- faster-whisper, whisper.cpp, Vosk, or any STT redesign;
- a cloud backend;
- prompt engineering for the remote Planner;
- a database;
- a general automation framework for every Android application;
- OCR or screenshot-based computer vision;
- WhatsApp-specific private APIs;
- root access;
- ADB-based execution inside the production app;
- UI Automator as the production executor;
- hidden or unsupported Android APIs;
- sending a real message;
- placing a real call;
- purchasing, ordering, deleting, granting permissions, or other sensitive actions;
- unrelated redesigns of the application UI;
- broad dependency or architecture migrations.

Do not silently expand the MVP from “open a conversation” into “compose and send a message.”

The executor may support `type` because it is part of the shared action contract, but the primary acceptance scenario does not send any content.

---

# 4. Non-Negotiable Architecture

Implement this separation:

```text
Goal source
    ↓
AgentLoop
    ↓
UiObservationSource / AccessibilityService
    ↓
CompactUiStateBuilder + TargetRegistry
    ↓
Planner
    ↓
DecisionValidator
    ↓
AndroidExecutor
    ↓
UiChangeObserver
    ↓
fresh observation and re-planning
```

The Planner chooses only the next single action.

The Planner must not:

- receive raw audio;
- receive the raw `AccessibilityNodeInfo` tree;
- build target IDs;
- hold Android node objects;
- tap or type directly;
- return several actions;
- assume an action succeeded;
- decide that an old target is still valid after a new snapshot;
- bypass the Validator;
- end the complete voice session merely because one task completed.

The Android runtime owns:

- the real UI observation;
- snapshot versions;
- target creation and lookup;
- live target validation;
- action execution;
- UI-change detection;
- action results;
- loop limits;
- cancellation;
- sensitive-action enforcement.

The remote Qwen integration must later require only a new implementation of:

```kotlin
interface Planner {
    suspend fun plan(request: PlannerRequest): PlannerDecision
}
```

Do not put MockPlanner-specific branching inside the executor or agent loop.

---

# 5. Repository Inspection Protocol

Before editing:

1. Read all applicable `AGENTS.md` files.
2. Inspect `settings.gradle`, `settings.gradle.kts`, module Gradle files, version catalogs, and the manifest.
3. Locate the application module and existing package structure.
4. Locate the current accessibility service and its XML configuration.
5. Locate code that captures or serializes the accessibility tree.
6. Locate any existing Planner interface, planner request/response models, or API client.
7. Locate the existing demo UI, ViewModel, service binding, repositories, and coroutines.
8. Locate tests and test dependencies.
9. Check the working tree and preserve unrelated modifications.
10. Identify the exact Gradle wrapper commands that work in this repository.

Then produce a concise internal implementation plan mapped to existing files. Continue directly into implementation unless a truly blocking ambiguity would risk destructive or incompatible changes.

Do not ask the user questions that the repository can answer.

Examples of facts that must be discovered rather than assumed:

- whether the app uses Compose;
- whether `kotlinx.serialization`, Moshi, or Gson is present;
- whether Hilt, Koin, manual construction, or no DI is used;
- the actual package containing the accessibility service;
- how UI state currently reaches the app screen;
- whether `MockPlanner` or equivalent code already exists;
- whether the app already has an action executor;
- whether the application has multiple modules.

---

# 6. Preserve Existing Functionality

Follow these rules while editing:

- Keep existing speech functionality working.
- Keep existing tree-preview functionality working.
- Keep existing planner demo modes working unless this task explicitly replaces a broken duplicate.
- Do not rename packages or modules broadly.
- Do not change application ID, signing, ABI configuration, SDK targets, or release settings without necessity.
- Avoid introducing new dependencies when the existing stack can implement the requirement.
- Do not remove user code because it appears unused unless it is unquestionably replaced within this task.
- Do not overwrite unrelated local changes.
- Do not use destructive Git commands.
- Keep production logic testable by separating Android framework objects from pure decision logic.

When the existing design conflicts with this specification, make the smallest coherent refactor needed and explain it in the final report.

---

# 7. Planner Contract

The Mock Planner and future Qwen Planner must use the same logical contract.

## 7.1 `PlannerRequest`

The logical request is:

```json
{
  "user_input": "افتح محادثة أحمد محمد",
  "active_goal": "فتح محادثة أحمد محمد في واتساب",
  "current_app": {
    "package_name": "com.whatsapp",
    "app_name": "WhatsApp"
  },
  "ui_state": {
    "screen_version": 12,
    "elements": [
      {
        "target_id": "e017",
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

Required logical fields:

| Field | Type | Rules |
|---|---|---|
| `user_input` | `String?` | Latest user command or answer. It becomes `null` when re-planning after an Android action without new speech. |
| `active_goal` | `String` | Stable goal for the current task. Do not replace it with `null` after the first action. |
| `current_app` | object | Current foreground package and readable app name when available. |
| `ui_state` | object | Current compact snapshot, never the raw tree. |
| `conversation_context` | list | Empty in the initial scenario; bounded for future questions and confirmations. |
| `last_action_result` | object or null | Outcome of the immediately preceding execution attempt. |
| `action_history` | list | Bounded recent actions and outcomes for loop prevention. |

Use the project’s existing JSON naming convention. If serialized JSON must match the contract, map Kotlin camelCase properties to the snake_case field names explicitly through the project’s serialization library.

Do not add infrastructure metadata such as request IDs or timestamps to the model decision. Internal wrappers may hold metadata without changing the seven-field Planner output.

## 7.2 Current App

Logical shape:

```json
{
  "package_name": "com.whatsapp",
  "app_name": "WhatsApp"
}
```

Both values may be nullable only if the runtime truly cannot identify the foreground application.

## 7.3 Compact UI State

Logical shape:

```json
{
  "screen_version": 12,
  "elements": [
    {
      "target_id": "e001",
      "role": "button",
      "label": "بحث",
      "actions": ["tap"]
    },
    {
      "target_id": "e017",
      "role": "list_item",
      "label": "أحمد محمد",
      "actions": ["tap"]
    }
  ]
}
```

The stable MVP element contract is:

| Field | Required | Meaning |
|---|---:|---|
| `target_id` | Yes | Identifier valid only for this snapshot. |
| `role` | Yes | Normalized role such as `button`, `text_field`, `list_item`, `text`, `scrollable`, or `unknown`. |
| `label` | Yes | Normalized user-relevant text. It may be an empty string only for a meaningful actionable element with no accessible label. |
| `actions` | Yes | Supported contract actions for this element. |

Do not make the Mock Planner depend on optional fields that the teammate’s Planner contract does not include.

## 7.4 Action Result

Use a typed result rather than a Boolean alone.

Required information:

```json
{
  "action": "tap",
  "success": false,
  "result_code": "SCREEN_UNCHANGED",
  "details": "The action was dispatched, but no meaningful UI change was observed."
}
```

Support at least these result codes where applicable:

```text
ACTION_SUCCEEDED
TARGET_NOT_FOUND
STALE_TARGET
ACTION_NOT_SUPPORTED
TARGET_NOT_VISIBLE
TARGET_NOT_ENABLED
SCREEN_UNCHANGED
APP_NOT_FOUND
TEXT_INPUT_FAILED
TIMEOUT
EXECUTOR_ERROR
CANCELLED
USER_DENIED_CONFIRMATION
```

Use enums or sealed types internally when consistent with the project. Serialize them to the agreed strings only at boundaries.

## 7.5 Action History

Keep a bounded history, preferably the latest five records. Each record must contain enough information to identify repeated decisions and their result without storing Android node objects.

At minimum:

```json
{
  "action": "tap",
  "target": null,
  "target_id": "e017",
  "value": null,
  "success": false,
  "result_code": "SCREEN_UNCHANGED"
}
```

Because target IDs are snapshot-scoped, repeated-action detection must also consider screen content or screen version. Do not assume that `e017` on a later snapshot is the same element.

---

# 8. Exact Planner Decision

The Planner returns exactly one decision containing these seven logical fields:

```json
{
  "reason": "محادثة أحمد محمد ظاهرة في الشاشة الحالية.",
  "status": "continue",
  "action": "tap",
  "target": null,
  "target_id": "e017",
  "value": null,
  "message": null
}
```

Required fields:

| Field | Type | Meaning |
|---|---|---|
| `reason` | `String` | Short operational reason. Do not use it as a chain-of-thought trace. |
| `status` | enum | Current task state after this decision. |
| `action` | enum | Exactly one next action. |
| `target` | `String?` | Semantic non-UI target, primarily an app name for `open_app`. |
| `target_id` | `String?` | ID copied from the current `ui_state`. |
| `value` | `String?` | Exact text or direction required by an action. |
| `message` | `String?` | Natural Arabic text intended for the user. |

Every field must exist at serialized boundaries even when its value is `null`.

Do not add these to the Planner decision:

- `request_id`;
- `decision_id`;
- `timestamp`;
- `schema_version`;
- `source_screen_version`;
- session IDs;
- Android node metadata.

If the runtime needs to bind a decision to a snapshot, create an internal non-model envelope, for example:

```kotlin
data class BoundPlannerDecision(
    val sourceScreenVersion: Long,
    val sourceFingerprint: String,
    val decision: PlannerDecision
)
```

This binding is produced by the runtime, not by the Mock Planner and not by the future Qwen model.

---

# 9. Status and Action Enums

Supported task statuses:

```text
continue
task_completed
needs_user_input
needs_confirmation
failed
end_session
```

Supported actions:

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

There is no `search_ui` action. Search is a sequence of ordinary one-action cycles:

```text
tap Search
→ observe
→ type query
→ observe
→ tap result
→ observe
```

Do not create a multi-action object, an action array, or a script field.

---

# 10. Decision Validation Matrix

Implement strict validation before execution.

| Status | Allowed actions |
|---|---|
| `continue` | `open_app`, `tap`, `type`, `scroll`, `back`, `read_aloud` |
| `task_completed` | `none`, `read_aloud` |
| `needs_user_input` | `ask_user` |
| `needs_confirmation` | `confirm_with_user` |
| `failed` | `none`, `read_aloud` |
| `end_session` | `none`, `read_aloud` |

Field rules:

| Action | `target` | `target_id` | `value` | `message` |
|---|---|---|---|---|
| `open_app` | Required | Must be null | Must be null | Optional |
| `tap` | Must be null | Required | Must be null | Optional |
| `type` | Must be null | Required | Required | Optional |
| `scroll` | Must be null | Required | Required direction | Optional |
| `back` | Must be null | Must be null | Must be null | Optional |
| `read_aloud` | Must be null | Must be null | Must be null | Required |
| `ask_user` | Must be null | Must be null | Must be null | Required |
| `confirm_with_user` | Must be null | Must be null | Must be null | Required |
| `none` | Must be null | Must be null | Must be null | Recommended for terminal reporting |

For `tap`, `type`, and `scroll`, validation must also confirm:

1. the bound source snapshot is still current;
2. the `target_id` exists in the current Target Registry;
3. the matching compact element exists;
4. the element advertises the requested action;
5. the live node is still usable immediately before execution;
6. local safety policy permits the action.

Return a typed validation result:

```kotlin
sealed interface ValidationResult {
    data object Valid : ValidationResult
    data class Invalid(
        val code: ValidationErrorCode,
        val message: String
    ) : ValidationResult
}
```

Use equivalent project conventions if sealed interfaces are unavailable or inconsistent with the codebase.

Never “repair” an invalid target by choosing another node inside the Validator. The Validator checks; it does not plan.

---

# 11. Compact UI State Builder

Implement a production component that converts the current accessibility root into:

1. a compact serializable state for the Planner; and
2. a local Target Registry for execution.

Do not send `AccessibilityNodeInfo`, view IDs, bounds, class names, raw bundles, or full tree structure to the Planner unless already required by an approved existing contract. The MVP contract above is intentionally small.

## 11.1 Testable Design

Do not make all filtering logic depend directly on final Android framework classes in unit tests.

Use one of these approaches:

- create a small internal `UiNodeSnapshot`/`AccessibilityNodeAdapter` abstraction and map Android nodes into it; or
- isolate framework traversal from pure normalization/filtering functions.

The goal is to unit-test:

- label normalization;
- role inference;
- action extraction;
- filtering;
- password redaction;
- element limiting;
- deterministic target assignment;
- duplicate-label behavior.

Do not build an unnecessary generic framework. Use the smallest abstraction that makes core logic testable.

## 11.2 Snapshot Creation

For every completed observation:

1. obtain the current root from the existing accessibility service;
2. identify the current foreground package;
3. traverse the tree deterministically;
4. extract candidate nodes;
5. normalize labels and roles;
6. resolve executable actions;
7. filter useless nodes;
8. redact sensitive values;
9. prioritize and limit candidates;
10. assign snapshot-scoped target IDs;
11. create the new registry;
12. atomically publish the compact state and matching registry;
13. invalidate and release the previous registry safely.

The compact state and registry must be produced from the same observation. Never publish a state whose IDs point to a different registry.

## 11.3 Traversal

Use deterministic traversal, normally depth-first pre-order or breadth-first order. Pick one and document it in code.

Requirements:

- handle a null root safely;
- handle nodes disappearing during traversal;
- prevent one malformed node from crashing the entire observation;
- use a configurable maximum visited-node limit;
- use a reasonable maximum depth if needed;
- avoid recursive stack overflow on abnormal trees;
- do not log the entire raw tree in production;
- release or clear retained framework-node references according to the Android APIs and the project’s SDK constraints.

## 11.4 Visibility and Usefulness

Prefer nodes that are:

- visible to the user;
- enabled when an action requires enabled state;
- labeled with meaningful text or content description; or
- directly actionable even if unlabeled.

Exclude nodes that are:

- invisible;
- empty and non-actionable;
- structural containers that provide no unique label or action;
- duplicates that add no planner value;
- known system noise;
- password content;
- outside the current relevant window.

Do not discard a labeled child merely because its actionable ancestor holds the actual click action. Resolve this case explicitly.

## 11.5 Actionable Ancestors

Real Android interfaces often expose text on a child node while the clickable action belongs to a parent.

Support this safely:

1. extract the user-facing label from the most meaningful node;
2. locate the nearest appropriate ancestor that supports the action;
3. store the executable node in the Target Registry entry;
4. advertise only actions that the registered executable target can perform;
5. avoid creating several identical planner elements for the same executable node unless they represent genuinely different choices.

This is important for conversation rows in applications such as WhatsApp.

Do not wait until execution to guess arbitrary ancestors. Make target resolution deterministic during snapshot building, while still rechecking the live node at execution.

## 11.6 Label Normalization

Construct the best user-relevant label from available accessibility properties, following existing project behavior where appropriate.

Consider:

- `text`;
- `contentDescription`;
- hint text for editable fields;
- a meaningful labeled descendant when the actionable container itself is unlabeled.

Rules:

- trim whitespace;
- collapse repeated whitespace;
- avoid joining identical text twice;
- preserve Arabic text;
- do not translate labels;
- do not lowercase or modify Arabic display text;
- do not serialize password values;
- cap individual label length to a reasonable constant;
- do not include timestamps, package metadata, or class names unless they are already part of the accessible user-facing label.

For matching inside `MockPlanner`, use a separate normalized comparison string rather than modifying the label sent to the user.

Arabic matching normalization may safely normalize common orthographic variants for comparison, such as:

```text
أ / إ / آ → ا
ى → ي
ة may remain distinct unless a tested requirement says otherwise
remove tatweel
remove optional Arabic diacritics
collapse whitespace
```

Keep the original label for display and Planner state.

## 11.7 Password and Sensitive Text Redaction

If a node is a password field or otherwise marked sensitive:

- never include its actual value in `label`;
- never include its actual value in logs;
- use a neutral label such as `حقل كلمة مرور` or `[REDACTED]`;
- never include password text in action history;
- ensure tests use synthetic values and verify the secret is absent from serialized state and logs.

Do not collect more data than needed for the Planner.

## 11.8 Role Inference

Map Android accessibility information into a small role vocabulary. At minimum support:

```text
button
text_field
list_item
text
scrollable
checkbox
switch
image
unknown
```

Role inference must be deterministic. Use Android class/action semantics, not only English class-name substring guessing when stronger signals are available.

The Planner logic must tolerate `unknown`.

## 11.9 Action Extraction

Map supported Android actions to the Planner action vocabulary:

- click-capable target → `tap`;
- editable target with set-text support → `type`;
- scroll-forward/backward target → `scroll`.

Do not advertise actions the executor cannot perform.

Do not expose Android action integer IDs to the Planner.

## 11.10 Target IDs

Target IDs are:

- generated by Android;
- unique within one snapshot;
- valid only for that snapshot;
- copied by the Planner, never invented by it;
- not stable across screen versions.

Use a clear format such as:

```text
e001
e002
e003
```

Assignment must be deterministic for the final ordered element list in one snapshot.

Do not derive the ID from user text alone because labels can repeat. Do not use memory addresses, node hash codes, or sensitive data in IDs.

## 11.11 Screen Version

Maintain a monotonic in-process `screen_version`.

Rules:

- a newly published snapshot receives a new version;
- the matching registry stores the same version;
- a new published snapshot invalidates the previous registry;
- versions do not need to persist across application restarts;
- version overflow should be handled safely if using a bounded integer type;
- do not ask the Planner to echo the version in its output.

Prefer `Long` internally.

## 11.12 Element Limit and Prioritization

Default maximum compact elements: `50`, configurable as a named constant.

When more candidates exist, prioritize:

1. visible actionable labeled elements;
2. visible editable elements;
3. visible scrollable containers;
4. meaningful visible text useful for recognizing the current screen;
5. unlabeled actionable elements;
6. passive decorative elements last or excluded.

Do not simply take the first 50 raw nodes before normalization; that can remove the important conversation target.

Preserve stable traversal order within the same priority group.

## 11.13 Screen Fingerprint

Create a non-sensitive, deterministic content fingerprint used internally for UI-change and loop detection.

It may be based on:

- foreground package;
- ordered normalized compact roles;
- redacted labels;
- supported actions.

Do not use the incrementing screen version itself as proof that content changed. A new observation may have a new version while showing the same UI.

The fingerprint is runtime metadata and is not part of the seven-field Planner output.

---

# 12. Target Registry

The Target Registry maps the current compact element IDs to executable Android targets.

Conceptual model:

```kotlin
data class TargetEntry(
    val targetId: String,
    val screenVersion: Long,
    val advertisedActions: Set<PlannerAction>,
    val node: AccessibilityNodeInfo
)
```

Adapt ownership details to the Android SDK and existing service implementation.

Required behavior:

- one active registry snapshot at a time;
- atomic replacement;
- read-only lookup during validation/execution;
- old registry invalidation after new snapshot publication;
- no Android node objects in Planner requests, JSON, history, logs, or ViewModel state;
- safe release/cleanup of retained nodes;
- failure with `STALE_TARGET` when the decision’s bound version differs from the active registry;
- failure with `TARGET_NOT_FOUND` if the ID does not exist;
- recheck node visibility, enabled state, and action support immediately before execution.

Avoid global mutable maps without synchronization. The AccessibilityService, UI, and coroutine-based agent loop may operate on different callbacks or threads.

Use the project’s concurrency conventions. A single immutable registry snapshot stored in an atomic reference or guarded state is preferable to piecemeal mutation.

---

# 13. Planner Interface

Create or adapt:

```kotlin
interface Planner {
    suspend fun plan(request: PlannerRequest): PlannerDecision
}
```

Implement:

```kotlin
class MockPlanner : Planner
```

Make future implementation possible:

```kotlin
class RemoteQwenPlanner(...) : Planner
```

Do not implement the remote class unless an existing stub must compile. If a stub exists, preserve it and do not silently change the remote API contract.

The agent loop depends only on `Planner`.

Use constructor injection or the project’s existing DI mechanism so tests can supply fake planners, observers, validators, and executors.

---

# 14. Mock Planner Requirements

The Mock Planner must be deterministic and based only on `PlannerRequest`.

It must never:

- read Android nodes directly;
- access the Target Registry;
- call the executor;
- invent a target ID;
- return multiple actions;
- claim success without current-screen evidence;
- use hard-coded `e001`-style IDs;
- assume WhatsApp opened because it requested `open_app`;
- treat one matching name in the chats list as proof that the conversation is already open.

## 14.1 Goal Parsing for the MVP

Support the primary goal:

```text
افتح محادثة أحمد محمد
```

Derive the desired contact using a small, explicit, testable rule. Do not build a broad Arabic NLU system.

The simplest acceptable approach is:

- store the normalized active goal;
- recognize that the target application is WhatsApp;
- extract or configure the expected contact name for this scenario;
- match Arabic text using the comparison normalization described earlier.

If the existing UI already collects a contact separately, reuse it rather than re-parsing unnecessarily.

Keep the active goal stable across re-planning calls.

## 14.2 Deterministic Decision Order

Use the following ordered logic.

### Rule A: Task already completed

Before selecting a navigation action, determine whether the newest observed state contains sufficient evidence that the requested conversation is open.

Require more than the contact name alone. Evidence should include a combination such as:

- the requested contact appears as a screen/header label; and
- a message composer/editable field is visible; or
- other existing tested screen markers strongly identify a conversation view.

Do not mark complete if the contact name merely appears as a row in the chat list or search result.

Return:

```json
{
  "reason": "عنوان الشاشة وحقل الرسالة يؤكدان أن المحادثة المطلوبة مفتوحة.",
  "status": "task_completed",
  "action": "none",
  "target": null,
  "target_id": null,
  "value": null,
  "message": "تم فتح محادثة أحمد محمد. وش تبغى أسوي؟"
}
```

The exact Arabic completion message may follow the project’s chosen dialect, but it must be clear and natural.

### Rule B: WhatsApp is not foreground

Identify WhatsApp primarily by package name:

```text
com.whatsapp
```

If the existing project explicitly supports WhatsApp Business, keep it separate and do not silently treat it as the same app.

Return:

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

### Rule C: Search results or chat list contains one exact match

Find current elements whose normalized label exactly matches the desired contact and whose actions include `tap`.

If exactly one safe exact match exists, return a tap using that element’s current ID.

Do not construct the ID from list position after filtering; use the ID already present on that element.

### Rule D: Multiple exact matches

Do not guess.

Return:

```json
{
  "reason": "يوجد أكثر من عنصر مطابق للاسم المطلوب.",
  "status": "needs_user_input",
  "action": "ask_user",
  "target": null,
  "target_id": null,
  "value": null,
  "message": "وجدت أكثر من نتيجة باسم أحمد محمد. ممكن توضح أي محادثة تقصد؟"
}
```

The initial runtime may stop in `WaitingForUserInput` if follow-up speech integration is not in scope. It must not choose randomly.

### Rule E: One safe partial match

If there is no exact match and exactly one strong actionable partial match, use it only if the matching rule is explicit and covered by tests.

Prefer exact matching for the primary acceptance scenario. Do not implement fuzzy similarity that could open the wrong person’s conversation.

### Rule F: Search field is already visible

If an editable Search field is clearly visible and the contact query has not already been entered unsuccessfully, return `type` with:

- the current Search field’s `target_id`;
- the exact desired contact as `value`.

Do not type into a generic message composer.

Use role, label, current screen evidence, and history to distinguish search from chat composition.

### Rule G: Search button is visible

If the target is not visible and exactly one Search button is visible and tappable, return `tap` using its current ID.

### Rule H: Scrollable chat list is visible

Scrolling is not required for the first acceptance scenario. If implemented, allow one bounded downward scroll only when:

- the desired contact is not visible;
- no Search control is available;
- one clear scrollable list exists;
- history shows that this identical screen has not already been scrolled repeatedly.

Never scroll indefinitely.

### Rule I: Previous action failure

Read `last_action_result` and current state.

- Never reuse an old ID after a new snapshot.
- After `STALE_TARGET`, choose only from the new state.
- After `SCREEN_UNCHANGED`, re-evaluate the current elements before retrying.
- Do not repeat the identical failed immediate action more than twice.
- After repeated failure, ask the user or fail safely.

### Rule J: No safe action

Return a controlled result:

```json
{
  "reason": "لا يوجد هدف واضح وآمن في الشاشة الحالية.",
  "status": "failed",
  "action": "none",
  "target": null,
  "target_id": null,
  "value": null,
  "message": "ما قدرت أفتح المحادثة من الشاشة الحالية."
}
```

Do not fabricate screen controls.

---

# 15. Android Executor

Create or adapt an `AndroidExecutor` responsible only for execution. It must not contain planning rules.

Conceptual interface:

```kotlin
interface ActionExecutor {
    suspend fun execute(
        boundDecision: BoundPlannerDecision
    ): ExecutionDispatchResult
}
```

Use an equivalent API that fits the repository.

The executor must support:

```text
open_app
tap
type
scroll
back
```

`read_aloud`, `ask_user`, `confirm_with_user`, and terminal `none` are runtime/session outcomes, not accessibility-node actions. Route them through the existing TTS/UI layer if available; otherwise expose their messages and states cleanly without pretending speech occurred.

## 15.1 `open_app`

Requirements:

1. Resolve the semantic target through a controlled app mapping.
2. For this MVP, map `WhatsApp` to `com.whatsapp`.
3. Use `PackageManager` and a launch intent.
4. Add flags appropriate to launching from a service/application context.
5. Return `APP_NOT_FOUND` if no launchable activity exists.
6. Return dispatch success only when the launch request was issued.
7. Let post-action observation decide whether WhatsApp actually became foreground.

Do not claim the task is complete after dispatching the launch intent.

## 15.2 `tap`

Requirements:

1. Validate snapshot binding.
2. Resolve the target through the current registry.
3. Recheck live node state.
4. Verify advertised and live click support.
5. Perform the appropriate accessibility click action.
6. Record whether dispatch succeeded.
7. Wait for observation before returning final action success to the next Planner call.

If the snapshot builder already mapped a labeled child to a clickable ancestor, tap the registered executable node. Do not perform an unbounded ancestor search at execution time.

## 15.3 `type`

Requirements:

1. Validate the current editable target.
2. Request focus if needed.
3. use `ACTION_SET_TEXT` with the correct argument bundle when supported;
4. verify dispatch;
5. observe the resulting state;
6. never log the typed value if it is marked sensitive.

Avoid a clipboard-based fallback unless the existing project already has a reviewed implementation. Clipboard use can leak data and is unnecessary for the first scenario.

## 15.4 `scroll`

Map directions consistently:

- `down` normally uses scroll-forward;
- `up` normally uses scroll-backward;
- horizontal directions only if the live node exposes suitable actions.

Reject unsupported directions.

Do not simulate repeated swipes in one Planner decision.

## 15.5 `back`

Use the accessibility service’s Android global Back action.

Treat it as one action followed by observation.

## 15.6 Execution Errors

Catch expected framework failures and convert them to typed results. Do not crash the service or app.

Do not catch `CancellationException` as a generic failure. Preserve coroutine cancellation.

---

# 16. UI Change Observation

Action dispatch success and task success are different.

After every real action:

1. listen for relevant accessibility/window events;
2. allow the target app time to update;
3. capture a fresh observation;
4. compare the fresh content fingerprint and foreground package with the source;
5. classify the result;
6. send the fresh state and typed result into the next Planner request.

Use existing accessibility event flow if present.

The observer should react to relevant events such as:

- window state changed;
- windows changed;
- window content changed;
- view focused;
- view text changed;
- view scrolled.

Do not call every event a meaningful screen change. Debounce or coalesce event bursts.

Use configurable timeouts. Reasonable starting values may be chosen and centralized as constants, for example:

- normal UI action observation timeout: roughly 2–3 seconds;
- app launch timeout: roughly 4–6 seconds;
- short post-event settle/debounce window: roughly 150–300 ms.

These are defaults, not hidden magic numbers. Name them and make them easy to tune.

If no event arrives, perform a final snapshot capture before concluding `TIMEOUT` or `SCREEN_UNCHANGED`.

Result guidance:

- dispatch failed immediately → specific executor error;
- foreground package or content fingerprint changed meaningfully → `ACTION_SUCCEEDED`;
- dispatch succeeded but final fingerprint is unchanged → `SCREEN_UNCHANGED`;
- job was cancelled → `CANCELLED`;
- no usable root appeared before timeout → `TIMEOUT`.

Do not use a new incremented `screen_version` alone as evidence of meaningful change.

---

# 17. Agent Loop

Implement a coroutine-safe one-action loop.

Conceptual lifecycle:

```text
Idle
→ Observing
→ Planning
→ Validating
→ Executing
→ WaitingForUi
→ Observing
→ Planning
...
→ Completed / NeedsUserInput / NeedsConfirmation / Failed / Cancelled
```

Use the project’s state-management style. A sealed state hierarchy is recommended when compatible.

## 17.1 Start

When the user starts the scenario:

1. reject or cancel any existing active run according to a clear policy;
2. create a new coroutine job;
3. set the stable `active_goal`;
4. set initial `user_input` to the entered command;
5. clear previous action history and pending confirmation;
6. capture the current UI;
7. begin the loop.

Do not allow two agent loops to execute actions concurrently.

## 17.2 One Iteration

Each iteration must:

1. check cancellation;
2. check the maximum-step limit;
3. capture or receive one coherent current snapshot;
4. build `PlannerRequest`;
5. call `planner.plan(request)` once;
6. bind the returned decision internally to the source version and fingerprint;
7. record a redacted structured log;
8. validate the decision;
9. if invalid, do not execute it;
10. route terminal/user-interaction decisions without executor calls;
11. execute at most one Android action;
12. wait for and capture the result;
13. append one bounded history record;
14. set `user_input` to `null` for automatic re-planning;
15. set `last_action_result`;
16. continue using a fresh snapshot.

Never execute a second action from the same Planner response.

## 17.3 Terminal and Paused Decisions

Handle statuses exactly:

### `task_completed`

- stop the current task loop;
- expose the completion message;
- do not end the whole voice session automatically;
- retain no stale target for future execution.

### `needs_user_input`

- stop automatic execution;
- expose/speak `message`;
- enter `WaitingForUserInput`;
- do not guess the answer;
- allow a future user response to resume the same stable goal if existing app architecture supports it.

### `needs_confirmation`

- stop automatic execution;
- expose/speak the intended sensitive action clearly;
- store confirmation state outside the model decision;
- do not execute the sensitive action;
- the primary “open conversation” scenario should not require this state.

### `failed`

- stop the task loop safely;
- expose/speak the failure message;
- clear executable pending state.

### `end_session`

- stop the task and session according to existing app behavior;
- do not use this status for normal task completion.

## 17.4 Step Limit

Use a configurable maximum step count. A reasonable MVP default is `10`.

Count actual Planner iterations or executed decisions consistently and document the rule.

When reached:

- stop execution;
- produce a controlled failure;
- include a non-sensitive diagnostic in logs;
- do not issue another action.

## 17.5 Repeated-Action and Loop Detection

Create an internal action signature from:

```text
source fingerprint
+ action
+ semantic target or current element label/role
+ redacted value marker
+ result code
```

Do not rely on target ID alone.

Protect against:

- the same failed action on the same unchanged screen;
- alternating between two unchanged states;
- repeatedly opening an already foreground app;
- repeatedly tapping Search without reaching a new state;
- repeatedly typing the same query into the same unchanged field;
- endless scrolling.

After two failed attempts toward the same immediate objective, stop and ask the user or fail safely.

## 17.6 Cancellation

Provide cancellation from the existing UI or ViewModel.

Cancellation must:

- cancel the loop coroutine;
- prevent any later queued decision from executing;
- prevent a stale post-timeout callback from resuming execution;
- preserve `CancellationException`;
- update observable runtime state to `Cancelled`;
- clear or invalidate pending execution bindings.

## 17.7 Concurrency

Protect shared state:

- active job;
- active snapshot;
- Target Registry;
- current task state;
- action history;
- last action result.

Avoid blocking the main thread.

Android framework calls that require the main thread must be dispatched appropriately. Pure Planner, filtering, and validation logic may run off the main thread.

---

# 18. Safety Policy

The primary scenario is non-sensitive navigation.

Still implement a minimal extensible safety gate so the future Planner cannot directly perform sensitive actions without confirmation.

Treat at least these as sensitive:

- sending a message;
- placing a call;
- submitting an order;
- confirming a purchase or payment;
- deleting content;
- granting a permission;
- sharing personal data;
- changing account or security settings;
- submitting an irreversible form.

Because the current compact contract does not contain a universal semantic-risk field, implement only safe conservative rules supported by current evidence. Do not pretend that a generic label matcher fully solves safety.

At minimum:

- no message send is included in the Mock scenario;
- `confirm_with_user` never executes the sensitive action in the same decision;
- a future sensitive execution must be tied to explicit, recent, matching approval;
- validation fails closed when approval state is absent or stale.

Document limitations honestly.

---

# 19. UI Integration

Reuse the existing application screen.

Provide the minimum controls and observable information needed to run and debug the scenario:

- a text input or existing recognized-text field;
- a Start Agent button;
- a Cancel button while running;
- current agent state;
- current step number;
- latest Planner decision summary;
- latest validation/execution result;
- completion/failure/user-question message;
- existing Compact UI State preview if already present.

Default scenario text may be:

```text
افتح محادثة أحمد محمد
```

Do not redesign unrelated screens.

Do not expose raw `AccessibilityNodeInfo` objects to Compose/View state.

Do not show or log sensitive text.

If the accessibility service is disabled, show a clear actionable state rather than crashing or pretending the agent is running.

If a direct service reference is currently used, make lifecycle handling safe. Do not retain an Activity or Compose context in the service.

---

# 20. Accessibility Service Integration

Reuse the existing service when present.

Verify:

- it is declared in the manifest;
- its metadata XML is valid;
- required event types and flags are sufficient for current observation;
- it can access interactive window content;
- event handling feeds the UI-change observer;
- lifecycle and connection state are exposed safely;
- no Internet permission is added by this task;
- no unnecessary accessibility capability is requested.

If the service does not exist, implement the smallest service necessary for this runtime and add a clear setup path. Do not create a second competing service if one already exists.

Do not claim the physical scenario works merely because the service compiles. It must be tested on a phone later.

---

# 21. Logging and Diagnostics

Use structured, concise logs for every iteration.

Include:

- run/session-local ID generated by runtime, not Planner output;
- step number;
- current package;
- source screen version;
- shortened screen fingerprint;
- number of compact elements;
- Planner status/action;
- target ID only when present;
- validation result;
- execution dispatch result;
- UI observation result;
- next screen version and fingerprint change;
- stop reason.

Never log:

- raw audio;
- password values;
- full message contents when marked sensitive;
- the full accessibility tree by default;
- Android node object dumps;
- tokens or credentials;
- remote Planner secrets;
- unredacted serialized state in release builds.

Use the project’s logging framework. Avoid adding a large logging dependency.

Logs should make it possible to answer:

```text
What did the agent observe?
What one action did the Planner choose?
Why did validation allow or reject it?
Did Android dispatch the action?
Did the UI actually change?
Why did the loop continue or stop?
```

---

# 22. Error Handling

Handle these conditions explicitly:

- accessibility service disabled;
- no active window root;
- foreground package unknown;
- empty Compact UI State;
- app not installed;
- Planner throws;
- Planner returns an invalid decision;
- stale snapshot;
- missing target;
- target disappeared;
- action unsupported;
- click dispatch returns false;
- text set fails;
- UI does not change;
- observation timeout;
- maximum steps reached;
- repeated-action loop;
- user cancellation;
- service disconnects mid-run.

Do not use broad exception swallowing.

Preserve cancellation.

Every stopped run must end in an observable state with a useful message and diagnostic code.

Do not fabricate success to keep the demo moving.

---

# 23. Suggested Package/Component Structure

First follow the repository’s existing package structure.

If no equivalent structure exists, a reasonable organization is:

```text
agent/
  contract/
    Planner.kt
    PlannerModels.kt
  perception/
    CompactUiStateBuilder.kt
    UiNodeSnapshot.kt
    TargetRegistry.kt
    ScreenFingerprint.kt
  validation/
    DecisionValidator.kt
    SafetyPolicy.kt
  execution/
    AndroidExecutor.kt
    ExecutionModels.kt
    UiChangeObserver.kt
  loop/
    AgentLoop.kt
    AgentRuntimeState.kt
    LoopProtection.kt
  mock/
    MockPlanner.kt
```

This is guidance, not permission to ignore an existing clean architecture. Do not create needless layers or files.

---

# 24. Implementation Phases

Execute the task in the following phases. After each meaningful phase, run the narrowest useful tests or compile task and fix failures before continuing.

## Phase 0: Inspect and Map

Deliver internally:

- repository architecture map;
- discovered existing equivalents;
- files likely to change;
- exact build/test commands;
- risks such as service lifecycle, node ownership, or existing uncommitted changes.

Do not edit until applicable instructions and core files are read.

## Phase 1: Freeze Contracts

Implement:

- statuses;
- actions;
- Planner request;
- seven-field Planner decision;
- current app;
- compact UI state and element;
- action result;
- action history;
- `Planner` interface.

Add tests for:

- enum serialization if boundary serialization exists;
- required field mapping;
- explicit null behavior where the serializer is configured to omit nulls;
- JSON fixture compatibility when applicable.

Do not introduce remote network calls.

## Phase 2: Perception and Registry

Implement:

- framework traversal;
- pure normalization/filtering;
- label extraction;
- password redaction;
- role inference;
- action extraction;
- actionable-ancestor resolution;
- prioritization and 50-element limit;
- target ID assignment;
- screen version;
- fingerprint;
- atomic Target Registry publication and invalidation.

Test pure logic thoroughly.

## Phase 3: Validator

Implement:

- status/action compatibility;
- required/null field rules;
- current snapshot binding;
- target existence;
- advertised action support;
- minimal safety policy;
- typed errors.

Do not execute anything in validator tests.

## Phase 4: Executor and Observation

Implement:

- app mapping and launch;
- tap;
- type;
- scroll;
- back;
- event-based observation;
- timeout and final snapshot;
- typed action results.

Keep Planner logic out of this layer.

## Phase 5: Mock Planner

Implement the ordered deterministic rules.

Add fixture tests for:

- WhatsApp not foreground;
- exact visible chat;
- open conversation evidence;
- duplicate matches;
- Search button;
- Search text field;
- no safe action;
- previous stale target;
- repeated unchanged action.

## Phase 6: Agent Loop

Implement:

- start;
- one-decision iterations;
- internal decision binding;
- validation;
- execution;
- observation;
- fresh re-planning;
- step limit;
- loop detection;
- cancellation;
- paused and terminal states;
- structured logs.

Use fakes to test without a physical phone.

## Phase 7: UI Wiring

Connect the existing UI/ViewModel to:

- start with text;
- cancel;
- runtime states;
- current decision/result;
- user-facing completion/failure question.

Preserve speech and existing demo features.

## Phase 8: Verification

Run:

- unit tests;
- debug compilation;
- debug APK assembly;
- lint if the existing project supports it without unrelated baseline failures;
- any relevant existing tests;
- connected tests only when an authorized device is available.

Fix failures caused by this work.

Review the final diff for:

- stale-node risks;
- memory leaks;
- main-thread blocking;
- uncaught cancellation;
- duplicate implementations;
- privacy leaks;
- hard-coded target IDs;
- multi-action behavior;
- false task completion;
- unrelated changes.

---

# 25. Required Tests

Use the project’s current test framework. Add only lightweight test dependencies when truly needed.

## 25.1 Contract Tests

Test:

- all status values;
- all action values;
- exact Planner decision field mapping;
- null values preserved at JSON boundary when required;
- no accidental infrastructure fields in Planner output fixture.

## 25.2 UI State Builder Tests

Test:

- visible labeled actionable node is included;
- invisible node is excluded;
- empty non-actionable container is excluded;
- text and content description are normalized without duplication;
- Arabic text is preserved;
- comparison normalization handles common Alef variants;
- password value is absent;
- labeled child resolves to actionable ancestor;
- duplicate labels on different real targets remain distinguishable;
- repeated wrapper nodes do not create useless duplicates;
- advertised actions match executable support;
- IDs are unique within a snapshot;
- order is deterministic for identical input;
- only the highest-priority 50 elements remain;
- important actionable items are not pushed out by passive text;
- new snapshot receives a new version;
- previous registry becomes stale;
- fingerprint stays the same for identical redacted content;
- fingerprint changes for meaningful content change.

## 25.3 Validator Tests

Test valid decisions and reject:

- incompatible status/action;
- `open_app` without target;
- `open_app` with target ID;
- `tap` without target ID;
- `tap` with invented ID;
- `tap` on a target that does not advertise tap;
- `type` without value;
- `type` on non-editable target;
- invalid scroll direction;
- stale source screen version;
- missing message for user-interaction action;
- sensitive action without matching approval.

## 25.4 Mock Planner Tests

Test:

- opens WhatsApp when another app is foreground;
- does not repeatedly open WhatsApp when it is already foreground;
- taps exactly one exact matching conversation;
- copies the current target ID;
- never hard-codes or fabricates an ID;
- asks when exact matches are ambiguous;
- taps Search when appropriate;
- types into the Search field, not a message composer;
- completes only with conversation-view evidence;
- does not complete from a chat-list row;
- fails safely when no action is supported;
- reacts to previous failure/history.

## 25.5 Agent Loop Tests with Fakes

Use fake observer, planner, validator, executor, and UI-change source.

Test:

- normal open-app → tap → complete sequence;
- exactly one executor call per Planner iteration;
- new observation before every re-plan;
- invalid decision is never executed;
- terminal decision is never sent to executor;
- stale target triggers no tap;
- screen unchanged is passed to next request;
- maximum steps stop the loop;
- repeated action stops the loop;
- cancellation prevents later execution;
- planner exception becomes controlled failure;
- executor exception becomes controlled failure;
- waiting-for-user state pauses execution;
- task completion does not become end-session.

## 25.6 Android/Instrumentation Tests

Add instrumentation tests only where they provide value and are feasible with the current project.

Do not fake a production success claim from instrumentation alone. WhatsApp behavior must still be physically verified.

---

# 26. Build and Verification Commands

Discover the actual Gradle tasks first.

Typical commands, when supported, are:

```bash
./gradlew testDebugUnitTest
./gradlew assembleDebug
./gradlew lintDebug
./gradlew connectedDebugAndroidTest
```

On Windows, the user may run:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

Do not assume every listed task exists. Use the repository’s actual module and variant names.

If lint has pre-existing unrelated failures:

- distinguish them from failures caused by this change;
- do not hide them;
- do not rewrite unrelated code to silence them;
- report them precisely.

Successful compilation alone is not the Definition of Done.

---

# 27. Physical Android Test Procedure

Codex must provide the exact final APK path and a concise device checklist.

The human device test should verify:

1. Install the debug APK.
2. Open the application.
3. Enable the app’s accessibility service in Android Settings.
4. Confirm the app reports that the service is connected.
5. Ensure WhatsApp is installed and already authenticated.
6. Ensure a visible test conversation named `أحمد محمد` exists, or update only the test goal/contact consistently.
7. Return to the agent app.
8. Enter `افتح محادثة أحمد محمد`.
9. Start the agent.
10. Observe that WhatsApp opens if needed.
11. Inspect logs/UI preview and confirm the contact appears in Compact UI State with a real current target ID.
12. Confirm the Mock Planner returns `tap` with that same ID.
13. Confirm validation accepts the current target.
14. Confirm the phone opens the real conversation.
15. Confirm a fresh snapshot is captured.
16. Confirm completion occurs only after conversation-screen evidence.
17. Run the test again from WhatsApp Home and from the launcher.
18. Test Cancel while the agent is waiting or navigating.
19. Test a missing contact and verify safe failure/question rather than a random tap.
20. Test two similar contact names and verify no silent guessing.

Do not send a message during this acceptance test.

If the device exposes a different WhatsApp accessibility structure, capture the redacted Compact UI State and adapt the generic extraction/matching logic with the smallest tested change. Do not hard-code one device’s traversal index.

---

# 28. Acceptance Criteria

The task is complete only when all applicable criteria are met.

## Architecture

- `Planner` is replaceable.
- `MockPlanner` is isolated from Android execution.
- Planner gets compact state, not raw nodes.
- One decision contains exactly one action.
- Agent loop always observes again before the next decision.

## State and Targets

- Compact UI State comes from the real accessibility tree.
- Password values are redacted.
- Elements are capped and prioritized.
- Target IDs are generated by Android.
- Target IDs are snapshot-scoped.
- Registry and compact state are atomically paired.
- Stale targets are rejected.
- The Planner never invents IDs.

## Execution

- App launch uses a real Android launch intent.
- Tap uses the live current accessibility target.
- Type, scroll, and Back have real executor implementations or clearly reported platform limitations.
- No terminal or invalid decision reaches the executor.
- UI change is observed after every dispatched action.

## Loop

- Stable active goal is retained.
- `user_input` becomes null during automatic re-planning.
- Last result and bounded history are supplied.
- Step limit exists.
- repeated-action protection exists.
- cancellation works.
- no concurrent runs execute.
- completion is evidence-based.

## Testing

- relevant unit tests pass;
- debug build succeeds;
- existing relevant tests still pass;
- build/test commands are reported;
- physical-device-only checks are clearly separated from verified automated checks.

## Primary Scenario

On a physical phone:

```text
افتح محادثة أحمد محمد
→ real WhatsApp launch if needed
→ real UI observation
→ current real target_id
→ real validated tap
→ fresh observation
→ evidence-based task_completed
```

---

# 29. Anti-Hallucination Rules

These rules are mandatory:

1. Do not claim a class, file, dependency, service, test, or Gradle task exists until you inspect it.
2. Do not claim a build passed unless you ran it and saw a successful result.
3. Do not claim the phone scenario passed unless it was actually tested on an available physical device.
4. Do not invent target IDs.
5. Do not invent UI elements that are absent from Compact UI State.
6. Do not assume a dispatch means the screen changed.
7. Do not assume a tap means the correct conversation opened.
8. Do not mark completion without current-state evidence.
9. Do not create multi-step Planner decisions.
10. Do not guess between ambiguous contacts.
11. Do not silently repair invalid decisions by choosing another target.
12. Do not hide pre-existing test or lint failures.
13. Do not say an unimplemented action is supported.
14. Do not report simulated/fake observations as real Android observations.
15. Do not add a remote Planner, VLM, or STT redesign.
16. Do not hard-code a target ID or accessibility traversal index.
17. Do not log secrets or password text.
18. Do not continue executing after cancellation.
19. Do not modify unrelated code just to make reports cleaner.
20. If evidence is missing, state exactly what is unverified.

---

# 30. Final Report Format

At the end, return a concise but complete report with these sections:

## Outcome

State what now works.

## Repository Findings

State the discovered architecture relevant to the implementation.

## Files Changed

For each created or modified file:

- path;
- purpose;
- important behavior.

## Architecture Implemented

Explain how observation, Planner, validation, execution, and re-planning connect.

## Contract Compliance

Confirm:

- exact Planner request information;
- exact seven Planner decision fields;
- one-action rule;
- target and target-ID distinction;
- snapshot binding;
- Mock/Qwen replaceability.

## Tests and Builds Run

List each exact command and result.

Do not summarize an unrun command as passing.

## Automated Verification

List behaviors proven by tests.

## Physical Device Verification Required

List steps that still require the user’s Android phone.

## Known Limitations

Be explicit and technical.

## APK

Provide the exact debug APK path if assembly succeeded.

## Recommended Next Step

Recommend only the next smallest action after this task, normally real-device testing followed by connecting `RemoteQwenPlanner`.

---

# 31. Execution Directive

Now perform the work.

Start by inspecting the repository and applicable instructions. Build a file-mapped plan from what actually exists. Then implement the phases in order, testing and fixing as you go.

Do not stop after the inspection or plan.

Do not return only code snippets.

Do not implement the real Qwen Planner.

Do not declare success based only on compilation.

The target outcome is a real Android agent body with a deterministic Mock Planner:

```text
Real observation
→ one mocked decision
→ real validation
→ one real Android action
→ real new observation
→ re-plan
```

The future integration must be able to replace:

```kotlin
MockPlanner
```

with:

```kotlin
RemoteQwenPlanner
```

without rewriting:

- Compact UI State;
- Target Registry;
- Decision Validator;
- Android Executor;
- UI change observation;
- Agent Loop;
- action history;
- cancellation and loop protection.


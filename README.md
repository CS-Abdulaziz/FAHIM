[README.md](https://github.com/user-attachments/files/30887717/README.md)
# FAHIM — Arabic Voice Agent for Android

FAHIM (فَهيم) is a research prototype of a hands-free Android assistant driven by spoken
Arabic. You say what you want in plain Arabic; the agent reads the screen through the
Android accessibility APIs, asks a remote language model what to do next, performs the tap
or the keystroke itself, and speaks back to you.

The target user is someone who cannot comfortably drive a touchscreen — the agent operates
the phone's real UI rather than talking to app APIs, so it works with apps that expose no
integration surface of their own.

> **Status: proof of concept.** It drives whatever apps are installed on the phone, is signed
> with a debug key, and is not hardened for general use.

This is branch **`1.2`** — [CHANGES-1.2.md](CHANGES-1.2.md) summarises everything it changes
relative to `main`. New to the project? [EMULATOR.md](EMULATOR.md) Part 1 sets up a machine from
zero.

---

## What changed in this version

Where the reasoning lives, how you reach it, and what it is allowed to touch.

### 1. The planner now runs on a cloud-hosted server

Earlier builds assumed a planner reachable on your own machine or a tunnel you set up by
hand. This version ships the server as part of the repository — [`server/`](server/) — as a
FastAPI service you can deploy to a free GPU host (Kaggle or Colab notebooks are included)
and reach from anywhere.

The server is more than a proxy. It is a **validation shim**: the phone's `DecisionValidator`
rejects any malformed decision, and a rejection ends the task outright with no retry. A model
small enough to self-host breaks the schema often enough that talking to it directly would fail
constantly. So the shim re-checks every response against a mirror of the on-device rules and,
when one is wrong, tells the model exactly what was wrong and asks again — up to
`MAX_ATTEMPTS`, inside a wall-clock budget that stays under the phone's 30-second read timeout.
Only a decision that *would* pass on the phone is ever sent to the phone.

That makes self-hosted models practical here, which was the point — and it keeps holding as the
model changes, since the guarantee is about the contract, not about any one model.

### 2. The server address is editable inside the app

The planner URL is no longer a constant you have to rebuild the APK to change. Open the app,
go to **section 4 — AI Planner**, type an address, tap **Save**. The next request goes there.

- Validated before it is accepted — `http://` or `https://` with a real host, or the field
  goes red and Save stays disabled.
- Persisted in `SharedPreferences`, so it survives restarts.
- **Reset** returns to the address compiled into `DemoConfig.PLANNER_ENDPOINT`.
- Locked while a task is running, so a run cannot change planners mid-flight.

This matters in practice because free GPU hosts hand you a new tunnel URL on every session.
Re-pointing the app is now a ten-second job on the phone instead of a rebuild-and-reinstall.

Relevant code: `PlannerEndpoints.kt` (validation, pure Kotlin and unit-tested),
`PlannerEndpointStore.kt` (persistence), and `PlannerClient` taking an `endpointProvider`
lambda so the address is read fresh on every request rather than captured at construction.

### 3. Any installed app, not a fixed list

Earlier builds recognised four apps and refused everything else — both for `open_app` and for
acting on a screen once it was open. That restriction is gone. The agent works in whatever the
user has installed.

The phone now reads its own launcher: `InstalledAppCatalog` lists every app with a launcher
entry, sends the labels to the planner as `available_apps`, and `AppTargetResolver` matches the
name the planner replies with back to a package — exact label first, then looser tiers, with
Arabic aliases («المتصفح», «المنبه», «الكاميرا» …) translating spoken names into the English
labels launchers actually use. Matching folds hamza, ta-marbuta and alef maqsura, because the
name comes from speech recognition and the label from the app's own resources, and those two
spell Arabic differently.

`ForegroundApplicationPolicy` correspondingly allows node actions in any foreground app. What it
still refuses is a decision that does not describe the live screen — a snapshot planned in one
app executed in another, a changed window, or the controller's own UI, which the agent must
never drive.

This needs the `MAIN`/`LAUNCHER` `<queries>` declaration in the manifest: from Android 11 a
package outside `<queries>` is invisible, and `getLaunchIntentForPackage` returns null for an app
that is plainly installed.

---

## How it works

```
   ┌─────────────────────── Android device ───────────────────────┐
   │                                                              │
   │  speech ──► goal text                                        │
   │                │                                             │
   │                ▼                                             │
   │      ┌───────────────────┐    accessibility tree             │
   │      │     AgentLoop     │◄──── TreeDumperService            │
   │      │                   │      (compacted to ≤500 nodes)    │
   │      └─────────┬─────────┘                                   │
   │                │ PlannerRequest (goal + UI state + history)  │
   └────────────────┼─────────────────────────────────────────────┘
                    │  HTTPS · Planner API v0.2
                    ▼
   ┌──────────────── cloud host (Kaggle / Colab / VM) ────────────┐
   │   planner_server.py — FastAPI shim                           │
   │        │  validate → repair → re-ask                         │
   │        ▼                                                     │
   │   Ollama or LM Studio ──► Qwen3 30B-A3B                      │
   └──────────────────────────────────────────────────────────────┘
                    │  seven-key JSON decision
                    ▼
   ┌──────────────────────── back on device ──────────────────────┐
   │  DecisionValidator → SafetyPolicy → AndroidActionExecutor    │
   │  then Arabic TTS speaks the result                           │
   └──────────────────────────────────────────────────────────────┘
```

One planner call per step, up to 12 steps per goal. Every decision is exactly seven fields —
`reason`, `status`, `action`, `target`, `target_id`, `value`, `message` — and anything else is
rejected. **No task reasoning happens on the device**; the phone perceives, validates, and
acts, and that separation is deliberate.

### Safety

- **Installed apps only, and never itself.** `open_app` resolves against the device's own
  launcher catalogue, so a name that matches nothing installed is refused rather than guessed at.
  `ForegroundApplicationPolicy` refuses to act on the controller's own screens, and on any screen
  that does not match the snapshot the decision was planned from.
- **Confirmation on sensitive actions.** The planner can return `needs_confirmation`, which
  stops the loop; the phone speaks the question and listens, and the spoken answer starts a
  fresh run with that answer appended to the goal.
  Separately, `SafetyPolicy` flags taps and typing near terms like send / delete / pay / call
  (Arabic and English). **That second gate is currently fail-closed:** `SafetyApproval` is
  declared but never constructed, and `AgentLoop` calls the validator without one, so a
  flagged action is always rejected with `SENSITIVE_ACTION_REQUIRES_CONFIRMATION` rather than
  prompting. Granting approval from the UI is not implemented yet.
- **Stale-screen protection.** Every decision is bound to the screen fingerprint it was made
  from. If the screen moved underneath it, the action does not fire.
- **Loop protection.** Repeated identical failures and A-B-A-B oscillations end the run.
- **Emergency stop** and a hard 12-step ceiling.

---

## Repository layout

| Path | What it is |
| --- | --- |
| `app/` | The Android application (Kotlin, Jetpack Compose) |
| `app/src/main/java/.../agent/` | Agent runtime: `perception`, `contract`, `validation`, `execution`, `loop`, `safety` |
| `app/src/main/cpp/` | JNI bridge to whisper.cpp |
| `server/` | Cloud planner: FastAPI shim, notebook, tests, and its own README |
| `decision_engine_system_prompt_v3_3.md` | The planner's system prompt |
| `CODEX_MASTER_PROMPT_ANDROID_AGENT_RUNTIME_V1.md` | Full runtime specification |
| `VoiceAgent_Decision_Engine_Final/` | Decision-engine design material |
| `FAHIM-report-paper-draft.pdf` | Project report draft |

---

## Building the app

**Requirements**

- Android Studio (recent), JDK 17+ — its bundled JBR (21) is what the commands below use
- Android SDK 36, NDK `28.2.13676358`, CMake 3.22.1
- Git LFS
- A local [whisper.cpp](https://github.com/ggerganov/whisper.cpp) checkout
- An **arm64** device or emulator — `abiFilters` is `arm64-v8a` only

For the emulator specifically — starting and stopping it, reinstalling, re-granting the
accessibility service, and what to do when `adb` is not on your `PATH` — see [EMULATOR.md](EMULATOR.md).

**Clone**

```bash
git clone https://github.com/eclipse-eng/FAHIM.git
cd FAHIM
git lfs pull          # the 175 MB Whisper model is LFS; without this you get a 134-byte pointer
chmod +x gradlew      # the exec bit does not survive some checkouts
```

**Build and install**

The CMake build needs a path to whisper.cpp. The default baked into `app/build.gradle.kts`
is a Windows path, so pass your own:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :app:installDebug -PwhisperSourceDir=/path/to/whisper.cpp
```

> The native whisper module is compiled and the model is bundled, but the live speech path
> currently runs through Android's own `SpeechRecognizer` (`GoogleSpeechRecognizerController`).
> `WhisperEngine` is present and loadable but not yet wired into the main flow — on-device
> transcription is the next step, not a shipped feature. You still need the whisper.cpp
> checkout for the build to succeed.

**Grant permissions after install**

```bash
adb shell pm grant com.example.testandroidenv android.permission.RECORD_AUDIO

adb shell settings put secure enabled_accessibility_services \
  com.example.testandroidenv/com.example.testandroidenv.TreeDumperService
adb shell settings put secure accessibility_enabled 1
```

Two gotchas worth knowing:

- Force-stopping or killing the app **wipes `enabled_accessibility_services` back to null.**
  Re-run both commands after every reinstall or kill.
- The in-app status only refreshes in `onResume` — press HOME and relaunch rather than
  `am start`, or it will still claim the service is disabled.

---

## Running the planner

Full detail lives in [`server/README.md`](server/README.md). The short version:

**Locally**, against LM Studio or Ollama:

```bash
cd server
pip install -r requirements.txt

export MODEL_BASE_URL=http://127.0.0.1:1234/v1   # LM Studio; Ollama is :11434/v1
export MODEL_NAME=qwen/qwen3-30b-a3b
uvicorn planner_server:app --host 0.0.0.0 --port 8000
```

Then point the app at it — **emulator:** `http://10.0.2.2:8000/api/v1/predict`; **USB device:**
`adb reverse tcp:8000 tcp:8000` and use `http://localhost:8000/api/v1/predict`.

**In the cloud**, open `server/fahim_planner_colab_v2.ipynb` in Colab or Kaggle, enable the
GPU (and Internet, on Kaggle), and *Run all*. The last cell prints a public URL ending in
`/api/v1/predict` — paste that into the app's Planner field and tap Save. Leave the final
`tail -f` cell running; the tunnel dies when the runtime is reclaimed.

Verify any server before trusting it:

```bash
curl -s -X POST <your-url> -H 'Content-Type: application/json' \
  -d @server/sample_request.json | python3 -m json.tool
```

Seven keys and nothing else is a pass.

---

## Tests

```bash
./gradlew :app:testDebugUnitTest -PwhisperSourceDir=/path/to/whisper.cpp   # 27 JVM suites
./gradlew :app:connectedDebugAndroidTest -PwhisperSourceDir=/path/to/whisper.cpp
cd server && python test_planner_server.py    # no model or GPU needed
```

The server suite runs the shim against a scripted fake model and asserts that nothing it
emits could be rejected on device — including when the model hallucinates a `target_id`,
picks a forbidden app, or returns prose instead of JSON. Run it after any change to the
prompt or the validation rules.

> `connectedDebugAndroidTest` **uninstalls the app when it finishes.** Reinstall and re-grant
> permissions afterwards.

---

## Known limitations

- Any installed app is in scope, but only apps with a launcher entry are visible to
  `InstalledAppCatalog` — a settings panel or a service with no launcher icon cannot be opened by
  name, only reached by navigating to it.
- `available_apps` is capped at 120 labels to bound the prompt. Resolution still searches the
  whole catalogue, so an app past the cap opens if the planner happens to name it, but it is not
  advertised.
- Free GPU hosts give you a new URL every session — which is precisely why the address is
  now editable in-app.
- The planner is `qwen3:30b-a3b-instruct-2507-q4_K_M`, which needs Kaggle's **T4 ×2** (19 GB
  of the 32 GB). Colab's single 16 GB T4 cannot hold it — use `qwen3:14b` or `qwen3:8b` there,
  with `SUPPRESS_THINKING=1` since those are hybrid builds that reason unless told not to.
  `eval_runner_v3.py` scores any of them against `planner_fixtures_v3.jsonl`.
- Release builds are signed with the debug key. Not for distribution.
- `adb shell uiautomator dump` returns a **stale tree** for this app, because its own
  accessibility service holds the active window. Verify screen state with `screencap` and
  drive the UI through instrumented Compose tests, not `adb input`.

---

## License

No license has been declared yet. Add one before publishing if you intend others to reuse
this code.

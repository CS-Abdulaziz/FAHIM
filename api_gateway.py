from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
import requests
import json
from typing import Optional, List, Dict, Any

app = FastAPI(title="VoiceAgent Decision API - V3.3")

# 1. تحديث شكل البيانات المستقبلة من الأندرويد (7 خانات حسب العقد v0.2)
class AgentRequest(BaseModel):
    user_input: Optional[str] = None
    active_goal: str
    current_app: Dict[str, Any]
    ui_state: Dict[str, Any]
    conversation_context: List[Any] = []
    last_action_result: Optional[Dict[str, Any]] = None
    action_history: List[Any] = []

LLAMA_SERVER_URL = "http://127.0.0.1:8080/v1/chat/completions"

# قراءة الموجه الجديد V3.3 مباشرة من الملف عشان ما تضطر تنسخه داخل الكود
try:
    with open("decision_engine_system_prompt_v3_3.md", "r", encoding="utf-8") as f:
        SYSTEM_PROMPT = f.read()
except FileNotFoundError:
    SYSTEM_PROMPT = "أنت المخطط في مساعد أندرويد عربي للمكفوفين..." # إحتياطي في حال عدم وجود الملف

@app.post("/api/v1/predict")
def get_next_action(request: AgentRequest):
    try:
        # تحويل بيانات الأندرويد إلى صيغة JSON مقروءة للمودل
        user_content = json.dumps(request.model_dump(), ensure_ascii=False, indent=2)

        # 2. هيكل الـ JSON المحدث (7 خانات و 6 حالات و 9 إجراءات حسب عقد V3.3)
        payload = {
            "messages": [
                {"role": "system", "content": SYSTEM_PROMPT},
                {"role": "user", "content": user_content}
            ],
            "temperature": 0.0,
            "seed": 42,
            "max_tokens": 200, # تحديد حد أقصى للرد لسرعة الاستجابة
            "response_format": {
                "type": "json_schema",
                "json_schema": {
                    "name": "planner_decision",
                    "strict": True,
                    "schema": {
                        "type": "object",
                        "properties": {
                            "reason": {"type": "string", "maxLength": 140},
                            "status": {
                                "type": "string",
                                "enum": ["continue", "task_completed", "needs_user_input", "needs_confirmation", "failed", "end_session"]
                            },
                            "action": {
                                "type": "string",
                                "enum": ["open_app", "tap", "type", "scroll", "back", "read_aloud", "ask_user", "confirm_with_user", "none"]
                            },
                            "target": {"type": ["string", "null"]},
                            "target_id": {"type": ["string", "null"]},
                            "value": {"type": ["string", "null"]},
                            "message": {"type": ["string", "null"]}
                        },
                        "required": ["reason", "status", "action", "target", "target_id", "value", "message"],
                        "additionalProperties": False
                    }
                }
            }
        }

        # إرسال الطلب لسيرفر llama-cpp المحلي
        response = requests.post(LLAMA_SERVER_URL, json=payload)
        response.raise_for_status()
        
        # استخراج النتيجة وإرجاعها لتطبيق الأندرويد
        model_output = response.json()["choices"][0]["message"]["content"]
        return json.loads(model_output)

    except Exception as e:
        raise HTTPException(status_code=500, detail=str(e))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8000)
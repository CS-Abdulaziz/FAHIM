package com.example.testandroidenv

import com.example.testandroidenv.agent.contract.PlannerAction
import com.example.testandroidenv.agent.contract.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PlannerJsonTest {
    @Test
    fun parsesProvenV02ResponsesAndExplicitNulls() {
        val open = PlannerResponseJson.parse(
            """
            {
              "reason":"واتساب غير مفتوح.",
              "status":"continue",
              "action":"open_app",
              "target":"WhatsApp",
              "target_id":null,
              "value":null,
              "message":null
            }
            """.trimIndent()
        )
        assertEquals(TaskStatus.CONTINUE, open.status)
        assertEquals(PlannerAction.OPEN_APP, open.action)
        assertNull(open.targetId)
        assertNull(open.message)

        val complete = PlannerResponseJson.parse(
            """
            {
              "reason":"تم فتح المحادثة.",
              "status":"task_completed",
              "action":"none",
              "target":null,
              "target_id":null,
              "value":null,
              "message":"تم. تبغى شيء ثاني؟"
            }
            """.trimIndent()
        )
        assertEquals(TaskStatus.TASK_COMPLETED, complete.status)
        assertEquals(PlannerAction.NONE, complete.action)
    }

    @Test
    fun parsesAiConversationStatusesWithoutLocalIntentClassification() {
        val needsInput = PlannerResponseJson.parse(
            response(status = "needs_user_input", action = "ask_user")
                .replace(
                    """"message":null""",
                    """"message":"أكيد، وش تبغاني أسوي؟""""
                )
        )
        assertEquals(TaskStatus.NEEDS_USER_INPUT, needsInput.status)
        assertEquals(PlannerAction.ASK_USER, needsInput.action)

        val end = PlannerResponseJson.parse(
            response(status = "end_session", action = "none")
                .replace(
                    """"message":null""",
                    """"message":"حياك الله.""""
                )
        )
        assertEquals(TaskStatus.END_SESSION, end.status)
        assertEquals(PlannerAction.NONE, end.action)
    }

    @Test
    fun rejectsUnknownStatusUnknownActionMalformedAndWrongShape() {
        assertThrows(PlannerException::class.java) {
            PlannerResponseJson.parse(response(status = "mystery"))
        }
        assertThrows(PlannerException::class.java) {
            PlannerResponseJson.parse(response(action = "type_text"))
        }
        assertThrows(PlannerException::class.java) {
            PlannerResponseJson.parse("not-json")
        }
        assertThrows(PlannerException::class.java) {
            PlannerResponseJson.parse("""{"status":"continue"}""")
        }
        assertThrows(PlannerException::class.java) {
            PlannerResponseJson.parse(
                response().dropLast(1) + ""","unexpected":true}"""
            )
        }
    }

    private fun response(
        status: String = "continue",
        action: String = "tap"
    ): String = """
        {
          "reason":"وجدت الهدف.",
          "status":"$status",
          "action":"$action",
          "target":null,
          "target_id":"s1_e001_a1b2c3",
          "value":null,
          "message":null
        }
    """.trimIndent()
}

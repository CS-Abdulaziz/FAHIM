package com.example.testandroidenv

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AiDrivenArchitectureTest {
    private val productionSource: String by lazy {
        mainSourceDirectory()
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy(File::getPath)
            .joinToString("\n") { it.readText(Charsets.UTF_8) }
    }

    @Test
    fun productionContainsNoLocalIntentParserOrRuleBasedPlanner() {
        val prohibitedSymbols = listOf(
            "class MockPlanner",
            "object MockPlanner",
            "extractContactName",
            "conversationIsOpen",
            "isSearchLabel",
            "FollowUpIntent",
            "END_PHRASES",
            "YES_PHRASES",
            "runPredefinedOpenConversationFlow",
            "userInput.contains(",
            "userInput.startsWith(",
            "activeGoal.contains(",
            "activeGoal.startsWith("
        )

        prohibitedSymbols.forEach { symbol ->
            assertFalse("Production contains prohibited local reasoning: $symbol",
                productionSource.contains(symbol))
        }
    }

    @Test
    fun productionContainsNoHardcodedDemonstrationContacts() {
        listOf("أحمد", "خالد", "محمد", "سارة", "عبدالله").forEach { contact ->
            assertFalse(
                "Production must not contain a hardcoded contact: $contact",
                productionSource.contains(contact)
            )
        }
    }

    @Test
    fun mainRuntimeUsesOnlyTheRemotePlannerClient() {
        val mainActivity = mainSourceDirectory()
            .resolve("com/example/testandroidenv/MainActivity.kt")
            .readText(Charsets.UTF_8)

        assertTrue(mainActivity.contains("planner = plannerClient"))
        assertFalse(mainActivity.contains("PlannerMode"))
        assertFalse(mainActivity.contains("MockPlanner"))
    }

    private fun mainSourceDirectory(): File {
        val working = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            working.resolve("src/main/java"),
            working.resolve("app/src/main/java")
        ).firstOrNull(File::isDirectory)
            ?: error("Could not locate the production Kotlin source directory from $working")
    }
}

package dev.baryo.dopaminekt

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertFailsWith

/** The runner must fail on a case that would check nothing. */
class FixtureGuardsTest {
    private fun inspect(text: String) = FixtureCase.inspect(Json.parseToJsonElement(text) as JsonObject)

    private fun rejects(text: String) {
        assertFailsWith<AssertionError> { inspect(text) }
    }

    private val head = """"name": "x", "clock": "2026-08-07T09:00:00""""

    @Test
    fun aGoodCaseIsAccepted() {
        inspect("""{$head, "steps": [{"op": "addXP", "amount": 1, "expect": {"player": {"xp": 1}}}]}""")
    }

    @Test
    fun expectMustBeAnObject() {
        rejects("""{$head, "steps": [{"op": "addXP", "amount": 1, "expect": null}]}""")
        rejects("""{$head, "steps": [{"op": "addXP", "amount": 1}]}""")
        rejects("""{$head, "steps": [{"op": "setClock", "at": "2026-08-08T09:00:00", "expect": null}]}""")
    }

    @Test
    fun afterInitAndStepsMustHaveTheRightType() {
        rejects("""{$head, "afterInit": null}""")
        rejects("""{$head, "steps": {}, "afterInit": {"player": {"xp": 0}}}""")
        rejects("""{$head}""")
    }

    @Test
    fun savedRawMustBeAString() {
        rejects("""{$head, "savedRaw": 5, "afterInit": {"player": {"xp": 0}}}""")
    }

    @Test
    fun achievementFieldsMustHaveTheRightType() {
        rejects("""{$head, "config": {"achievements": {"a": {"name": 1, "when": {"always": true}}}}, "afterInit": {"player": {"xp": 0}}}""")
        rejects("""{$head, "config": {"achievements": {"a": {"xp": "5", "when": {"always": true}}}}, "afterInit": {"player": {"xp": 0}}}""")
    }

    @Test
    fun unknownKeysFail() {
        rejects("""{$head, "extra": 1, "afterInit": {"player": {"xp": 0}}}""")
        rejects("""{$head, "steps": [{"op": "nope", "expect": {"player": {"xp": 0}}}]}""")
        rejects("""{$head, "steps": [{"op": "reset", "bogus": 1, "expect": {"player": {"xp": 0}}}]}""")
    }
}

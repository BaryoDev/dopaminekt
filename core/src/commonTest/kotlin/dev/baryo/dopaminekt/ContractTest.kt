package dev.baryo.dopaminekt

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Everything here is built from the public contracts alone, to show a module outside core can
 * implement all of them.
 */
class ContractTest {
    private val calls = mutableListOf<String>()

    private val haptics = object : Haptics {
        override fun tap() { calls += "tap" }
        override fun double() { calls += "double" }
        override fun setEnabled(enabled: Boolean) { calls += "enabled:$enabled" }
    }

    private val sound = object : SoundPlayer {
        override fun play(key: String) { calls += "play:$key" }
        override fun destroy() { calls += "sound.destroy" }
    }

    private val particles = object : ParticleRenderer {
        override fun burst(kind: BurstKind, at: Point?, options: BurstOptions?) {
            calls += "burst:${kind.wireName}:$at:$options"
        }
        override fun destroy() { calls += "particles.destroy" }
    }

    private val ui = object : RewardUi {
        override fun hud(state: HudState) { calls += "hud:${state.level}" }
        override fun popup(popup: Popup) { calls += "popup:${popup.kind.wireName}" }
        override fun text(text: FloatingText) { calls += "text:${text.text}" }
        override fun destroy() { calls += "ui.destroy" }
    }

    private val parts: Parts = object : Parts {
        override val ui: RewardUi = this@ContractTest.ui
        override val particles: ParticleRenderer = this@ContractTest.particles
        override val sound: SoundPlayer = this@ContractTest.sound
        override val haptics: Haptics? = null
    }

    private class FakeContext(
        override val progress: Progress,
        override val parts: Parts,
        val calls: MutableList<String>,
    ) : PluginContext {
        override val storage: StoragePort = MemoryStorage()
        override val clock: Clock = SystemClock()
        override val env: Env = object : Env {
            override val reducedMotion: Boolean = false
        }
        override val log: Logger = object : Logger {
            override fun debug(message: String, error: Throwable?) { calls += "debug:$message" }
            override fun info(message: String, error: Throwable?) { calls += "info:$message" }
            override fun warn(message: String, error: Throwable?) { calls += "warn:$message" }
            override fun error(message: String, error: Throwable?) { calls += "error:$message" }
        }
        override val events: PluginEvents = object : PluginEvents {
            override fun on(wireName: String, listener: (ProgressEvent) -> Unit): () -> Unit =
                progress.on(wireName, listener)

            override fun emit(wireName: String, data: JsonElement?) {
                progress.emit(ProgressEvent.Custom(wireName, data))
            }
        }
        override val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        private val handles = mutableMapOf<String, Any?>()
        var current: String = "spin"

        override fun plugin(name: String): Any? = handles[name]
        override fun expose(handle: Any?) { handles[current] = handle }
    }

    private class SpinPlugin : DopaminePlugin {
        override val name = "spin"
        override val version = "1.0.0"
        override val api = HOST_API
        override val after = listOf("base")
        override val requires = listOf(PartName.Sound, PartName.Ui)

        override suspend fun install(context: PluginContext): Disposer? {
            val sound = context.parts.sound ?: return null
            val off = context.events.on("spin:done") { sound.play("ding") }
            context.parts.haptics?.tap()
            context.parts.particles?.burst(BurstKind.Stars)
            context.parts.ui?.hud(HudState(xp = 0.0, level = 1, streak = 0))
            context.log.info("installed")
            context.expose("handle")
            context.events.emit("spin:done", JsonPrimitive(1))
            return Disposer { off() }
        }
    }

    @Test
    fun aPluginBuiltOnThePublicContractsInstallsAndDisposes() = runTest {
        val progress = Progress(MemoryStorage())
        val context = FakeContext(progress, parts, calls)
        val plugin = SpinPlugin()

        assertEquals(HOST_API, plugin.api)
        assertEquals(true, isValidPluginName(plugin.name))
        assertEquals(listOf("sound", "ui"), plugin.requires.map { it.wireName })
        assertNull(context.plugin("spin"))

        val disposer = assertNotNull(plugin.install(context))
        assertEquals("handle", context.plugin("spin"))
        assertEquals(
            listOf("burst:stars:null:null", "hud:1", "info:installed", "play:ding"),
            calls,
        )

        calls.clear()
        disposer.dispose()
        context.events.emit("spin:done")
        assertEquals(emptyList(), calls)
        context.scope.cancel()
    }

    @Test
    fun defaultsAreEmptyForAPluginThatSetsNone() {
        val bare = object : DopaminePlugin {
            override val name = "bare"
            override val version = "0"
            override val api = HOST_API
            override suspend fun install(context: PluginContext): Disposer? = null
        }
        assertEquals(emptyList(), bare.after)
        assertEquals(emptyList(), bare.requires)
    }

    @Test
    fun partsAreUsableThroughTheirInterfaces() {
        parts.haptics?.tap()
        parts.sound?.play("coin")
        parts.particles?.burst(BurstKind.Coins, Point(1.0, 2.0), BurstOptions(count = 3))
        parts.ui?.popup(Popup(PopupKind.Achievement, title = "Hi", tier = PopupTier.Rare))
        parts.ui?.text(FloatingText("+5"))
        assertEquals(
            listOf("play:coin", "burst:coins:Point(x=1.0, y=2.0):BurstOptions(count=3, color=null)", "popup:achievement", "text:+5"),
            calls,
        )
    }
}

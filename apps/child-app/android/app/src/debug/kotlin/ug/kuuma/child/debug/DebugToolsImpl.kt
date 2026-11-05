package ug.kuuma.child.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ug.kuuma.child.DebugHooks
import ug.kuuma.child.KuumaRuntime
import ug.kuuma.contracts.ai.HarmClassifier
import ug.kuuma.core.crypto.OpaqueRefs
import ug.kuuma.core.log.LogField
import ug.kuuma.core.log.SafeLog
import ug.kuuma.core.module.KuumaModule
import ug.kuuma.core.module.ModuleRegistry
import ug.kuuma.fakes.BaseFake
import ug.kuuma.fakes.FakeSet
import ug.kuuma.fakes.ScenarioLibrary
import ug.kuuma.sentinel.ingest.WatchedApps
import java.util.concurrent.Executors

/** Debug builds only: registers the fakes and plays scenarios through them. */
class DebugToolsImpl : DebugHooks {
    private lateinit var fakes: FakeSet
    private val player = Executors.newSingleThreadExecutor()

    override fun install(context: Context, registry: ModuleRegistry, classifier: HarmClassifier, refs: OpaqueRefs) {
        fakes = FakeSet(classifier, refs)
        fakes.all.forEach(registry::register)
        // The real vault replaces Casefile's fake, so Sentinel's flags are not handled twice.
        fakes.casefile.enabled = false
        // Debug only: `adb shell cmd notification post ...` notifications come from the shell
        // package; treat them as SMS so the real notification listener can be tested on an emulator.
        WatchedApps.addSmsPackage("com.android.shell")
    }

    override fun scenarios(): List<String> = ScenarioLibrary.names()

    override fun play(name: String, delayMillis: Long) {
        val scenario = ScenarioLibrary.load(name)
        player.execute {
            SafeLog.i("debug.scenario.start", LogField.num("messages", scenario.messages.size))
            fakes.player.play(scenario, delayMillis)
            // The nightly emulator job greps logcat for this line (events > 0 means the chain ran).
            SafeLog.i(
                "debug.scenario.done",
                LogField.num("messages", scenario.messages.size),
                LogField.num("events", KuumaRuntime.recent.size),
            )
        }
    }

    override fun setFakeEnabled(namespace: String, enabled: Boolean) {
        fakes.all.first { it.namespace.wire == namespace }.enabled = enabled
    }

    override fun fakeEnabled(module: KuumaModule): Boolean = (module as? BaseFake)?.enabled ?: true
}

/** `adb shell am broadcast -a ug.kuuma.debug.PLAY_SCENARIO -n ug.kuuma.child/.debug.ScenarioReceiver --es scenario <name>` */
class ScenarioReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra("scenario") ?: return
        val delay = intent.getIntExtra("speedMs", 400).toLong()
        KuumaRuntime.debug?.play(name, delay)
        resultData = "playing"
    }
}

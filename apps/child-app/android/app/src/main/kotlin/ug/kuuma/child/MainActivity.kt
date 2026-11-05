package ug.kuuma.child

import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel

/** FlutterFragmentActivity (not FlutterActivity) so kuuma_ui's fingerprint unlock works. */
class MainActivity : FlutterFragmentActivity() {
    private val main = Handler(Looper.getMainLooper())

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        val messenger = flutterEngine.dartExecutor.binaryMessenger

        MethodChannel(messenger, "ug.kuuma/registry").setMethodCallHandler { call, result ->
            val debug = KuumaRuntime.debug
            when (call.method) {
                "modules" -> result.success(
                    KuumaRuntime.registry.modules.map { m ->
                        mapOf(
                            "namespace" to m.namespace.wire,
                            "displayName" to m.displayName,
                            "isFake" to m.isFake,
                            "enabled" to (debug?.fakeEnabled(m) ?: true),
                            "screens" to m.screens.map { mapOf("id" to it.id, "slot" to it.slot, "titleKey" to it.titleKey) },
                            "permissions" to m.permissions.map { mapOf("permission" to it.permission, "reasonKey" to it.reasonKey, "kind" to it.kind.name) },
                        )
                    },
                )
                "scenarios" -> result.success(debug?.scenarios() ?: emptyList<String>())
                "playScenario" -> {
                    debug?.play(call.argument<String>("name")!!, 400)
                    result.success(null)
                }
                "setFakeEnabled" -> {
                    debug?.setFakeEnabled(call.argument<String>("namespace")!!, call.argument<Boolean>("enabled")!!)
                    result.success(null)
                }
                else -> result.notImplemented()
            }
        }

        if (BuildConfig.DEBUG) {
            EventChannel(messenger, "ug.kuuma/bus").setStreamHandler(
                object : EventChannel.StreamHandler {
                    private var stop: (() -> Unit)? = null

                    override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                        stop = KuumaRuntime.listen { e -> main.post { events.success(e) } }
                    }

                    override fun onCancel(arguments: Any?) {
                        stop?.invoke()
                        stop = null
                    }
                },
            )
        }
    }
}

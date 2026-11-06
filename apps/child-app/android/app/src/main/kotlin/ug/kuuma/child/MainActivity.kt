package ug.kuuma.child

import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import io.flutter.embedding.android.FlutterFragmentActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import ug.kuuma.companion.CompanionBridge
import ug.kuuma.companion.CompanionModule

/** FlutterFragmentActivity (not FlutterActivity) so kuuma_ui's fingerprint unlock works. */
class MainActivity : FlutterFragmentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private val moduleCalls = java.util.concurrent.Executors.newFixedThreadPool(2)

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

        // Each module's Flutter screens talk to its own Kotlin half (KuumaModule.onCall), and only to it.
        for (module in KuumaRuntime.registry.modules.filterNot { it.isFake }) {
            MethodChannel(messenger, "ug.kuuma/module/${module.namespace.wire}").setMethodCallHandler { call, result ->
                @Suppress("UNCHECKED_CAST")
                val args = (call.arguments as? Map<String, Any?>).orEmpty()
                moduleCalls.execute {
                    try {
                        // Unit (a call with no answer) goes back as null: the codec cannot encode it.
                        val value = module.onCall(call.method, args).takeUnless { it is Unit }
                        main.post { result.success(value) }
                    } catch (e: UnsupportedOperationException) {
                        main.post { result.notImplemented() }
                    } catch (e: Exception) {
                        // The code only: messages could carry content.
                        main.post { result.error(e.javaClass.simpleName, null, null) }
                    }
                }
            }
        }

        connectCompanion(messenger)

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

    /**
     * Companion's screens (Help, advice, Talk to someone) use their own bridge: advice plans
     * stream in, crisis content and helplines come back, and the dialler opens with a helpline
     * number filled in (the child still presses call).
     */
    private fun connectCompanion(messenger: io.flutter.plugin.common.BinaryMessenger) {
        val companion = KuumaRuntime.registry.modules.filterIsInstance<CompanionModule>().firstOrNull() ?: return
        val bridge = CompanionBridge(companion, dialer = { number ->
            main.post { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number"))) }
        })
        MethodChannel(messenger, CompanionBridge.METHOD_CHANNEL).setMethodCallHandler { call, result ->
            val args = call.arguments as? Map<*, *> ?: emptyMap<Any, Any>()
            moduleCalls.execute {
                try {
                    val value = bridge.handle(call.method, args).takeUnless { it is Unit }
                    main.post { result.success(value) }
                } catch (e: CompanionBridge.UnknownMethod) {
                    main.post { result.notImplemented() }
                } catch (e: IllegalArgumentException) {
                    main.post { result.error("bad_args", null, null) }
                } catch (e: Exception) {
                    main.post { result.error(e.javaClass.simpleName, null, null) }
                }
            }
        }
        EventChannel(messenger, CompanionBridge.EVENT_CHANNEL).setStreamHandler(
            object : EventChannel.StreamHandler {
                private var stop: (() -> Unit)? = null

                override fun onListen(arguments: Any?, events: EventChannel.EventSink) {
                    stop = bridge.listen { m -> main.post { events.success(m) } }
                }

                override fun onCancel(arguments: Any?) {
                    stop?.invoke()
                    stop = null
                }
            },
        )
    }
}

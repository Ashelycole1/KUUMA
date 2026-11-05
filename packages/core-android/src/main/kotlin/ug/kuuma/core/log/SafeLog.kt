package ug.kuuma.core.log

/**
 * Content-free logging (rule 7: never log message content).
 *
 *     SafeLog.i("sentinel.flag.raised", LogField.id("flag_id", flagId), LogField.enum("severity", Severity.HIGH))
 *
 * The event name must be a constant string; the `KuumaSafeLog` lint check (shipped with
 * this library) fails the build otherwise. Field values are typed: IDs (validated),
 * enums, numbers, booleans and durations. There is no way to pass free text.
 */
object SafeLog {
    private val EVENT = Regex("^[a-z0-9][a-z0-9_.:-]{0,79}$")
    private val ID = Regex(
        "^([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}|or1_[A-Za-z0-9_-]{43}|[0-9a-f]{64})$"
    )

    @Volatile var sink: LogSink = AndroidLogSink

    fun d(event: String, vararg fields: LogField) = emit(LogLevel.DEBUG, event, fields)
    fun i(event: String, vararg fields: LogField) = emit(LogLevel.INFO, event, fields)
    fun w(event: String, vararg fields: LogField) = emit(LogLevel.WARN, event, fields)
    fun e(event: String, vararg fields: LogField) = emit(LogLevel.ERROR, event, fields)

    private fun emit(level: LogLevel, event: String, fields: Array<out LogField>) {
        require(EVENT.matches(event)) { "log event names are lowercase tokens like 'sentinel.flag.raised'" }
        val line = buildString {
            append(event)
            for (f in fields) append(' ').append(f.name).append('=').append(f.render())
        }
        sink.write(level, line)
    }

    internal fun checkId(value: String): String {
        require(ID.matches(value)) { "LogField.id accepts only UUIDs, OpaqueRefs and hashes" }
        return value
    }
}

enum class LogLevel { DEBUG, INFO, WARN, ERROR }

fun interface LogSink {
    fun write(level: LogLevel, line: String)
}

object AndroidLogSink : LogSink {
    override fun write(level: LogLevel, line: String) {
        when (level) {
            LogLevel.DEBUG -> android.util.Log.d("Kuuma", line)
            LogLevel.INFO -> android.util.Log.i("Kuuma", line)
            LogLevel.WARN -> android.util.Log.w("Kuuma", line)
            LogLevel.ERROR -> android.util.Log.e("Kuuma", line)
        }
    }
}

/** A typed log value. Construct with the factory functions; there is no free-text field. */
class LogField private constructor(val name: String, private val value: String) {
    fun render() = value

    companion object {
        private val NAME = Regex("^[a-z][a-z0-9_]{0,39}$")

        private fun make(name: String, value: String): LogField {
            require(NAME.matches(name)) { "log field names are snake_case" }
            return LogField(name, value)
        }

        fun id(name: String, value: String) = make(name, SafeLog.checkId(value))
        fun enum(name: String, value: Enum<*>) = make(name, value.name.lowercase())
        fun num(name: String, value: Number) = make(name, value.toString())
        fun bool(name: String, value: Boolean) = make(name, value.toString())
        fun ms(name: String, millis: Long) = make(name, "${millis}ms")
    }
}

package ug.kuuma.lint

import com.android.tools.lint.client.api.IssueRegistry
import com.android.tools.lint.client.api.Vendor
import com.android.tools.lint.detector.api.CURRENT_API
import com.android.tools.lint.detector.api.Category
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.lint.detector.api.Detector
import com.android.tools.lint.detector.api.Implementation
import com.android.tools.lint.detector.api.Issue
import com.android.tools.lint.detector.api.JavaContext
import com.android.tools.lint.detector.api.Scope
import com.android.tools.lint.detector.api.Severity
import com.android.tools.lint.detector.api.SourceCodeScanner
import com.intellij.psi.PsiMethod
import org.jetbrains.uast.UCallExpression

class KuumaIssueRegistry : IssueRegistry() {
    override val issues = listOf(SafeLogDetector.NON_CONSTANT, SafeLogDetector.RAW_LOG)
    override val api = CURRENT_API
    override val vendor = Vendor(vendorName = "Kuuma Core", identifier = "ug.kuuma.core")
}

/** Rule 7: never log message content. */
class SafeLogDetector :
    Detector(),
    SourceCodeScanner {
    override fun getApplicableMethodNames() =
        listOf("d", "i", "w", "e", "v", "wtf", "println", "print", "id", "enum", "num", "bool", "ms")

    override fun visitMethodCall(context: JavaContext, node: UCallExpression, method: PsiMethod) {
        val owner = method.containingClass?.qualifiedName ?: return
        when {
            owner == "ug.kuuma.core.log.SafeLog" ||
                owner == "ug.kuuma.core.log.LogField.Companion" ||
                owner == "ug.kuuma.core.log.LogField" -> {
                // The event name (SafeLog) or field name (LogField) must be a compile-time constant.
                val first = node.valueArguments.firstOrNull() ?: return
                if (ConstantEvaluator.evaluate(context, first) !is String) {
                    context.report(
                        NON_CONSTANT,
                        node,
                        context.getLocation(first),
                        "SafeLog event and field names must be constant strings, so message content can never reach a log"
                    )
                }
            }
            owner in RAW_LOG_OWNERS -> {
                if (context.file.path.replace(
                        '\\',
                        '/'
                    ).contains("/packages/core-android/src/main/kotlin/ug/kuuma/core/log/")
                ) {
                    return
                }
                context.report(
                    RAW_LOG,
                    node,
                    context.getLocation(node),
                    "Use SafeLog (ug.kuuma.core.log) instead of raw logging"
                )
            }
        }
    }

    companion object {
        private val RAW_LOG_OWNERS = setOf("android.util.Log", "kotlin.io.ConsoleKt", "java.io.PrintStream")
        private val IMPL = Implementation(SafeLogDetector::class.java, Scope.JAVA_FILE_SCOPE)

        val NON_CONSTANT = Issue.create(
            id = "KuumaSafeLogNonConstant",
            briefDescription = "Non-constant string passed to SafeLog",
            explanation = "Kuuma logs may hold only IDs, enums, numbers and timings. Event and field names must be " +
                "string constants; values must use the typed LogField factories.",
            category = Category.SECURITY,
            priority = 9,
            severity = Severity.FATAL,
            implementation = IMPL
        )

        val RAW_LOG = Issue.create(
            id = "KuumaRawLog",
            briefDescription = "Raw logging bypasses the content-free rule",
            explanation = "android.util.Log, println and System.out can leak message content. Use SafeLog.",
            category = Category.SECURITY,
            priority = 9,
            severity = Severity.FATAL,
            implementation = IMPL
        )
    }
}

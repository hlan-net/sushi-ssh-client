package net.hlan.sushi

object ShellUtils {
    fun shellQuote(value: String): String {
        if (value.isEmpty()) {
            return "''"
        }
        return "'${value.replace("'", "'\\\"'\\\"'")}'"
    }
}

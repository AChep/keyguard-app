package com.artemchep.keyguard.ipctestclient.ipc

/**
 * A bounded, process-wide history of exchanges, filled by the runners.
 *
 * The instrumentation assertions append its tail to failure messages, so a red
 * test says what the provider actually answered rather than only which
 * assertion tripped. The driver shows each exchange on its own, from the report.
 */
object IpcExchangeLog {
    private const val MAX_ENTRIES = 64

    data class Entry(
        val title: String,
        val detail: String,
    )

    private val entries = ArrayDeque<Entry>()

    @Synchronized
    fun record(title: String, exchange: IpcExchange) {
        record(Entry(title, exchange.describe()))
    }

    @Synchronized
    fun record(entry: Entry) {
        entries.addLast(entry)
        while (entries.size > MAX_ENTRIES) {
            entries.removeFirst()
        }
    }

    @Synchronized
    fun snapshot(): List<Entry> = entries.toList()

    @Synchronized
    fun tail(count: Int): String = entries
        .takeLast(count)
        .joinToString("\n\n") { "--- ${it.title} ---\n${it.detail}" }

    @Synchronized
    fun clear() = entries.clear()
}

/**
 * Appends this exchange to the shared log and returns it, so every runner call
 * lands in the trace a failed assertion prints.
 */
fun IpcExchange.recorded(): IpcExchange = also {
    IpcExchangeLog.record(IpcExchangeLog.Entry(legs.first().label, describe()))
}

/** A human-readable trace of every leg, including the approvals in between. */
fun IpcExchange.describe(): String = legs.joinToString("\n") { leg ->
    buildString {
        appendLine("[${leg.label}] ${leg.durationMs} ms")
        leg.request?.let { appendLine("request: ${it.dumpExtras()}") }
        leg.approval?.let {
            appendLine(
                "approval: resultCode=${it.resultCode} approved=${it.approved} " +
                    "token=${it.hasAuthorizationToken}",
            )
            it.data?.let { data -> appendLine("retry: ${data.dumpExtras()}") }
        }
        leg.result?.let { appendLine("result: ${it.dumpExtras()}") }
        leg.output?.let { appendLine("output: byte[${it.size}]") }
        leg.error?.let { appendLine("error: $it") }
    }.trimEnd()
}

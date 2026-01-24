import org.gradle.api.Project
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.TimeZone

data class VersionInfo(
    val marketingVersion: String,
    val logicalVersion: Int,
    val buildDate: String,
    val buildRef: String,
)

fun Project.createVersionInfo(
    marketingVersion: String,
    logicalVersion: Int, // max 9999
): VersionInfo {
    val buildRef = project.properties["versionRef"]
        ?.let { it as? String }
        .orEmpty()
    val dateFormat = SimpleDateFormat("yyyyMMdd")
    val calendar = Calendar.getInstance().apply {
        timeZone = TimeZone.getTimeZone("UTC")
        time = project.properties["versionDate"]
            ?.let { it as? String }
            ?.let { date ->
                dateFormat.timeZone = TimeZone.getTimeZone("UTC")
                dateFormat.parse(date)
            }
            ?: Date() // fallback to 'now'
    }
    // Max date:
    // (999 * 500 + 356) * 10000 + 9999
    val codeVersion = kotlin.run {
        val suppliedVersionCode = project.properties["versionCode"]
            ?.let { it as? String }
            ?.toIntOrNull()
        if (suppliedVersionCode != null) {
            return@run suppliedVersionCode
        }

        ((calendar.get(Calendar.YEAR) % 1000) * 500 + calendar.get(Calendar.DAY_OF_YEAR)) * 10000 + logicalVersion
    }
    val buildDate = dateFormat.format(calendar.time)
    return VersionInfo(
        marketingVersion = marketingVersion,
        logicalVersion = codeVersion,
        buildDate = buildDate,
        buildRef = buildRef,
    )
}

package db_key_value.shared_prefs

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun getSharedPrefsFile(
    context: Context,
    file: String,
): File = withContext(Dispatchers.IO) {
    File(context.dataDir, "shared_prefs/$file.xml")
}

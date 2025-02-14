package db_key_value.datastore

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal suspend fun getDataStoreFile(
    context: Context,
    file: String,
): File = withContext(Dispatchers.IO) {
    File(context.dataDir, "datastore/$file.preferences_pb")
}

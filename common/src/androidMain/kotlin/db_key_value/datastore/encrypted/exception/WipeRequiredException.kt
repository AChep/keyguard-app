package db_key_value.datastore.encrypted.exception

internal class WipeRequiredException(
    val failure: Throwable,
) : Exception(failure)

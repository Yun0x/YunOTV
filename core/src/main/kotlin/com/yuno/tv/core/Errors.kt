package com.yuno.tv.core

import com.yuno.tv.core.model.ErrorCode
import com.yuno.tv.core.model.NetworkResult
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException

class SourceException(val code: ErrorCode) : IOException(code.name)
fun Throwable.friendlyCode(): ErrorCode = when (this) {
    is TimeoutCancellationException, is SocketTimeoutException -> ErrorCode.TIMEOUT
    is SourceException -> code
    is UnknownHostException, is IOException -> ErrorCode.NETWORK
    is IllegalArgumentException -> ErrorCode.INVALID_URL
    else -> ErrorCode.UNKNOWN
}
fun Throwable.rethrowCancellation() {
    if (this is CancellationException && this !is TimeoutCancellationException) throw this
}

suspend fun <T> networkResult(block: suspend () -> T): NetworkResult<T> = try {
    NetworkResult.Success(block())
} catch (error: Exception) {
    error.rethrowCancellation()
    NetworkResult.Error(error.friendlyCode())
}

package com.yuno.tv.core.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.yuno.tv.core.model.ErrorCode

@Composable
fun errorText(code: ErrorCode): String = stringResource(when (code) {
    ErrorCode.NETWORK -> R.string.error_network
    ErrorCode.TIMEOUT -> R.string.error_timeout
    ErrorCode.DOWNLOAD -> R.string.error_download
    ErrorCode.CONFIG_FORMAT -> R.string.error_config
    ErrorCode.ENCODING -> R.string.error_encoding
    ErrorCode.INVALID_URL -> R.string.error_url
    ErrorCode.UNSUPPORTED -> R.string.error_unsupported
    ErrorCode.EMPTY -> R.string.error_empty
    ErrorCode.PLAYBACK -> R.string.error_playback
    ErrorCode.STORAGE -> R.string.error_storage
    ErrorCode.FILE_PICKER -> R.string.error_file_picker
    ErrorCode.UNKNOWN -> R.string.error_unknown
})

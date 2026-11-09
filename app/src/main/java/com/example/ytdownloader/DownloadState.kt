package com.example.ytdownloader

import kotlinx.coroutines.flow.MutableStateFlow

data class DownloadUi(
    val running: Boolean = false,
    val progress: Float = 0f,
    val status: String = "",
    val speed: String = "",
    val eta: Long = -1
)

object DownloadState {
    val state = MutableStateFlow(DownloadUi())
}
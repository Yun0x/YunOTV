package com.yuno.tv.core.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class ModelFactory<T : ViewModel>(private val create: () -> T) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <VM : ViewModel> create(modelClass: Class<VM>): VM = create() as VM
}

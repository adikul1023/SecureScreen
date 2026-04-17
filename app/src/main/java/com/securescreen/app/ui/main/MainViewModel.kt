package com.securescreen.app.ui.main

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.securescreen.app.data.AppInfo
import com.securescreen.app.data.AppRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = AppRepository(application)

    private val _apps = MutableLiveData<List<AppInfo>>(emptyList())
    val apps: LiveData<List<AppInfo>> = _apps

    private val _protectedPackages = MutableLiveData<Set<String>>(emptySet())
    val protectedPackages: LiveData<Set<String>> = _protectedPackages

    private val _serviceEnabled = MutableLiveData(false)
    val serviceEnabled: LiveData<Boolean> = _serviceEnabled

    fun loadApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val allApps = repository.getInstalledLaunchableApps()
            withContext(Dispatchers.Main) {
                _apps.value = allApps
            }
        }
    }

    fun loadState() {
        _protectedPackages.value = repository.getProtectedPackages()
        _serviceEnabled.value = repository.isProtectionEnabled()
    }

    fun setPackageProtected(packageName: String, isProtected: Boolean) {
        repository.setPackageProtected(packageName, isProtected)
        _protectedPackages.value = repository.getProtectedPackages()
    }

    fun setServiceEnabled(enabled: Boolean) {
        repository.setProtectionEnabled(enabled)
        _serviceEnabled.value = enabled
    }
}

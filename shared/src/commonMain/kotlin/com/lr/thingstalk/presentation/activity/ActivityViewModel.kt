package com.lr.thingstalk.presentation.activity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lr.thingstalk.data.activity.*
class ActivityViewModel : ViewModel() {
    val repository = StoredActivityRepository(createActivityStorage(), viewModelScope)
}

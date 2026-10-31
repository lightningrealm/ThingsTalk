package com.lr.thingstalk.di

import com.lr.thingstalk.data.appearance.StoredAppearanceRepository
import com.lr.thingstalk.data.appearance.createAppearanceStorage
import com.lr.thingstalk.presentation.theme.AppearanceViewModel

fun createAppearanceViewModel() = AppearanceViewModel(StoredAppearanceRepository(createAppearanceStorage()))

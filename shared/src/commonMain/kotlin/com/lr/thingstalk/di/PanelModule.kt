package com.lr.thingstalk.di

import com.lr.thingstalk.data.panel.PanelJson
import com.lr.thingstalk.presentation.panel.PanelViewModel
import com.lr.thingstalk.domain.panel.PanelDefinition

fun createPanelViewModel(definition: PanelDefinition, onSave: suspend (PanelDefinition) -> Unit): PanelViewModel =
    PanelViewModel(initialDefinition = definition, codec = PanelJson, onSave = onSave)

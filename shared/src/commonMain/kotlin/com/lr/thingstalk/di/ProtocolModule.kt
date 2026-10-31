package com.lr.thingstalk.di

import com.lr.thingstalk.data.panel.DefaultPayloadInspector
import com.lr.thingstalk.domain.panel.PayloadInspector

fun createPayloadInspector(): PayloadInspector = DefaultPayloadInspector()

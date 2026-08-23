package com.mewmix.nabu.uiagent

import com.mewmix.nabu.data.Model
import com.mewmix.nabu.data.ModelType
import com.mewmix.nabu.data.OAuthRemoteModels

/** The single eligibility boundary for latency-critical Android action execution. */
object ActionModelEligibility {
    fun isEligible(model: Model): Boolean =
        model.type == ModelType.LLM &&
            OAuthRemoteModels.detectSelection(model.id, model.backend)?.provider !=
                OAuthRemoteModels.Provider.CODEX
}

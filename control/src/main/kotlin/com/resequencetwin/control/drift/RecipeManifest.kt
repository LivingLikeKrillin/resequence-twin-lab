package com.resequencetwin.control.drift

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

@JsonIgnoreProperties(ignoreUnknown = true)
data class RecipeManifest(
    val defRef: String = "", val contentSha256: String = "", val version: String = "",
)

package com.oneminutedocs.core.network

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
internal data class TokenResponse(val token: String, val expiresAt: String)

@Serializable
data class FileInfoDto(val fileName: String, val contentType: String, val sizeBytes: Long)

@Serializable
data class OutputDto(val fileName: String, val contentType: String, val sizeBytes: Long, val meta: JsonObject = JsonObject(emptyMap()))

@Serializable
data class JobErrorDto(val code: String, val message: String)

@Serializable
data class JobDto(
    val id: String,
    val operation: String,
    val status: String,
    val progress: Int? = null,
    val createdAt: String,
    val updatedAt: String,
    val expiresAt: String,
    val inputs: List<FileInfoDto> = emptyList(),
    val output: OutputDto? = null,
    val error: JobErrorDto? = null,
)

@Serializable
internal data class ErrorEnvelope(val error: ApiErrorDto)

@Serializable
internal data class ApiErrorDto(val code: String, val message: String, val requestId: String? = null)

@Serializable
data class AiRequestDto(
    val task: String,
    val language: String,
    val documentType: String? = null,
    val tone: String? = null,
    val instructions: String? = null,
    val fields: Map<String, String>? = null,
    val text: String? = null,
    val targetLanguage: String? = null,
)

@Serializable
data class AiResultDto(
    val task: String,
    val language: String,
    val markdown: String,
    val aiGenerated: Boolean,
    val provider: String,
    val model: String,
    val warnings: List<String> = emptyList(),
)

@Serializable
data class CapabilityOperationDto(val name: String, val available: Boolean)

@Serializable
data class CapabilitiesDto(val operations: List<CapabilityOperationDto> = emptyList(), val ai: AiCapabilityDto? = null)

@Serializable
data class AiCapabilityDto(val configured: Boolean)

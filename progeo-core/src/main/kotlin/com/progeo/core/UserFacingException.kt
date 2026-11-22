package com.progeo.core

/**
 * A failure the user can act on: [userMessage] is shown as is, [message] carries the
 * technical detail for logs.
 */
open class UserFacingException(
    val userMessage: String,
    detail: String? = null,
    cause: Throwable? = null,
) : Exception(detail ?: userMessage, cause)

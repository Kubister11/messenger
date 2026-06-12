package dev.kubister11.messenger.config

data class NatsConfiguration(
    val url: String,
    val username: String = "",
    val password: String = "",
    val channel: String,
    val globalChannel: String,
    val prefix: String = ""
)

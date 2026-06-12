package dev.kubister11.messenger.config

data class RedisConfiguration(
    val host: String,
    val port: Int = 6379,
    val password: String = "",
    val channel: String,
    val globalChannel: String,
    val prefix: String = ""
)

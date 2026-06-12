package dev.kubister11.messenger.impl.redis

import dev.kubister11.messenger.listener.MessageListener

class RedisMessageListener<T : Any>(
    val listener: MessageListener<T>
) : org.redisson.api.listener.MessageListener<T> {
    override fun onMessage(channel: CharSequence, msg: T) {
        this.listener.onMessage(msg)
    }
}
package dev.kubister11.messenger.impl.redis

import dev.kubister11.messenger.listener.MessageListener
import java.util.concurrent.Executor

class RedisMessageListener<T : Any>(
    val listener: MessageListener<T>,
    private val executor: Executor
) : org.redisson.api.listener.MessageListener<T> {
    override fun onMessage(channel: CharSequence, msg: T) {
        this.executor.execute { this.listener.onMessage(msg) }
    }
}
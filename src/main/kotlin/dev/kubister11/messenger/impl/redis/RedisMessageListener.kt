package dev.kubister11.messenger.impl.redis

import dev.kubister11.messenger.listener.MessageListener
import org.slf4j.LoggerFactory
import java.util.concurrent.Executor

class RedisMessageListener<T : Any>(
    val listener: MessageListener<T>,
    private val executor: Executor
) : org.redisson.api.listener.MessageListener<T> {

    private companion object {
        private val logger = LoggerFactory.getLogger(RedisMessageListener::class.java)
    }

    override fun onMessage(channel: CharSequence, msg: T) {
        this.executor.execute {
            try {
                this.listener.onMessage(msg)
            } catch (throwable: Throwable) {
                logger.error("Listener on channel {} threw", channel, throwable)
            }
        }
    }
}
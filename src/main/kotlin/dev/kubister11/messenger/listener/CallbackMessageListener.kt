package dev.kubister11.messenger.listener

import dev.kubister11.messenger.MessagingService
import dev.kubister11.messenger.message.CallbackMessage
import dev.kubister11.messenger.message.CallbackResponse
import org.slf4j.LoggerFactory
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type

/**
 * Handles request/response messages of type [REQUEST], replying with [RESPONSE].
 *
 * The runtime [REQUEST] class is resolved from the generic supertype, so subclasses must fix it to a
 * concrete type, e.g. `class PingListener : CallbackMessageListener<Ping, Pong>()`.
 */
abstract class CallbackMessageListener<REQUEST, RESPONSE> : MessageListener<CallbackMessage> {

    private companion object {
        private val logger = LoggerFactory.getLogger(CallbackMessageListener::class.java)
    }

    lateinit var messagingService: MessagingService

    private val requestType: Class<*> = resolveRequestType()

    abstract fun onMessage(packet: REQUEST): CallbackResponse<RESPONSE>

    override fun onMessage(packet: CallbackMessage) {
        val request = packet.payload
        if (!this.requestType.isInstance(request)) return

        try {
            @Suppress("UNCHECKED_CAST")
            val response = onMessage(request as REQUEST)
            if (response.type == CallbackResponse.Type.SKIP) return

            response.response.thenAccept { responseObject ->
                this.messagingService.respond(
                    CallbackMessage(
                        responseObject,
                        packet.callbackCallback,
                        packet.uuid,
                        packet.callbackScope
                    )
                )
            }
        } catch (throwable: Throwable) {
            logger.error("Callback listener for {} threw while handling a request", this.requestType.name, throwable)
        }
    }

    private fun resolveRequestType(): Class<*> {
        var type: Type = javaClass
        while (type is Class<*> || type is ParameterizedType) {
            if (type is ParameterizedType && type.rawType == CallbackMessageListener::class.java) {
                return type.actualTypeArguments[0].toRawClass()
            }
            val raw = if (type is ParameterizedType) type.rawType as Class<*> else type as Class<*>
            type = raw.genericSuperclass ?: break
        }
        error(
            "Cannot resolve the REQUEST type of ${javaClass.name}. " +
                "Subclass CallbackMessageListener with a concrete request type, " +
                "e.g. `class MyListener : CallbackMessageListener<MyRequest, MyResponse>()`."
        )
    }

    private fun Type.toRawClass(): Class<*> = when (this) {
        is Class<*> -> this
        is ParameterizedType -> this.rawType as Class<*>
        else -> error("Unsupported REQUEST type bound: $this")
    }
}

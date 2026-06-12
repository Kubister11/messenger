package dev.kubister11.messenger.message

import java.util.concurrent.CompletableFuture

data class CallbackResponse<RESPONSE>(
    var type: Type,
    var response: CompletableFuture<RESPONSE?>
) {
    enum class Type {
        SUCCESS,
        SKIP,
        FUTURE
    }

    companion object {
        fun <RESPONSE> success(response: RESPONSE): CallbackResponse<RESPONSE> {
            return CallbackResponse(Type.SUCCESS, CompletableFuture.completedFuture(response))
        }

        fun <RESPONSE> future(response: CompletableFuture<RESPONSE?>): CallbackResponse<RESPONSE> {
            return CallbackResponse(Type.FUTURE, response)
        }

        fun <RESPONSE> skip(): CallbackResponse<RESPONSE> {
            return CallbackResponse(Type.SKIP, CompletableFuture.completedFuture(null))
        }
    }
}

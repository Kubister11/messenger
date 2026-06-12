package dev.kubister11.messenger.impl.redis.codec

import io.netty.buffer.ByteBuf
import io.netty.buffer.ByteBufAllocator
import org.apache.fory.ThreadSafeFory
import org.redisson.client.codec.BaseCodec
import org.redisson.client.handler.State
import org.redisson.client.protocol.Decoder
import org.redisson.client.protocol.Encoder

class RedisForyCodec(private val fory: ThreadSafeFory) : BaseCodec() {

    private val encoder = Encoder { obj ->
        val bytes = fory.serialize(obj)
        val buffer = ByteBufAllocator.DEFAULT.buffer(bytes.size)
        buffer.writeBytes(bytes)
        buffer
    }

    private val decoder = Decoder { buf: ByteBuf, _: State? ->
        val bytes = ByteArray(buf.readableBytes())
        buf.readBytes(bytes)
        fory.deserialize(bytes)
    }

    override fun getValueEncoder(): Encoder {
        return encoder
    }

    override fun getValueDecoder(): Decoder<Any?> {
        return decoder
    }
}

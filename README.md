# messenger

Transport-agnostic publish/subscribe and request/response messaging for the JVM. Backends: **Redis**
(Redisson), **NATS**, and a no-op `NONE`. Wire serialization uses [Apache Fory](https://fory.apache.org/).

## Install

```kotlin
dependencies {
    implementation("dev.kubister11:messenger:1.0.0")

    // Pick the backend you use — both are optional (compileOnly in the library).
    runtimeOnly("org.redisson:redisson:4.5.0")
    // or
    runtimeOnly("io.nats:jnats:2.25.3")

    // Bind a logger (the library only depends on slf4j-api).
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")
}
```

## Usage

```kotlin
import dev.kubister11.messenger.MessagingService
import dev.kubister11.messenger.config.RedisConfiguration
import dev.kubister11.messenger.serialization.MessengerFory
import dev.kubister11.messenger.type.MessagingType

data class Ping(val text: String)
data class Pong(val text: String)

// One shared, thread-safe Fory. Register every message type you send.
val fory = MessengerFory.create {
    it.register(Ping::class.java)
    it.register(Pong::class.java)
}

val messaging = MessagingService.create(
    type = MessagingType.REDIS,
    fory = fory,
    redis = RedisConfiguration(
        host = "localhost",
        channel = "my-service",      // local namespace
        globalChannel = "global",    // cross-service namespace
        prefix = "myapp"             // optional channel prefix; empty by default
    )
)

messaging.use { service ->          // AutoCloseable — releases the connection on exit
    service.connect()

    // fire-and-forget
    service.publish("greetings", Ping("hi"))

    // request/response
    val pong: Pong = service.request<Pong>("ping", Ping("hi")).get()
}
```

## Channel naming

A scoped channel is built as `[prefix]<namespace><sep><channel>`, where the namespace is `channel`
(LOCAL scope) or `globalChannel` (GLOBAL scope). `prefix` is empty by default — set it yourself if you
want one. Separators: Redis uses `@`/`::`, NATS uses `.`.

## Lifecycle

- `connect()` must be called before publishing or subscribing.
- `close()` (or `use { }`) fails any in-flight `request` futures and releases the connection. It is
  idempotent.
- `request(...)` futures time out after `MessagingService.DEFAULT_REQUEST_TIMEOUT` (30s) by default;
  pass a `timeout` to override. Correlation entries are always cleaned up, so the request map cannot grow
  unbounded.

## Security

`MessengerFory.create(requireClassRegistration = false)` (the default) lets Fory instantiate any class
named in an incoming payload. That is a remote-code-execution vector if untrusted peers can publish to
your broker. For production with any untrusted peer, pass `requireClassRegistration = true` and register
all message types explicitly.

## License

Apache License 2.0 — see [`LICENSE`](LICENSE).

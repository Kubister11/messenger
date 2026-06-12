package dev.kubister11.messenger.listener

interface MessageListener<T> {
    fun onMessage(packet: T)
}
package cc.taixue.lod.server

import java.nio.ByteBuffer
import java.util.concurrent.ExecutionException
import java.util.concurrent.Flow
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ManagementBodySubscriberTest {
    private class Subscription : Flow.Subscription {
        var cancelled = false

        override fun request(count: Long) {}

        override fun cancel() {
            cancelled = true
        }
    }

    @Test
    fun `oversized responses cancel before they are copied`() {
        val subscriber = ManagementBodySubscriber(4)
        val subscription = Subscription()
        subscriber.onSubscribe(subscription)
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(1, 2, 3))))
        subscriber.onNext(listOf(ByteBuffer.wrap(byteArrayOf(4, 5))))
        assertTrue(subscription.cancelled)
        assertThrows(ExecutionException::class.java) { subscriber.body.toCompletableFuture().get() }
    }

    @Test
    fun `bounded bodies retain exact bytes`() {
        val subscriber = ManagementBodySubscriber(4)
        subscriber.onSubscribe(Subscription())
        subscriber.onNext(
            listOf(ByteBuffer.wrap(byteArrayOf(1, 2)), ByteBuffer.wrap(byteArrayOf(3, 4)))
        )
        subscriber.onComplete()
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), subscriber.body.toCompletableFuture().get())
    }
}

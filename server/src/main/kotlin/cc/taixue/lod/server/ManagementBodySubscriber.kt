package cc.taixue.lod.server

import java.io.ByteArrayOutputStream
import java.net.http.HttpResponse
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow

/** Cancels oversized management responses before allocating or decoding their bodies. */
internal class ManagementBodySubscriber(private val limit: Int) :
    HttpResponse.BodySubscriber<ByteArray> {
    private val result = CompletableFuture<ByteArray>()
    private val bytes = ByteArrayOutputStream()
    private var subscription: Flow.Subscription? = null

    override fun getBody(): CompletionStage<ByteArray> = result

    override fun onSubscribe(value: Flow.Subscription) {
        if (subscription != null) {
            value.cancel()
            return
        }
        subscription = value
        value.request(1)
    }

    override fun onNext(items: List<ByteBuffer>) {
        if (result.isDone) return
        if (items.sumOf { it.remaining().toLong() } > limit - bytes.size()) {
            subscription?.cancel()
            result.completeExceptionally(IllegalArgumentException("management response too large"))
            return
        }
        items.forEach { buffer ->
            val value = ByteArray(buffer.remaining())
            buffer.get(value)
            bytes.write(value)
        }
        subscription?.request(1)
    }

    override fun onError(error: Throwable) {
        result.completeExceptionally(error)
    }

    override fun onComplete() {
        result.complete(bytes.toByteArray())
    }
}

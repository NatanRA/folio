package app.folio.library

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.readium.r2.shared.util.DebugError
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Url
import org.readium.r2.shared.util.data.Container
import org.readium.r2.shared.util.data.ReadError
import org.readium.r2.shared.util.resource.Resource
import java.util.concurrent.atomic.AtomicBoolean

/** Drain active archive reads before closing; late WebView reads receive a normal error. */
internal class ReaderResources(private val delegate: Container<Resource>) : Container<Resource> {
    private val mutex = Mutex()
    private val closed = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override val sourceUrl = delegate.sourceUrl
    override val entries = delegate.entries
    override fun get(url: Url): Resource? {
        if (closed.get()) return null
        val resource = delegate[url] ?: return null
        return object : Resource by resource {
            private val released = AtomicBoolean(false)
            override suspend fun read(range: LongRange?): Try<ByteArray, ReadError> = mutex.withLock {
                if (closed.get() || released.get()) Try.failure(ReadError.UnsupportedOperation(DebugError("Book is closed"))) else resource.read(range)
            }
            override fun close() {
                if (released.compareAndSet(false, true)) scope.launch { mutex.withLock { resource.close() } }
            }
        }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) scope.launch {
            try { mutex.withLock { delegate.close() } } finally { scope.cancel() }
        }
    }
}

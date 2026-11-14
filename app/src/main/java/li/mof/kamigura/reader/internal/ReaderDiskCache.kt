package li.mof.kamigura.reader.internal

import android.system.ErrnoException
import android.system.OsConstants
import coil.annotation.ExperimentalCoilApi
import coil.disk.DiskCache
import java.io.IOException

private class ReaderDiskCacheMissAfterWrite : IOException("Reader cache entry was not retained")

internal fun readerDiskCacheCanBypass(error: Throwable): Boolean =
    generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).any {
        it is ReaderDiskCacheMissAfterWrite || it is ErrnoException && it.errno == OsConstants.ENOSPC
    }

@OptIn(ExperimentalCoilApi::class)
internal class ReaderDiskCache(private val delegate: DiskCache) : DiskCache by delegate {
    override fun openEditor(key: String): DiskCache.Editor? = delegate.openEditor(key)?.let(::Editor)

    override fun openSnapshot(key: String): DiskCache.Snapshot? =
        delegate.openSnapshot(key)?.let { snapshot ->
            object : DiskCache.Snapshot by snapshot {
                override fun closeAndOpenEditor(): DiskCache.Editor? = snapshot.closeAndOpenEditor()?.let(::Editor)
            }
        }

    private class Editor(private val delegate: DiskCache.Editor) : DiskCache.Editor by delegate {
        override fun commitAndOpenSnapshot(): DiskCache.Snapshot =
            // Coil 2.7 would close the response and try to read its body again on null.
            delegate.commitAndOpenSnapshot() ?: throw ReaderDiskCacheMissAfterWrite()
    }
}

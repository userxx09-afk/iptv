package io.tapper.firetv.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.File as SmbFile
import io.tapper.firetv.data.RecordingLocationStore
import io.tapper.firetv.data.SmbStorage
import io.tapper.firetv.data.SmbUri
import java.io.IOException

/**
 * Plays smb:// recordings saved on a network share, with seeking. Media3 has
 * no SMB support of its own, so this reads the file with smbj at whatever
 * offset the player asks for.
 */
@OptIn(UnstableApi::class)
class SmbDataSource(private val location: RecordingLocationStore) : BaseDataSource(/* isNetwork = */ true) {

    private var opened: SmbStorage.Opened? = null
    private var file: SmbFile? = null
    private var uri: Uri? = null
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        val target = dataSpec.uri.toString()
        val parsed = SmbUri.parse(target) ?: throw IOException("Not an smb:// address")
        val share = location.shareFor(target) ?: throw IOException("Not an smb:// address")
        transferInitializing(dataSpec)
        uri = dataSpec.uri
        val o = try {
            SmbStorage.open(share)
        } catch (t: Throwable) {
            throw IOException(SmbStorage.friendly(t), t)
        }
        opened = o
        val f = try {
            o.share.openFile(
                parsed.path,
                setOf(AccessMask.GENERIC_READ),
                null,
                setOf(SMB2ShareAccess.FILE_SHARE_READ, SMB2ShareAccess.FILE_SHARE_WRITE),
                SMB2CreateDisposition.FILE_OPEN,
                null,
            )
        } catch (t: Throwable) {
            close()
            throw IOException(SmbStorage.friendly(t), t)
        }
        file = f
        val size = f.fileInformation.standardInformation.endOfFile
        position = dataSpec.position
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length
            else (size - position).coerceAtLeast(0)
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        val f = file ?: throw IOException("Not open")
        // One SMB read per call, kept under the smallest read size servers allow.
        val want = minOf(length.toLong(), remaining, 65_536L).toInt()
        val n = f.read(buffer, position, offset, want)
        if (n <= 0) return C.RESULT_END_OF_INPUT
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val wasOpen = file != null
        runCatching { file?.close() }
        file = null
        opened?.close()
        opened = null
        uri = null
        if (wasOpen) transferEnded()
    }
}

/**
 * The player's data source: smb:// goes to [SmbDataSource], everything else
 * (http, https, file://) to Media3's default, wrapping the player's own
 * HTTP settings. The plain HTTP factory alone couldn't open file:// either,
 * which is what recordings on this Fire TV use.
 */
@OptIn(UnstableApi::class)
class RoutingDataSourceFactory(
    private val context: Context,
    private val http: DataSource.Factory,
    private val location: RecordingLocationStore,
) : DataSource.Factory {
    override fun createDataSource(): DataSource = RoutingDataSource(
        DefaultDataSource.Factory(context, http).createDataSource(),
        SmbDataSource(location),
    )
}

@OptIn(UnstableApi::class)
private class RoutingDataSource(
    private val normal: DataSource,
    private val smb: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        normal.addTransferListener(transferListener)
        smb.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val ds = if (dataSpec.uri.scheme.equals("smb", ignoreCase = true)) smb else normal
        active = ds
        return ds.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        (active ?: throw IOException("Not open")).read(buffer, offset, length)

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try { active?.close() } finally { active = null }
    }
}

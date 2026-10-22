package app.droidscp

import android.app.Application
import app.droidscp.data.PrefsKnownHosts
import app.droidscp.data.SiteRepository
import app.droidscp.data.TransferManager
import org.bouncycastle.jce.provider.BouncyCastleProvider
import java.security.Security

class DroidScpApp : Application() {
    lateinit var sites: SiteRepository
        private set
    lateinit var knownHosts: PrefsKnownHosts
        private set
    val transfers = TransferManager()

    override fun onCreate() {
        super.onCreate()
        // Android membawa provider "BC" versi ringkas yang bentrok dengan sshj (ed25519, curve25519, dll.).
        // Ganti dengan BouncyCastle lengkap dari dependensi.
        Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
        Security.insertProviderAt(BouncyCastleProvider(), 1)

        sites = SiteRepository(this)
        knownHosts = PrefsKnownHosts(this)
    }
}

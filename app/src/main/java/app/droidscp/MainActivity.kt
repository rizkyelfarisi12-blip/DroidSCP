package app.droidscp

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.droidscp.ui.AppViewModel
import app.droidscp.ui.DroidApp
import app.droidscp.ui.DroidTheme

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    private val notifPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* transfer tetap jalan tanpa notifikasi */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent {
            DroidTheme {
                val state by vm.ui.collectAsStateWithLifecycle()
                val sites by vm.sites.collectAsStateWithLifecycle()
                DroidApp(vm, state, sites)
            }
        }
    }
}

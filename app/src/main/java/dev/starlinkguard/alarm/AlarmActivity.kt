package dev.starlinkguard.alarm

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.starlinkguard.data.MonitorRepository
import dev.starlinkguard.service.MonitorService
import dev.starlinkguard.ui.theme.StarlinkGuardTheme

/**
 * The screen the alarm throws up over whatever the phone was doing — including the lock
 * screen. Its only job is to say what moved and offer a way to stop the noise.
 */
class AlarmActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showOverLockScreen()

        setContent {
            StarlinkGuardTheme {
                val state by MonitorRepository.uiState.collectAsState()
                AlarmScreen(
                    reasons = state.activeTriggers.map { it.describe() },
                    onStop = {
                        startService(
                            Intent(this, MonitorService::class.java)
                                .setAction(MonitorService.ACTION_STOP_ALARM),
                        )
                        finish()
                    },
                )
            }
        }
    }

    private fun showOverLockScreen() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD,
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
}

@Composable
private fun AlarmScreen(reasons: List<String>, onStop: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF8B0000))
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(96.dp),
        )
        Text(
            text = "DISH MOVED",
            style = MaterialTheme.typography.displaySmall,
            color = Color.White,
            modifier = Modifier.padding(top = 16.dp),
        )
        if (reasons.isEmpty()) {
            Text(
                text = "The dish's orientation changed unexpectedly.",
                color = Color.White,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp),
            )
        } else {
            reasons.forEach { reason ->
                Text(
                    text = reason,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Button(
            onClick = onStop,
            colors = ButtonDefaults.buttonColors(
                containerColor = Color.White,
                contentColor = Color(0xFF8B0000),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 48.dp),
        ) {
            Text("STOP ALARM", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            text = "Stopping re-arms monitoring from the dish's current position.",
            color = Color.White.copy(alpha = 0.85f),
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

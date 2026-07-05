package com.vishaal.healthconnector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

/**
 * Health Connect requires apps that request permissions to declare an activity that responds
 * to the `androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE` intent action (surfaced from the
 * Health Connect app's permission management UI as "why does this app want this data"). This
 * is a minimal standalone screen satisfying that requirement; see the intent-filter on this
 * activity in AndroidManifest.xml.
 */
class PrivacyPolicyActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        Text(text = stringResource(id = R.string.privacy_policy_title))
                        Text(
                            text = stringResource(id = R.string.privacy_policy_body),
                            modifier = Modifier.padding(top = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

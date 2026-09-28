package app.folio.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable internal fun AvailabilityBadges(local: Boolean, inBookLore: Boolean, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        if (inBookLore) Icon(Icons.Outlined.Cloud, "From your cloud library (last checked)",
            Modifier.size(22.dp).background(MaterialTheme.colorScheme.surface, CircleShape).border(1.dp, MaterialTheme.colorScheme.primary, CircleShape).padding(3.dp), tint = MaterialTheme.colorScheme.primary)
        if (local) Icon(Icons.Outlined.CheckCircle, "Available offline",
            Modifier.size(22.dp).background(MaterialTheme.colorScheme.surface, CircleShape), tint = MaterialTheme.colorScheme.primary)
    }
}

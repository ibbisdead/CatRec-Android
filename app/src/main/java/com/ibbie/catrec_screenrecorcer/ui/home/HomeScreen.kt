package com.ibbie.catrec_screenrecorcer.ui.home

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ibbie.catrec_screenrecorcer.navigation.Screen
import com.ibbie.catrec_screenrecorcer.ui.components.CatRecIcons

@Composable
fun HomeScreen(
    onNavigate: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp)
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Header
        Icon(
            imageVector = CatRecIcons.Paw,
            contentDescription = "CatRec Logo",
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "CatRec",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "v0.5.0 Public Beta",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Start Recording Button
        Button(
            onClick = { onNavigate(Screen.Recording.route) },
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Icon(
                imageVector = CatRecIcons.Recordings,
                contentDescription = null,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(16.dp))
            Text(
                text = "Start Recording",
                style = MaterialTheme.typography.headlineSmall
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        // Quick Links
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            QuickLinkItem(
                text = "Settings",
                icon = CatRecIcons.Settings,
                onClick = { onNavigate(Screen.Settings.route) }
            )
            QuickLinkItem(
                text = "Recordings",
                icon = CatRecIcons.Recordings,
                onClick = { onNavigate(Screen.Recordings.route) }
            )
            QuickLinkItem(
                text = "Tools",
                icon = CatRecIcons.Tools,
                onClick = { onNavigate(Screen.Tools.route) }
            )
            QuickLinkItem(
                text = "Support",
                icon = CatRecIcons.Support,
                onClick = { onNavigate(Screen.Support.route) }
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
fun QuickLinkItem(
    text: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.padding(8.dp)
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(56.dp)
        ) {
            Icon(imageVector = icon, contentDescription = text)
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium
        )
    }
}

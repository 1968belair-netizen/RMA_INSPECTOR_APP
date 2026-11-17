package com.bitzer.rmainspector.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.bitzer.rmainspector.R
import com.bitzer.rmainspector.ui.theme.BitzerColors

/**
 * Black top bar with the official BITZER logo (style guide: logo only on black or white backgrounds,
 * scaled proportionally, unchanged). Title in capitals.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BitzerTopBar(
    title: String,
    navigationIcon: @Composable () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Image(
                    painter = painterResource(R.drawable.bitzer_logo),
                    contentDescription = "BITZER",
                    modifier = Modifier.height(36.dp),
                )
                Spacer(Modifier.width(16.dp))
                Text(title.uppercase())
            }
        },
        navigationIcon = navigationIcon,
        actions = actions,
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = BitzerColors.Black,
            titleContentColor = BitzerColors.White,
            navigationIconContentColor = BitzerColors.White,
            actionIconContentColor = BitzerColors.White,
        ),
    )
}

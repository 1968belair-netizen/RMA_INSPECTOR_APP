package com.bitzer.rmainspector.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.ui.AppUiState
import com.bitzer.rmainspector.ui.Connection
import com.bitzer.rmainspector.ui.components.BitzerTopBar
import com.bitzer.rmainspector.ui.components.ConnectionBanner
import com.bitzer.rmainspector.ui.components.InfoBanner
import com.bitzer.rmainspector.ui.components.MultiSelectChipRow
import com.bitzer.rmainspector.ui.components.RmaCard
import com.bitzer.rmainspector.ui.components.ScreenHeader
import com.bitzer.rmainspector.ui.theme.BitzerColors
import java.time.LocalDate

/** Test tags for the emulator tests. */
object HomeTags {
    const val SEARCH = "home-search"
    const val COUNT = "home-work-count"
    fun workItem(rmaId: String) = "work-item-$rmaId"
    fun searchItem(rmaId: String) = "search-item-$rmaId"
}

/** Home (docs/spec.md §3): server status, one search box, Received work list with product-type / return-code chips. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(
    app: AppUiState,
    onOpenSettings: () -> Unit,
    onRetryServer: () -> Unit,
    onOpenRma: (rmaId: String) -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }

    // A single search hit opens the RMA straight away (spec §3.3).
    LaunchedEffect(state.openRmaId) {
        state.openRmaId?.let { onOpenRma(it); vm.onRmaOpened() }
    }

    HomeContent(
        app = app,
        state = state,
        query = query,
        onQueryChange = { query = it },
        onSearch = { vm.search(query) },
        onClearSearch = { query = ""; vm.clearSearch() },
        onToggleProductType = vm::toggleProductType,
        onToggleReturnCode = vm::toggleReturnCode,
        onRefreshWorkList = vm::refreshWorkList,
        onOpenSettings = onOpenSettings,
        onRetryServer = onRetryServer,
        onOpenRma = onOpenRma,
    )
}

/** Stateless Home content — used by [HomeScreen] and by screenshot tests. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeContent(
    app: AppUiState,
    state: HomeState,
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClearSearch: () -> Unit,
    onToggleProductType: (Code) -> Unit,
    onToggleReturnCode: (Code) -> Unit,
    onRefreshWorkList: () -> Unit,
    onOpenSettings: () -> Unit,
    onRetryServer: () -> Unit,
    onOpenRma: (rmaId: String) -> Unit,
    today: LocalDate = LocalDate.now(),
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val runSearch = { keyboard?.hide(); onSearch() }

    Scaffold(
        topBar = {
            BitzerTopBar(
                title = "RMA Inspector",
                actions = {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "Settings") }
                },
            )
        },
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = state.workList.loading,
            onRefresh = { onRetryServer(); onRefreshWorkList() },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyColumn(Modifier.fillMaxSize()) {
                item {
                    Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (app.connection !is Connection.Ok || (app.connection as Connection.Ok).updateRequired) {
                            ConnectionBanner(app.connection) {
                                if (app.connection is Connection.Error) {
                                    Row {
                                        TextButton(onClick = onRetryServer) { Text("Retry") }
                                        TextButton(onClick = onOpenSettings) { Text("Settings") }
                                    }
                                }
                            }
                        }
                        app.user?.let { user ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Person, contentDescription = null, tint = BitzerColors.Grey90)
                                Text("  Inspector: ${user.displayName} (${user.initials})", style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                // --- Search
                item {
                  Column {
                    ScreenHeader("Find an RMA", subtitle = "RMA number, serial number, or anything else that identifies the RMA.")
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        OutlinedTextField(
                            value = query,
                            onValueChange = onQueryChange,
                            label = { Text("RMA no. or serial") },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (query.isNotEmpty()) {
                                        IconButton(onClick = onClearSearch) { Icon(Icons.Filled.Close, contentDescription = "Clear") }
                                    }
                                    // Barcode / serial-plate scanning comes in D1 (#19); shown, not usable yet.
                                    IconButton(onClick = {}, enabled = false) {
                                        Icon(Icons.Filled.PhotoCamera, contentDescription = "Scan (coming soon)")
                                    }
                                }
                            },
                            modifier = Modifier.weight(1f).testTag(HomeTags.SEARCH),
                        )
                        Button(onClick = runSearch, enabled = query.isNotBlank(), modifier = Modifier.heightIn(min = 56.dp)) {
                            Icon(Icons.Filled.Search, contentDescription = null)
                            Text("  Search")
                        }
                    }
                  }
                }
                when (val s = state.search) {
                    SearchState.Idle -> {}
                    SearchState.Searching -> item {
                        Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text("  Searching…", style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    is SearchState.NoMatch -> item {
                        Text("No RMA found for \"${s.query}\".", style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    }
                    is SearchState.Error -> item {
                        InfoBanner(Icons.Filled.ErrorOutline, s.message, BitzerColors.Error, Modifier.padding(horizontal = 24.dp, vertical = 12.dp))
                    }
                    is SearchState.Results -> {
                        item {
                            Text(
                                "${s.items.size} RMA${if (s.items.size == 1) "" else "s"} for \"${s.query}\"" +
                                    if (s.matchedOn == "serial") " (by serial)" else "",
                                style = MaterialTheme.typography.titleSmall,
                                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 8.dp),
                            )
                        }
                        items(s.items, key = { "s-" + it.rmaId }) { item ->
                            RmaCard(item, today, onClick = { onOpenRma(item.rmaId) },
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag(HomeTags.searchItem(item.rmaId)))
                        }
                    }
                }

                // --- Work list
                val ws = state.workList
                item {
                  Column {
                    ScreenHeader("Work list", subtitle = "RMAs waiting for inspection (Received), oldest first. Pull down to refresh.")
                    Column(Modifier.padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        val f = ws.filter
                        if (ws.productTypes.isNotEmpty()) {
                            MultiSelectChipRow(ws.productTypes, f?.productTypeKeys.orEmpty(), onToggleProductType,
                                label = "Product type", chipText = { it.code })
                        }
                        if (ws.returnCodes.isNotEmpty()) {
                            MultiSelectChipRow(ws.returnCodes, f?.returnCodeKeys.orEmpty(), onToggleReturnCode,
                                label = "Return code")
                        }
                    }
                  }
                }
                when {
                    ws.error != null -> item {
                        InfoBanner(Icons.Filled.ErrorOutline, ws.error, BitzerColors.Error, Modifier.padding(24.dp)) {
                            TextButton(onClick = { onRefreshWorkList() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("Retry") }
                        }
                    }
                    ws.loadedOnce && ws.nothingSelected -> item {
                        Text("Pick at least one product type and one return code to see RMAs.", style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(24.dp))
                    }
                    ws.loadedOnce && ws.items.isEmpty() -> item {
                        Text("No Received RMAs match the selected product types and return codes.", style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(24.dp))
                    }
                    else -> {
                        item {
                            if (ws.loadedOnce) {
                                Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text("${ws.items.size} RMA${if (ws.items.size == 1) "" else "s"}",
                                        style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f).testTag(HomeTags.COUNT))
                                    IconButton(onClick = { onRefreshWorkList() }) { Icon(Icons.Filled.Refresh, contentDescription = "Refresh") }
                                }
                            }
                        }
                        items(ws.items, key = { "w-" + it.rmaId }) { item ->
                            RmaCard(item, today, onClick = { onOpenRma(item.rmaId) },
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp).testTag(HomeTags.workItem(item.rmaId)),
                                draftChanged = item.rmaId in ws.draftChanged)
                        }
                    }
                }
                item { Row(Modifier.padding(bottom = 32.dp)) {} }
            }
        }
    }
}

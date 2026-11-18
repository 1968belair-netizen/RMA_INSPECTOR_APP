package com.bitzer.rmainspector.screenshots

import com.bitzer.rmainspector.data.AppSettings
import com.bitzer.rmainspector.data.Capabilities
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.data.Flag
import com.bitzer.rmainspector.data.Health
import com.bitzer.rmainspector.data.LockState
import com.bitzer.rmainspector.data.PartRow
import com.bitzer.rmainspector.data.PhotoFolder
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.data.RmaHeader
import com.bitzer.rmainspector.data.RmaListItem
import com.bitzer.rmainspector.data.Summary
import com.bitzer.rmainspector.data.SummaryBlock
import com.bitzer.rmainspector.data.User
import com.bitzer.rmainspector.data.WorkListFilter
import com.bitzer.rmainspector.ui.AppUiState
import com.bitzer.rmainspector.ui.Connection
import com.bitzer.rmainspector.ui.SignInProgress
import com.bitzer.rmainspector.ui.home.HomeState
import com.bitzer.rmainspector.ui.home.SearchState
import com.bitzer.rmainspector.ui.home.WorkListState
import com.bitzer.rmainspector.ui.signin.SignInState
import com.bitzer.rmainspector.ui.workspace.GuidedButton
import com.bitzer.rmainspector.ui.workspace.WorkspaceLoad
import com.bitzer.rmainspector.ui.workspace.WorkspaceState

/** FAKE data for screenshots — mirrors the mock server's fake RMAs. */
object ScreenshotData {
    private fun c(code: String, desc: String = code) = Code(null, code, desc)

    val user = User("demo", "Demo Inspector", "DI", "DEMO_SQL", listOf("all"))
    val health = Health(status = "ok", apiVersion = "1.0", serverVersion = "0.2.0-mock", minAppVersion = "0.1.0", backend = "mock",
        backendReachable = true, photoStoreReachable = true)

    val appOk = AppUiState(
        settings = AppSettings("http://10.0.2.2:8000/api/v1"),
        connection = Connection.Ok(health, updateRequired = false),
        user = user,
    )
    val appOffline = appOk.copy(connection = Connection.Error("Can't reach the server at http://10.0.2.2:8000/api/v1. Check the address in Settings and that this device is on the same network."))

    // Sign-in screen (spec §2). Fixed app version so screenshots don't change with every CI run number.
    val signInEmpty = SignInState(serverUrl = "http://10.0.2.2:8000/api/v1", connection = appOk.connection, appVersion = "0.1.0")
    val signInError = signInEmpty.copy(username = "demo", password = "wrong", progress = SignInProgress(error = "Wrong username or password."))
    val signInBusy = signInEmpty.copy(username = "demo", password = "demo", progress = SignInProgress(signingIn = true))
    val signInOffline = signInEmpty.copy(connection = appOffline.connection)

    private fun item(id: String, model: String, type: String, key: String, rc: String, since: String,
                     estimated: Boolean = false, locked: Boolean = false, flags: List<Flag> = emptyList(), serial: String = "") =
        RmaListItem(id, id, model, serial, type, key, c(rc), c("Received"), since, estimated, flags, true, true, locked)

    private val core = Flag("CORE_TEARDOWN", "In core teardown log")
    val workItems = listOf(
        item("912346", "HSN7471-75", "Screw", "screw", "Warranty", "2026-10-08", locked = true, serial = "25X0000002"),
        item("912348", "GSD60235VAB", "Scroll", "scroll", "Warranty", "2026-10-19", serial = "25X0000004"),
        item("912345", "4FE-28Y", "Recip", "recip", "Warranty", "2026-10-26", serial = "25X0000001"),
        item("912352", "PART-0001", "Part", "part", "Repair & Return", "2026-10-30", estimated = true),
        item("912355", "CSH6553-50Y", "Screw", "screw", "Repair & Return", "2026-11-06", flags = listOf(core), serial = "25X0000010"),
        item("912357", "2KES-05Y", "Recip", "recip", "Inspection Only", "2026-11-13", serial = "25X0000012"),
    )
    /** Fixed "today" so ages in screenshots never change. */
    val today: java.time.LocalDate = java.time.LocalDate.of(2026, 11, 18)

    val workProductTypes = listOf("Recip", "Screw", "Scroll", "Part", "Transport", "Pressure Vessel", "System/Unit").map { c(it) }
    val workReturnCodes = listOf("Core Buyback", "Core Credit", "Core&Inspection", "Cus Line Reject", "Freight Damage",
        "Inspection Only", "New & Unused", "Repair & Return", "Warranty").map { c(it) }
    val defaultFilter = WorkListFilter(listOf("Recip", "Screw", "Scroll", "Part"),
        listOf("Inspection Only", "Repair & Return", "Warranty").map { CodeKey(null, it) })

    val homeDefault = HomeState(workList = WorkListState(productTypes = workProductTypes, returnCodes = workReturnCodes,
        filter = defaultFilter, items = workItems, loadedOnce = true))
    /** Screw only, Warranty + Repair & Return. */
    val homeFiltered = homeDefault.copy(workList = homeDefault.workList.copy(
        filter = WorkListFilter(listOf("Screw"), listOf(CodeKey(null, "Repair & Return"), CodeKey(null, "Warranty"))),
        items = workItems.filter { it.productType == "Screw" }))
    val homeEmpty = homeDefault.copy(workList = homeDefault.workList.copy(
        filter = WorkListFilter(listOf("Transport"), listOf(CodeKey(null, "Freight Damage"))), items = emptyList()))
    val homeError = homeDefault.copy(workList = homeDefault.workList.copy(items = emptyList(), loadedOnce = false,
        error = "The server didn't answer in time. Pull down or tap Retry."))
    val homeOffline = HomeState(workList = WorkListState(error = "Can't reach the server. Check the address in Settings."))
    val homeSearch = homeDefault.copy(search = SearchState.Results("25X", "serial", workItems.take(2)))

    val rma = RmaDetail(
        rmaId = "912345", displayNumber = "912345", signature = "sig-example", isOpen = true, inspectable = true,
        productType = "Recip", productTypeKey = "recip",
        capabilities = Capabilities(true, true, true, true, true),
        header = RmaHeader(customerName = "Example Refrigeration Co.", model = "4FE-28Y", failedSerialNo = "25X0000001",
            salesOrderNo = "SO-000000", returnCode = c("Warranty"), inspectionStatus = c("Received"), failedDate = "2/17/2026",
            rmaComment = "Example comment", failureCode = "9-Oil Issue",
            failureDescription = "Example: low oil pressure trips reported", comments = "Example: customer reports repeated trips"),
        parts = listOf(
            PartRow("L1.x", c("07a", "Terminal box"), c("50", "Normal")),
            PartRow("L2.x", c("48a", "Winding resistance"), c("50", "Normal"), note = "Within spec, all 3 phases"),
        ),
        summary = Summary(blocks = listOf(SummaryBlock("1", ""))),
        photos = PhotoFolder(folderFound = false),
    )
    val rmaLocked = rma.copy(rmaId = "912346", displayNumber = "912346",
        lock = LockState(locked = true, heldBy = "JDOE2", host = "EXAMPLE-PC"))
    val rmaScroll = rma.copy(rmaId = "912348", displayNumber = "912348", productType = "Scroll", productTypeKey = "scroll",
        capabilities = Capabilities())
    val rmaClosed = rma.copy(rmaId = "912349", displayNumber = "912349", isOpen = false, capabilities = Capabilities(),
        header = rma.header.copy(inspectionStatus = c("Closed")))

    // Inspection workspace (A5)
    private fun ws(r: RmaDetail) = WorkspaceState(load = WorkspaceLoad.Loaded(r), guided = GuidedButton.Unavailable(r.productType))
    val workspace = ws(rma.copy(photos = PhotoFolder(folderFound = true, folderDisplay = "912xxx\\9123xx\\912345\\All Pics", count = 2,
        files = listOf("00-1 As received.jpg", "00-2 Nameplate.jpg"))))
    val workspaceLocked = ws(rmaLocked.copy(productType = "Screw", productTypeKey = "screw", parts = emptyList(),
        header = rma.header.copy(model = "HSN7471-75", failedSerialNo = "25X0000002")))
    val workspaceScroll = ws(rmaScroll.copy(parts = emptyList(), header = rma.header.copy(model = "GSD60235VAB", failedSerialNo = "25X0000004")))
    val workspaceClosed = ws(rmaClosed)

    // --- Code pickers (A3). FAKE codes, shaped like the mock server's lists.
    val issueCodes = listOf(
        "01" to "Incomplete", "03" to "Missing", "04" to "Damaged", "10" to "Dirty", "12" to "Broken", "13" to "Heavy wear",
        "22" to "Discolored", "32" to "Wear", "32a" to "Polished (Light wear)", "34" to "Scored / Scratched",
        "38" to "Ground fault", "41" to "Overheated", "46" to "Burnt", "50" to "Normal", "51" to "Too low", "54" to "Galled",
    ).map { (code, d) -> Code(null, code, d, pinFirst = code == "50") }
    val normalIssues = listOf(CodeKey(null, "50"))

    /** 180 fake part codes (natural-sort mix of plain, lettered and zero-padded codes). */
    val manyParts: List<Code> = (1..180).map { i ->
        val code = when {
            i % 9 == 0 -> "${i}a"
            i < 10 -> "0$i"
            else -> "$i"
        }
        Code(null, code, "Fake part ${i}")
    } + listOf(Code(null, "48a", "Winding resistance"), Code(null, "PV01", "Vessel"), Code(null, "TR06", "Length of wire w/ connector"))

    val recentParts = listOf(CodeKey(null, "48a"), CodeKey(null, "07"), CodeKey(null, "PV01"))
    val productTypeChips = listOf("Recip", "Scroll", "Screw", "Part", "Unknown").map { c(it) }
    val returnCodeChips = listOf("Inspection Only", "Repair & Return", "Warranty", "Core&Inspection").map { c(it) }
}

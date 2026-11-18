package com.bitzer.rmainspector.screenshots

import com.bitzer.rmainspector.data.AppSettings
import com.bitzer.rmainspector.data.Capabilities
import com.bitzer.rmainspector.data.Code
import com.bitzer.rmainspector.data.CodeKey
import com.bitzer.rmainspector.data.Flag
import com.bitzer.rmainspector.data.Health
import com.bitzer.rmainspector.data.Inspector
import com.bitzer.rmainspector.data.LockState
import com.bitzer.rmainspector.data.PartRow
import com.bitzer.rmainspector.data.PhotoFolder
import com.bitzer.rmainspector.data.RmaDetail
import com.bitzer.rmainspector.data.RmaHeader
import com.bitzer.rmainspector.data.RmaListItem
import com.bitzer.rmainspector.data.Summary
import com.bitzer.rmainspector.data.SummaryBlock
import com.bitzer.rmainspector.ui.AppUiState
import com.bitzer.rmainspector.ui.Connection
import com.bitzer.rmainspector.ui.home.HomeState
import com.bitzer.rmainspector.ui.home.SearchState
import com.bitzer.rmainspector.ui.home.WorkListState

/** FAKE data for screenshots — mirrors the mock server's fake RMAs. */
object ScreenshotData {
    private fun c(code: String, desc: String = code) = Code(null, code, desc)

    val inspector = Inspector("demo", "Demo Inspector", "DI", "DEMO_SQL")
    val health = Health(status = "ok", apiVersion = "1.0", serverVersion = "0.2.0-mock", minAppVersion = "0.1.0", backend = "mock",
        backendReachable = true, photoStoreReachable = true)

    val appOk = AppUiState(
        settings = AppSettings("http://10.0.2.2:8000/api/v1", inspector),
        connection = Connection.Ok(health, updateRequired = false),
        inspectors = listOf(inspector),
    )
    val appOffline = appOk.copy(connection = Connection.Error("Can't reach the server at http://10.0.2.2:8000/api/v1. Check the address in Settings and that this device is on the same network."))

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
    val statuses = listOf("Open", "Entered", "En Route", "Received", "Inspected", "Rework in Progress", "Report Complete",
        "Sent to RSM", "Issued to Customer", "Non-Warranty Processed", "Closed").map { c(it) }

    val homeDefault = HomeState(workList = WorkListState(statuses = statuses, selected = listOf(CodeKey(null, "Received")),
        items = workItems, loadedOnce = true))
    val homeEmpty = homeDefault.copy(workList = homeDefault.workList.copy(items = emptyList()))
    val homeError = HomeState(workList = WorkListState(error = "Can't reach the server. Check the address in Settings."))
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
}

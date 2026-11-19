# Demo guide — BITZER Inspect (mock server, fake data)

A five-minute walk through the whole loop on a tablet or phone, against the **mock server**. Everything shown is
**fake data**: RMA numbers 912361–912369, "Example Refrigeration Co.", fake serials. Nothing touches Macola or the G: drive.

## 1. Before the demo (about 10 minutes)

**What you need:** the work laptop (Python installed), the tablet (or phone) with the debug APK, and your phone as a
hotspot. No BITZER network is needed.

1. **Install the app.** On the tablet, open the GitHub release named **latest** (APK list) and install the newest
   `BITZER-Inspect-…-debug.apk`. Allow "install unknown apps" if asked. A new APK installs over the old one.
2. **Start the mock server.** On the laptop, open the repo's `mock-server` folder and double-click
   **`run_mock_server.bat`**. It installs what it needs, starts the **demo dataset**, and prints the laptop's
   IPv4 address, e.g. `192.168.43.20`. Leave the window open. If Windows asks about the firewall, allow
   **Private networks**.
3. **Put both on the phone's hotspot.** Turn on your phone's hotspot. Connect the **laptop** and the **tablet** to it.
   Run `run_mock_server.bat` (or re-read the address in its window) *after* the laptop has joined the hotspot, so the
   printed address is the hotspot one.
4. **Point the app at the laptop.** On the tablet: sign-in screen → **Settings** (or the gear in the top bar) →
   **Server address** → type `http://<laptop IPv4>:8000/api/v1` → save. The app checks the connection.
5. **Sign in** with the fake user **`demo` / `demo`**.
6. **Reset between runs.** Close the server window and start `run_mock_server.bat` again. Everything goes back to
   the start (finished example included) and the app signs out; sign in again.

**Quick check:** the Home screen should list 8 RMAs, oldest first (912364 at the top, 41 days old).

If something doesn't connect: confirm both devices are on the hotspot, the server window is still open, the address
starts with `http://` and ends with `:8000/api/v1`, and Python was allowed through the firewall. If the server window
shows "Failed building wheel for pillow", the requirements are out of date: download the latest repo ZIP again (Pillow
is now `>=12.3.0`, which has wheels for new Python versions) or install Python 3.13 and re-run.

## 2. The five-minute script

Use **RMA 912361** (Recip, Received) for the main run. Button names are shown in CAPITALS as they appear in the app.

1. **Sign in** (`demo` / `demo`). The Home work list shows the demo RMAs, oldest first.
2. **Find the RMA.** Type `912361` in the search box and open it. (To show the camera instead: tap the camera icon
   and point it at a label or barcode — needs a real tablet camera; see section 4.)
3. **Guided inspection (if included in your APK).** On the RMA workspace tap **Start guided inspection**. For each
   step take the **Overall** photo, then **Next**. After the last step tap **Finish** → "Inspection complete" →
   **Back to workspace**. **Exit** mid-run shows "Continue (step x of y)" next time. If the button is missing, use
   step 4 and add findings by hand.
4. **Findings tab.** Add or change parts, pick an issue code for a part, add a note.
5. **Photos tab.** Take a photo for a part row (camera stays open, "N saved" counter; **Done** closes it). The
   filename preview is labelled "preview — the server makes the final name".
6. **Submit inspection.** Tap **SUBMIT** (top bar). The review screen lists the status change (Received →
   Inspected), parts added/changed/removed and the photo count. Tap **SUBMIT** → "Saving", then "Uploading photos n
   of m" → "Saved. Status is now Inspected." → **DONE** (back to the workspace, reloaded).
7. **Report tab.** Choose the defect code and edit the summary. To show spell check, type a misspelling such as
   "scorring". Add report photos from the photo list.
8. **Submit report.** Tap **SUBMIT REPORT** (scroll the right pane if needed). The progress list runs; the spell
   check offers "Fix: scoring" → tap it → **Continue**. The report is built.
9. **PDF preview.** "Page 1 of N": disposition letter, RMA report page (header, parts table, DIAGNOSIS, summary),
   then photo pages. Swipe through, then **Done**. The status now reads **Report Complete**.
10. **Re-submit (optional).** Submitting again shows "Report already complete"; **Update report** keeps the status.

**Shortcut:** open **912369** (finished example): the report, 7 photos and the built PDF are already there.

## 3. Which RMA to use

| RMA | Use it for |
|---|---|
| 912361 | The main walk-through: Recip, Warranty, misspellings ("Compresser", "scorring", "Bearring") for the spell-check moment |
| 912364 | Screw, 41 days old — shows aging on the work list |
| 912362 | Core-teardown badge |
| 912368 | **Scroll — read-only** ("Handled in the repair database") |
| 912369 | **Finished example**: report already built, 7 photos, open the PDF straight away |

## 4. Not in this demo (coming later)

Offline upload queue and conflict screens, lock handling beyond a message, the real BITZER server (Macola and the G:
photo folders), and SAP. The camera scanner and the photo camera have not been run on a real tablet yet; test both before the demo.

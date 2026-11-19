# Real-tablet camera test (about 20 minutes)

Run on the **tablet**, then repeat steps 1, 2, 5 and 8 on the **phone**. Use the mock server and demo data
(see `docs/demo.md`): RMA **912361**. You need something to scan: print or show on a screen a QR code that says
`912361`, a barcode of `912361`, and any label with a number on it (a fake serial plate is fine).

For every step that does not match "Expect", take a **screenshot**, and report: **step number + screenshot + one line**
(what you did, what happened). Note the tablet model and Android version once.

1. **Barcode / QR search.** Home -> camera icon in the search box. If asked, tap **Allow camera** -> **While using
   the app**. Expect: live picture, "Barcode / QR" selected. Point at the QR code. Expect: green frame, "Found
   912361", then the search runs and RMA 912361 opens or is listed.
2. **Linked QR.** Scan a QR holding a web link ending in `/912361` or `?rma=912361`. Expect: the search uses `912361`,
   not the whole link.
3. **Plate text.** Camera icon -> **Serial plate text** -> frame the label -> **Capture**. Expect: "Reading...", then a
   list of lines with digits; tap one. The search box shows it and searches. A blank wall should say "Nothing
   readable found" with **Try again**.
4. **Search camera rotation.** With the camera open, turn the tablet to portrait and back. Expect: the picture
   stays live, the chosen mode stays selected, no crash.
5. **Photo camera, Photos tab.** Open 912361 -> **Photos** tab -> take a part-row photo. Expect: full-screen camera,
   shutter button, "1 saved" after the first shot; the camera stays open for a second shot; **Done** closes it; both
   photos show as thumbnails with the right way up (not sideways) in portrait and landscape.
6. **Retake.** Open a photo -> **Retake**. Expect: camera opens, closes itself after one shot, the new photo replaces
   the old one.
7. **Guided inspection photo.** **Start guided inspection** -> take the **Overall** photo -> **Next**. Expect: photo
   saved for that step, step counter advances; **Exit** then the button reads "Continue (step x of y)".
8. **Permission denied (photo camera).** Android Settings -> Apps -> BITZER Inspect -> Permissions -> Camera ->
   **Don't allow**. Reopen the app, Photos tab, take a photo. Expect: "The app needs the camera..." with **Allow
   camera**. Tap it and **Don't allow** (twice if asked). Expect: "Camera permission was denied..." and **Open app
   settings**. Tap it, turn Camera on, press Back. Expect: the camera starts by itself.
9. **Permission denied (search camera).** Turn Camera off again and tap the search camera icon. Expect: "Camera
   access needed" -> after denying, "Camera access is turned off" with **Open app settings**; same recovery.
10. **Camera busy.** Open the tablet's own Camera app, leave it open, switch to BITZER Inspect, open a camera. Expect:
    a clear message ("camera couldn't be started...") with **Try again** (search) or a red message (photo), not a
    blank screen. Close the other camera app, tap **Try again**: the picture appears.
11. **Photo rotation and app switch.** Photo camera open: rotate, then press Home and return. Expect: camera still
    open, the count ("N saved") kept, no crash.
12. **Low storage (only if easy).** With under 100 MB free, take a photo. Expect: "The tablet is almost out of
    storage..." message, no crash, earlier photos intact.

**Report back:** for each failed step the number, a screenshot, and what you saw. Also say: which steps passed
without any problem, tablet and phone model, and whether the picture looked mirrored or sideways anywhere.

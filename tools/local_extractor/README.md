# Local column model

This is a small multinomial naive Bayes classifier trained **from scratch** by
`train.py`, using only Python's standard library. It classifies header text;
it never generates numbers, dates, transactions, or arbitrary responses.
It runs in shared Kotlin, without a service, API key, model download, or network.

The header training set contains 316 authored Arabic/English header and negative
examples (up from 124). A separate three-class direction model uses 122 examples
of receipts, payments and ambiguous/nonfinancial descriptions. These are
synthetic examples, not customer statements. They do not
establish production accuracy or accuracy on unseen banks. High confidence and
vocabulary coverage thresholds cause abstention; accepted predictions still
require explicit user selection in the preview. Existing deterministic parsing
and integer-baisa validation remain authoritative for transaction values.

Run `python tools/local_extractor/train.py` to reproduce LocalColumnWeights.kt
and LocalDirectionWeights.kt. The generated sources include training-data SHA256.
Training and inference
do not use internet access. Corpus changes require rerunning the shared tests.

`python tools/local_extractor/evaluate.py --output tools/local_extractor/header-evaluation.json`
evaluates the header classifier alone on 54 separately authored development
validation strings that do not exactly overlap training. Version 0.3.0 accepted
29 (27 correct, 2 wrong), abstaining on 25; the expanded model accepts 38
(38 correct, 0 wrong), abstaining on 16. This set informed development and is
not a blind final benchmark, an OCR benchmark, or proof of accuracy on banks.
The two trained models occupy about 163 KB together. Classifier posterior scores
are uncalibrated; UI never displays them as guaranteed accuracy percentages.

Explicit signed columns remain the primary direction evidence. Description rules
handle received payments, withdrawals, refunds, debit/credit reversals and failed
or pending operations. The direction model is only a fallback suggestion.
Description/model/balance suggestions, conflicting evidence, weak OCR and OCR
retry disagreement require individual confirmation. The core import method
enforces this requirement, independently of the select-all button.
The raw direction classifier can confuse reversals; those descriptions bypass
learned fallback and use explicit contextual rules or abstain. A conservative
classifier can also abstain on a clear short phrase handled by the rules.

The confirmation form lets the user correct date, description, amount and in/out
direction. Core validation rejects invalid dates, non-positive/over-precision
amounts and unknown directions. Duplicate detection is recalculated after edits;
source-row identity is preserved to prevent re-importing a corrected row.

Consecutive balances are checked only within a page when date order establishes
ascending or descending chronology. A mismatch is a review warning: omitted rows,
fees, unusual statement order and OCR errors can also cause mismatches. Same-day
only or mixed-order pages do not establish ordering. Balance differences never
create transaction amounts. Any inferred direction still requires confirmation.

Before claiming bank-independent accuracy, create a separately labelled test set
with entire unseen banks/layouts held out. Measure exact amount, date, direction,
currency and row recall separately; include blurred scans, missing headers,
RTL, cheque numbers, balances, duplicates and ambiguous transfers. Report accepted
accuracy together with abstention rate. Unit-test counts are not OCR accuracy.

## OCR and privacy

Android OCR uses Tesseract4Android 4.9.0 (Apache-2.0) and bundled tessdata_fast
Arabic/English LSTM weights. These OCR weights are pretrained open-source weights;
they are distinct from our own header model. Source revision, sizes, SHA256 and
the data licence are stored with `androidApp/src/main/assets/ocr/`.
`scripts/setup-ocr-assets.ps1` is a developer-only fetch step. The app never invokes
it or downloads files. The installed APK must contain both language models.

Text PDFs retain direct text extraction. Image-only pages use Android's PdfRenderer
through a seekable in-memory proxy file descriptor. No plaintext PDF/photo/OCR
output is written to disk. The only new plaintext files are public language weights
in the app's no-backup private directory. Ledger writes still use the existing
AES-GCM store. The application has no INTERNET permission, analytics or remote OCR.

Initial resource bounds: 20 MiB input, 2.5 million decoded pixels per image,
one OCR engine and one rendered page at a time, up to 10 scanned pages per import.
OCR first uses sparse text layout. If no complete, sufficiently confident rows
are found, a bounded grayscale/contrast copy is retried with automatic layout.
The better structured result is kept; disagreements require review. Recognition
does not replace letters with financial digits. OCR weights themselves have not
been retrained on new customer images. Both passes use the same bundled weights.

OCR rows are not selected automatically; weak date/amount/direction recognition
blocks a row until the user corrects and explicitly confirms it. Temporary byte
buffers are zeroed on close, including failures. A source-buffer lease defers
erasure during cancellation until the native reader releases it, avoiding
corrupting an in-flight PDF render. The chosen original file is
opened read-only and never deleted; no URI, PDF, image, password or full-page
OCR text is retained in the ledger or for training. Only confirmed extracted
transaction fields and a file hash/row identity for deduplication are saved.
OCR confidence is a heuristic, not a calibrated guarantee.
No physical-device speed, memory, OCR-accuracy or airplane-mode test is implied
by a successful JVM test or APK build. These need device validation.

Version 0.6 adds independent arithmetic using explicitly labelled opening/closing
balances and debit/credit totals. It also checks adjacent balances across pages
when dates establish ordering. All movements, including transfers, contribute to
statement reconciliation before ledger reporting excludes internal transfers.
Conflicting/ambiguous summary values abstain. Missing data never proves a complete
match. A mismatch, uncertain OCR summary or checked incomplete statement requires
an additional acknowledgement enforced by core import; any row correction resets
it and recomputes the audit. Matching arithmetic alone is not proof of row accuracy.

Android detects hybrid PDF pages with a substantial image and no readable numeric
transaction rows, even if text headers exist. Weak/small-text OCR can use overlapping
high-resolution strips from the original PDF or region-decoded image, up to 2400px
wide, 2.5M pixels per working bitmap and 12 strips per page. Excessively long pages
keep the existing bounded reading rather than returning an incomplete strip set.
Coordinates are merged; overlap words are deduplicated and conflicting readings
require review. The OCR weights remain unchanged. Native OCR accuracy/performance
on these strips has not been measured on a device.

The source is retained in RAM during review to render original date/amount/balance
crops from normalized transient coordinates. A serialized crop lease prevents
concurrent native readers; close defers byte erasure until a read finishes. Android
clears displayed crop pixels when replaced/closed. Success, cancellation or screen
disposal releases the review source; failed saving allows correction/retry without
writing a source file. iOS retains an in-memory PDFDocument until review ends and
releases its document/image references. No source, crop, password, full-page text,
summary or geometry is saved to the ledger. The original selected file is read-only.

iOS receives the shared header classifier and review flags. Native image/scanned
PDF OCR on iOS has not been added; the existing iOS importer remains text-PDF only.

Sources: https://github.com/adaptech-cz/Tesseract4Android and
https://github.com/tesseract-ocr/tessdata_fast .

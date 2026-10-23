# Local column model

This is a small multinomial naive Bayes classifier trained **from scratch** by
`train.py`, using only Python's standard library. It classifies header text;
it never generates numbers, dates, transactions, or arbitrary responses.
It runs in shared Kotlin, without a service, API key, model download, or network.

The initial training set contains 124 authored Arabic/English header and negative
examples. These are synthetic examples, not customer statements. They do not
establish production accuracy or accuracy on unseen banks. High confidence and
vocabulary coverage thresholds cause abstention; accepted predictions still
require explicit user selection in the preview. Existing deterministic parsing
and integer-baisa validation remain authoritative for transaction values.

Run `python tools/local_extractor/train.py` to reproduce LocalColumnWeights.kt.
The generated source includes the training-data SHA256. Training and inference
do not use internet access. Corpus changes require rerunning the shared tests.

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
OCR rows are not selected automatically; weak date/amount/direction recognition
blocks a row. OCR confidence is a heuristic, not a calibrated guarantee.
No physical-device speed, memory, OCR-accuracy or airplane-mode test is implied
by a successful JVM test or APK build. These need device validation.

iOS receives the shared header classifier and review flags. Native image/scanned
PDF OCR on iOS has not been added; the existing iOS importer remains text-PDF only.

Sources: https://github.com/adaptech-cz/Tesseract4Android and
https://github.com/tesseract-ocr/tessdata_fast .

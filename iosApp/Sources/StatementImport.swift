import SwiftUI
import UniformTypeIdentifiers
import PDFKit
import UIKit
import CryptoKit
import ExpenseCore

// Only this bounded, transient layout crosses into the shared parser. Neither the PDF nor
// its password is written to the ledger, a temporary file, or a network service.
private struct StatementLayout: Encodable {
    let version = 1
    let pages: [StatementPageLayout]
}
private struct StatementPageLayout: Encodable {
    let width: Double
    let height: Double
    let words: [StatementWordLayout]
}
private struct StatementWordLayout: Encodable {
    let text: String
    let x: Double
    let y: Double
    let width: Double
    let height: Double
}
private struct StatementPreview: Decodable {
    let documentId: String
    let rows: [StatementPreviewRow]
    let currency: String
    let skippedRows: Int
    let warnings: [String]
    let bankName: String?
    let summary: StatementSummaryPreview?
    let audit: StatementAuditPreview?
}
private struct StatementSummaryPreview: Decodable { let openingBaisa: Int64?; let closingBaisa: Int64? }
private struct StatementAuditPreview: Decodable {
    let status: String; let outgoingBaisa: Int64; let incomingBaisa: Int64
    let calculatedClosingBaisa: Int64?; let checks: Int; let requiresConfirmation: Bool; let warnings: [String]
}
private struct StatementPreviewRegion: Decodable { let x: Double; let y: Double; let width: Double; let height: Double }
private struct StatementPreviewField: Decodable { let name: String; let region: StatementPreviewRegion; let readingConfidence: Double }
private struct StatementPreviewRow: Decodable, Identifiable {
    let id: String
    let page: Int
    let dateText: String
    let occurredAtMillis: Int64?
    let description: String
    let amountBaisa: Int64?
    let direction: String?
    let effect: String
    let categoryId: String?
    let duplicateOf: String?
    let warnings: [String]
    let canImport: Bool
    let reviewRequired: Bool?
    let userConfirmed: Bool?
    let datePrecision: String?
    let region: StatementPreviewRegion?
    let fields: [StatementPreviewField]?
}
private enum ExtractedStatement {
    case passwordNeeded(Data)
    case layout(String, String, PDFDocument)
}

private enum StatementPDFReader {
    static let byteLimit = 20_000_000
    static let characterLimit = 1_000_000
    static let wordLimit = 100_000

    static func read(_ url: URL) throws -> ExtractedStatement {
        let granted = url.startAccessingSecurityScopedResource()
        defer { if granted { url.stopAccessingSecurityScopedResource() } }
        if let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > byteLimit {
            throw AppError.message("حجم الكشف يتجاوز 20 ميجابايت. اختر كشفًا لفترة أقصر.")
        }
        let handle = try FileHandle(forReadingFrom: url)
        defer { try? handle.close() }
        var data = Data()
        while let chunk = try handle.read(upToCount: 65_536), !chunk.isEmpty {
            try Task.checkCancellation()
            guard data.count <= byteLimit - chunk.count else {
                throw AppError.message("حجم الكشف يتجاوز 20 ميجابايت. اختر كشفًا لفترة أقصر.")
            }
            data.append(chunk)
        }
        return try extract(data, password: nil)
    }

    static func extract(_ data: Data, password: String?) throws -> ExtractedStatement {
        try Task.checkCancellation()
        guard !data.isEmpty, data.count <= byteLimit, let document = PDFDocument(data: data) else {
            throw AppError.message("تعذر قراءة الملف. اختر كشف حساب PDF صالحًا صادرًا من البنك.")
        }
        if document.isLocked {
            guard let password else { return .passwordNeeded(data) }
            guard document.unlock(withPassword: password) else {
                throw AppError.message("كلمة مرور الكشف غير صحيحة. أعد المحاولة.")
            }
        }
        guard document.pageCount > 0, document.pageCount <= 50 else {
            throw AppError.message("يدعم الاستيراد حتى 50 صفحة في المرة. اختر كشفًا لفترة أقصر.")
        }
        var pages = [StatementPageLayout]()
        var characterCount = 0
        var wordCount = 0
        let tokens = try NSRegularExpression(pattern: "\\S+")
        for pageIndex in 0..<document.pageCount {
            try Task.checkCancellation()
            guard let page = document.page(at: pageIndex) else {
                throw AppError.message("تعذر قراءة إحدى صفحات الكشف.")
            }
            let box = page.bounds(for: .cropBox).standardized
            guard box.width.isFinite, box.height.isFinite, box.width > 0, box.height > 0,
                  box.width <= 20_000, box.height <= 20_000 else {
                throw AppError.message("أبعاد إحدى صفحات الكشف غير صالحة.")
            }
            guard page.numberOfCharacters <= characterLimit - characterCount else {
                throw AppError.message("نص الكشف طويل جدًا. اختر كشفًا لفترة أقصر.")
            }
            let text = (page.string ?? "") as NSString
            guard text.length <= characterLimit - characterCount else {
                throw AppError.message("نص الكشف طويل جدًا. اختر كشفًا لفترة أقصر.")
            }
            characterCount += text.length
            let length = min(text.length, page.numberOfCharacters)
            var words = [StatementWordLayout]()
            let rotation = ((page.rotation % 360) + 360) % 360
            let rotated = rotation == 90 || rotation == 270
            let displayBox = CGRect(x: 0, y: 0, width: rotated ? box.height : box.width,
                height: rotated ? box.width : box.height)
            for match in tokens.matches(in: text as String, range: NSRange(location: 0, length: length)) {
                try Task.checkCancellation()
                var fragment = ""
                var fragmentBounds = CGRect.null
                var previous = CGRect.null
                func appendFragment() throws {
                    guard !fragment.isEmpty, !fragmentBounds.isNull else {
                        fragment = ""; fragmentBounds = .null; return
                    }
                    guard wordCount < wordLimit else {
                        throw AppError.message("عدد كلمات الكشف كبير جدًا. اختر كشفًا لفترة أقصر.")
                    }
                    words.append(StatementWordLayout(text: fragment,
                        x: Double(fragmentBounds.minX), y: Double(fragmentBounds.minY),
                        width: Double(fragmentBounds.width), height: Double(fragmentBounds.height)))
                    wordCount += 1
                    fragment = ""; fragmentBounds = .null
                }
                var characterIndex = match.range.location
                while characterIndex < NSMaxRange(match.range) {
                    if characterIndex % 1_024 == 0 { try Task.checkCancellation() }
                    let composed = text.rangeOfComposedCharacterSequence(at: characterIndex)
                    let end = min(NSMaxRange(composed), NSMaxRange(match.range))
                    var characterBox = CGRect.null
                    for index in characterIndex..<end {
                        let raw = page.characterBounds(at: index).standardized
                        if !raw.isNull, !raw.isInfinite, raw.minX.isFinite, raw.minY.isFinite,
                           raw.width.isFinite, raw.height.isFinite, raw.width > 0, raw.height > 0 {
                            characterBox = characterBox.union(raw)
                        }
                    }
                    let piece = text.substring(with: NSRange(location: characterIndex, length: end - characterIndex))
                    characterIndex = end
                    guard !characterBox.isNull else { continue }
                    let rect = topLeft(characterBox, box: box, rotation: rotation).intersection(displayBox)
                    guard !rect.isNull, rect.width > 0, rect.height > 0 else { continue }
                    // Some generators omit whitespace between adjacent table cells. Break
                    // those fragments geometrically without reversing Arabic logical text.
                    if !previous.isNull {
                        let height = max(previous.height, rect.height)
                        let verticalGap = abs(previous.midY - rect.midY)
                        let horizontalGap = max(previous.minX - rect.maxX, rect.minX - previous.maxX)
                        if verticalGap > height * 0.65 || horizontalGap > max(4, height * 1.5) {
                            try appendFragment()
                        }
                    }
                    fragment += piece
                    fragmentBounds = fragmentBounds.union(rect)
                    previous = rect
                }
                try appendFragment()
            }
            pages.append(StatementPageLayout(width: Double(rotated ? box.height : box.width),
                height: Double(rotated ? box.width : box.height), words: words))
        }
        guard wordCount > 0 else {
            throw AppError.message("الكشف لا يحتوي نصًا قابلًا للاستخراج. هذه الميزة تقرأ PDF نصّيًا صادرًا من البنك؛ الصور والملفات الممسوحة ضوئيًا تحتاج نسخة نصّية.")
        }
        let documentId = SHA256.hash(data: data).map { String(format: "%02x", $0) }.joined()
        return .layout(try jsonString(StatementLayout(pages: pages)), documentId, document)
    }

    // PDFKit bounds use a lower-left origin. The common parser uses display-space
    // top-left coordinates, including pages carrying a /Rotate entry.
    private static func topLeft(_ rect: CGRect, box: CGRect, rotation: Int) -> CGRect {
        let x = rect.minX - box.minX
        let y = box.maxY - rect.maxY
        switch rotation {
        case 90: return CGRect(x: box.height - y - rect.height, y: x, width: rect.height, height: rect.width)
        case 180: return CGRect(x: box.width - x - rect.width, y: box.height - y - rect.height, width: rect.width, height: rect.height)
        case 270: return CGRect(x: y, y: box.width - x - rect.width, width: rect.height, height: rect.width)
        default: return CGRect(x: x, y: y, width: rect.width, height: rect.height)
        }
    }
}

@MainActor private final class StatementImportModel: ObservableObject {
    @Published private(set) var busy = false
    @Published private(set) var needsPassword = false
    @Published private(set) var preview: StatementPreview?
    @Published private(set) var result: LedgerImport?
    @Published var selected = Set<String>()
    @Published var currencyConfirmed = false
    @Published var error: String?
    @Published var bankName = ""
    @Published var auditAcknowledged = false
    private var reviewDocument: PDFDocument?
    private var encryptedData: Data?
    private var previewJson: String?
    private var task: Task<Void, Never>?
    private var generation = UUID()

    func load(_ url: URL, store: LedgerStore) {
        reset()
        start(store: store) { try StatementPDFReader.read(url) }
    }
    func unlock(_ password: String, store: LedgerStore) {
        guard !password.isEmpty, let encryptedData else { return }
        start(store: store) { try StatementPDFReader.extract(encryptedData, password: password) }
    }
    private func start(store: LedgerStore, operation: @escaping () throws -> ExtractedStatement) {
        busy = true; error = nil
        let token = generation
        task = Task {
            do {
                let extraction = Task.detached(priority: .userInitiated, operation: operation)
                let extracted = try await withTaskCancellationHandler {
                    try await extraction.value
                } onCancel: { extraction.cancel() }
                try Task.checkCancellation()
                guard generation == token else { return }
                switch extracted {
                case .passwordNeeded(let data):
                    encryptedData = data; needsPassword = true
                case .layout(let documentJson, let documentId, let document):
                    clearSource(); needsPassword = false
                    reviewDocument = document
                    let snapshot = try store.snapshot()
                    let received = millis()
                    let analysis = Task.detached(priority: .userInitiated) {
                        try Task.checkCancellation()
                        let reader = try LedgerEngine(snapshot: snapshot)
                        return try reader.previewStatement(documentJson: documentJson, documentId: documentId, receivedAtMillis: received)
                    }
                    let json = try await withTaskCancellationHandler {
                        try await analysis.value
                    } onCancel: { analysis.cancel() }
                    try Task.checkCancellation()
                    guard generation == token else { return }
                    let decoded = try decode(json, as: StatementPreview.self)
                    previewJson = json; preview = decoded
                    bankName = decoded.bankName == "غير محدد" ? "" : decoded.bankName ?? ""
                    selected = ["OMR", "UNKNOWN"].contains(decoded.currency)
                        ? Set(decoded.rows.filter { $0.canImport && $0.reviewRequired != true && $0.duplicateOf == nil }.map(\.id)) : []
                }
            } catch is CancellationError {
                // Closing the sheet is an ordinary cancellation; no file is retained.
            } catch {
                guard generation == token else { return }
                clearSource(); reviewDocument = nil; needsPassword = false
                self.error = (error as? AppError)?.errorDescription ?? "تعذر تحليل الكشف. تأكد من أنه PDF نصّي صالح، ثم حاول مجددًا."
            }
            if generation == token { busy = false }
        }
    }
    var currencySupported: Bool { preview.map { ["OMR", "UNKNOWN"].contains($0.currency) } ?? false }
    var canImport: Bool {
        !busy && result == nil && currencySupported && currencyConfirmed && BankKnowledge.shared.key(value: bankName) != nil && !selected.isEmpty &&
        (preview?.audit?.requiresConfirmation != true || auditAcknowledged) &&
        (preview?.rows.filter { selected.contains($0.id) }.allSatisfy { $0.canImport && ($0.reviewRequired != true || $0.userConfirmed == true) } ?? false)
    }
    func confirm(_ row: StatementPreviewRow, date: String, description: String, amount: String, direction: String, store: LedgerStore) -> String? {
        guard let previewJson else { return "اختر الكشف مجددًا" }
        do {
            let reader = try LedgerEngine(snapshot: store.snapshot())
            let json = try reader.confirmStatementRow(previewJson: previewJson, rowId: row.id, dateText: date,
                description: description, amountText: amount, direction: direction)
            let updated = try decode(json, as: StatementPreview.self)
            self.previewJson = json; preview = updated; selected.insert(row.id)
            auditAcknowledged = false; currencyConfirmed = false
            return nil
        } catch { return "تحقق من التاريخ والوصف والمبلغ الموجب والاتجاه قبل التأكيد" }
    }
    private func clearSource() {
        if let count = encryptedData?.count { encryptedData?.resetBytes(in: 0..<count) }
        encryptedData = nil
    }
    func importSelected(store: LedgerStore) {
        guard canImport, let previewJson else { return }
        do {
            let rowIds = try jsonString(selected.sorted())
            let received = millis()
            let imported: LedgerImport = try store.change {
                let checked = auditAcknowledged ? try $0.acknowledgeStatementAudit(previewJson: previewJson) : previewJson
                let configured = try $0.configureStatementBank(previewJson: checked, bankName: bankName, ownedAccount: true)
                return try decode($0.importStatement(previewJson: configured, selectedRowIdsJson: rowIds,
                    receivedAtMillis: received, currencyConfirmed: currencyConfirmed), as: LedgerImport.self)
            }
            result = imported
            self.previewJson = nil; preview = nil; selected = []; clearSource(); reviewDocument = nil
            currencyConfirmed = false
            Task { await Notifications.shared.flush() }
        } catch {
            self.error = (error as? AppError)?.errorDescription ?? "تعذر حفظ العمليات. لم تُستبدل بياناتك الحالية."
        }
    }
    func reset() {
        generation = UUID(); task?.cancel(); task = nil
        clearSource(); previewJson = nil; preview = nil; result = nil
        selected = []; currencyConfirmed = false; needsPassword = false; busy = false; error = nil
        bankName = ""; auditAcknowledged = false; reviewDocument = nil
    }
    func total(direction: String) -> Int64 {
        (preview?.rows ?? []).filter { selected.contains($0.id) && $0.direction == direction }
            .reduce(0) { total, row in
                let sum = total.addingReportingOverflow(row.amountBaisa ?? 0)
                return sum.overflow ? total : sum.partialValue
            }
    }
    var pendingRows: [StatementPreviewRow] { (preview?.rows ?? []).filter { !$0.canImport || $0.reviewRequired == true && $0.userConfirmed != true } }
    func originalCrop(_ row: StatementPreviewRow, focus: StatementPreviewField?) -> UIImage? {
        guard let region = focus?.region ?? row.region, let page = reviewDocument?.page(at: row.page - 1), let source = page.pageRef else { return nil }
        let values = [region.x, region.y, region.width, region.height]
        guard values.allSatisfy({ $0.isFinite }), region.x >= 0, region.y >= 0, region.width > 0, region.height > 0,
              region.x + region.width <= 1.000001, region.y + region.height <= 1.000001 else { return nil }
        let left = max(0, region.x - 0.012), top = max(0, region.y - 0.006)
        let width = min(1, region.x + region.width + 0.012) - left
        let height = min(1, region.y + region.height + 0.006) - top
        let bounds = page.bounds(for: .cropBox)
        let sideways = page.rotation % 180 != 0
        let pageWidth = Double(sideways ? bounds.height : bounds.width)
        let pageHeight = Double(sideways ? bounds.width : bounds.height)
        let ratio = height * pageHeight / (width * pageWidth)
        let pixelsWide = min(1200.0, sqrt(2_500_000 / ratio))
        let pixelsHigh = max(1, floor(pixelsWide * ratio))
        let format = UIGraphicsImageRendererFormat(); format.scale = 1; format.opaque = true
        return UIGraphicsImageRenderer(size: CGSize(width: pixelsWide, height: pixelsHigh), format: format).image { output in
            UIColor.white.setFill(); output.fill(CGRect(x: 0, y: 0, width: pixelsWide, height: pixelsHigh))
            let context = output.cgContext
            context.translateBy(x: 0, y: pixelsHigh); context.scaleBy(x: 1, y: -1)
            let fullWidth = pixelsWide / width, fullHeight = fullWidth * pageHeight / pageWidth
            let destination = CGRect(x: -left * fullWidth, y: -(1 - top - height) * fullHeight, width: fullWidth, height: fullHeight)
            context.concatenate(source.getDrawingTransform(.cropBox, rect: destination, rotate: 0, preserveAspectRatio: true))
            context.drawPDFPage(source)
        }
    }
}

@MainActor struct StatementImportView: View {
    @ObservedObject var store: LedgerStore
    @StateObject private var model = StatementImportModel()
    @Environment(\.dismiss) private var dismiss
    @State private var choosingFile = false
    @State private var password = ""
    @State private var editing: StatementPreviewRow?
    @State private var onlyReview = true
    let completed: (String) -> Void

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Text("اختر كشف حساب PDF نصّيًا صادرًا من تطبيق البنك. يُقرأ على جهازك، وتراجع العمليات قبل إضافتها. لا يُحفظ الملف ولا كلمة مروره داخل التطبيق.")
                        .font(.subheadline).foregroundStyle(.secondary)
                    if !model.busy && model.result == nil {
                        Button { password = ""; choosingFile = true } label: {
                            Label(model.preview == nil ? "اختيار كشف PDF" : "اختيار كشف آخر", systemImage: "doc.badge.plus")
                        }
                    }
                    Text("حتى 20 ميجابايت و50 صفحة. الصور الممسوحة ضوئيًا غير مدعومة حاليًا.").font(.caption).foregroundStyle(.secondary)
                }
                if model.busy { Section { ProgressView("قراءة الكشف وتحليل العمليات…") } }
                if model.needsPassword && !model.busy {
                    Section("الكشف محمي بكلمة مرور") {
                        SecureField("كلمة مرور PDF", text: $password).textContentType(.password)
                        Button("فتح الكشف") {
                            let value = password; password = ""; model.unlock(value, store: store)
                        }.disabled(password.isEmpty)
                        Text("تُستخدم كلمة المرور لفتح هذا الملف فقط، ولا تُخزن.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                if let error = model.error { Section { Text(error).foregroundStyle(.red) } }
                if let preview = model.preview { previewSections(preview) }
                if let result = model.result {
                    Section("اكتمل الاستيراد") {
                        Label("أضيفت \(result.ids.count) عملية", systemImage: "checkmark.circle.fill")
                        if result.ignored > 0 { Text("تم تجاوز \(result.ignored) صفًا") }
                        if result.duplicates > 0 { Text("عمليات مكررة أو محتملة التكرار: \(result.duplicates)") }
                        Text("يمكن مراجعة الفئات والتحويلات من سجل العمليات.").font(.subheadline)
                        ForEach(Array(result.reasons.enumerated()), id: \.offset) { _, reason in Text(reason).font(.caption) }
                        Button("عرض العمليات") { completed("أضيفت \(result.ids.count) عملية من كشف الحساب"); dismiss() }
                    }
                }
            }
            .navigationTitle("استيراد كشف حساب")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("إغلاق") { model.reset(); password = ""; dismiss() } } }
            .fileImporter(isPresented: $choosingFile, allowedContentTypes: [.pdf]) { result in
                switch result {
                case .success(let url): model.load(url, store: store)
                case .failure(let error):
                    if (error as NSError).code != NSUserCancelledError { model.error = "تعذر فتح الملف المختار. أعد اختياره من تطبيق الملفات." }
                }
            }
        }
        .onAppear { store.modalDepth += 1 }
        .onChange(of: model.preview?.documentId) { _, _ in onlyReview = !model.pendingRows.isEmpty }
        .onDisappear { store.modalDepth = max(0, store.modalDepth - 1); model.reset(); password = "" }
        .sheet(item: $editing) { row in
            StatementRowConfirmationView(row: row, hasNext: model.pendingRows.contains { $0.id != row.id }, originalCrop: model.originalCrop) { date, description, amount, direction in
                let error = model.confirm(row, date: date, description: description, amount: amount, direction: direction, store: store)
                if error == nil { editing = model.pendingRows.first }
                return error
            }.id(row.id)
        }
    }

    @ViewBuilder private func previewSections(_ preview: StatementPreview) -> some View {
        Section("راجع قبل الإضافة") {
            Text("الخطوة 2 من 3 — \(preview.rows.count) عملية • \(model.pendingRows.count) تحتاج مراجعة").font(.headline)
            TextField("البنك المستخدم في هذا الكشف", text: $model.bankName)
                .onChange(of: model.bankName) { _, _ in model.currencyConfirmed = false; model.auditAcknowledged = false }
            Text("MTHQ = ميثاق، NIZWA = نزوى. يُحفظ البنك لكل عملية. مطابقة التحويلات تحتاج ساعة موثوقة خلال خمس دقائق.").font(.caption)
            Text("حدد العمليات التي تريد إضافتها. العمليات غير الواضحة لا تُختار تلقائيًا؛ ويمكن مراجعة التحويلات بعد الاستيراد.").font(.subheadline)
            if model.currencySupported {
                Text("المحدد: \(model.selected.count) • صادر: \(baisa(model.total(direction: "out"))) ر.ع • وارد: \(baisa(model.total(direction: "in"))) ر.ع")
                    .font(.subheadline.bold())
            }
            if preview.skippedRows > 0 { Text("تجاوز التحليل \(preview.skippedRows) صفًا غير قابل للاستيراد.").font(.caption) }
            ForEach(Array(preview.warnings.enumerated()), id: \.offset) { _, warning in Text(warning).font(.caption).foregroundStyle(.orange) }
            if preview.rows.isEmpty { Text("لم نجد جدول عمليات واضحًا. لم تُضف أي بيانات. جرّب كشفًا آخر صادرًا من البنك.").foregroundStyle(.orange) }
            if !model.currencySupported { Text("عملة الكشف \(preview.currency). يدعم الاستيراد حاليًا المبالغ بالريال العُماني فقط.").foregroundStyle(.red) }
        }
        if let audit = preview.audit {
            Section("تدقيق الأرصدة والإجماليات") {
                Text(audit.status == "matched" ? "تطابقت الأرقام في الفحوص المتاحة" : audit.status == "mismatch" ? "يوجد فرق يحتاج المراجعة" : audit.status == "incomplete" ? "المطابقة غير مكتملة" : "لا توجد أرصدة كافية للمطابقة")
                if let opening = preview.summary?.openingBaisa { Text("الافتتاحي: \(baisa(opening)) • الداخل: \(baisa(audit.incomingBaisa)) • الخارج: \(baisa(audit.outgoingBaisa))") }
                if let closing = preview.summary?.closingBaisa { Text("الختامي المطبوع: \(baisa(closing)) • المحسوب: \(audit.calculatedClosingBaisa.map(baisa) ?? "غير متاح")") }
                Text("\(audit.checks) فحص لكل حركات الكشف، ومنها التحويلات. التطابق الحسابي وحده لا يثبت صحة جميع الصفوف.").font(.caption)
                ForEach(Array(audit.warnings.enumerated()), id: \.offset) { _, warning in Text(warning).font(.caption).foregroundStyle(.orange) }
            }
        }
        Section("العمليات المستخرجة") {
            Toggle("عرض العمليات التي تحتاج مراجعة فقط", isOn: $onlyReview)
            if !model.pendingRows.isEmpty { Button("مراجعة العمليات بالتتابع") { editing = model.pendingRows.first } }
            ForEach(onlyReview ? model.pendingRows : preview.rows) { row in
                Toggle(isOn: Binding(get: { model.selected.contains(row.id) }, set: { checked in
                    if checked && (!row.canImport || row.reviewRequired == true && row.userConfirmed != true) { editing = row }
                    else if checked { model.selected.insert(row.id) } else { model.selected.remove(row.id) }
                })) { rowView(row, currency: preview.currency) }.disabled(model.busy || !model.currencySupported)
                Button(row.userConfirmed == true ? "تعديل البيانات المؤكدة" : "مراجعة وتصحيح وتأكيد") { editing = row }
                    .disabled(model.busy || !model.currencySupported)
            }
        }
        Section {
            if preview.audit?.requiresConfirmation == true {
                Toggle("راجعت تنبيهات الأرصدة والإجماليات وأؤكد إضافة المحدد", isOn: $model.auditAcknowledged)
            }
            Toggle("أؤكد أن الكشف لحسابي ومبالغه بالريال العُماني وقد راجعت العمليات", isOn: $model.currencyConfirmed)
                .disabled(!model.currencySupported)
            Button("إضافة \(model.selected.count) عملية") { model.importSelected(store: store) }
                .disabled(!model.canImport)
            Text("احذف تحديد التكرار المحتمل إلا إذا تأكدت أنها عملية مستقلة. التحويل غير المحسوم يظل للمراجعة ولا يُحسب كمصروف تلقائيًا.")
                .font(.caption).foregroundStyle(.secondary)
        }
    }

    private func rowView(_ row: StatementPreviewRow, currency: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Text(row.description.isEmpty ? "عملية من كشف الحساب" : row.description).font(.subheadline.bold())
            Text("\(row.dateText) • صفحة \(row.page)").font(.caption).foregroundStyle(.secondary)
            if row.datePrecision == "day" { Text("الساعة غير موجودة؛ لا تُجرى مطابقة خمس دقائق لهذا الصف").font(.caption) }
            Text("\(row.direction == "in" ? "داخل: إيداع أو مستلم" : row.direction == "out" ? "خارج: دفع أو سحب" : "الاتجاه غير محدد"): \(row.amountBaisa.map(baisa) ?? "مبلغ غير واضح") \(["OMR", "UNKNOWN"].contains(currency) ? "ر.ع" : currency)")
                .font(.subheadline)
            if let category = store.state.categories.first(where: { $0.id == row.categoryId }) {
                Text("الفئة المقترحة: \(category.name)").font(.caption).foregroundStyle(.secondary)
            }
            if row.duplicateOf != nil { Text("تكرار محتمل — غير محدد تلقائيًا").font(.caption).foregroundStyle(.orange) }
            if row.reviewRequired == true && row.userConfirmed != true { Text("يحتاج تأكيدك قبل الحفظ").font(.caption).foregroundStyle(.orange) }
            if row.effect == "unresolved" { Text("نوع العملية يحتاج مراجعة بعد الإضافة").font(.caption).foregroundStyle(.orange) }
            ForEach(Array(row.warnings.enumerated()), id: \.offset) { _, warning in Text(warning).font(.caption).foregroundStyle(.orange) }
        }.padding(.vertical, 5)
    }
}

private struct StatementRowConfirmationView: View {
    let row: StatementPreviewRow
    let hasNext: Bool
    let originalCrop: (StatementPreviewRow, StatementPreviewField?) -> UIImage?
    let confirm: (String, String, String, String) -> String?
    @Environment(\.dismiss) private var dismiss
    @State private var date = ""
    @State private var description = ""
    @State private var amount = ""
    @State private var direction = ""
    @State private var checked = false
    @State private var error: String?
    var body: some View {
        NavigationStack {
            Form {
                StatementOriginalCropView(row: row, crop: originalCrop)
                Text("صحّح القيم بالرجوع إلى كشفك الأصلي. لا يُحفظ ملف الكشف داخل التطبيق.")
                TextField("التاريخ والوقت إن وُجد: 13/10/2026 10:30:00", text: $date)
                TextField("الوصف", text: $description)
                TextField("المبلغ بالريال العُماني", text: $amount).keyboardType(.decimalPad)
                Picker("اتجاه المبلغ", selection: $direction) {
                    Text("اختر الاتجاه").tag("")
                    Text("داخل: إيداع أو مستلم").tag("in")
                    Text("خارج: دفع أو سحب").tag("out")
                }
                Toggle("راجعت التاريخ والمبلغ والاتجاه وأؤكد صحتها", isOn: $checked)
                if let error { Text(error).foregroundStyle(.red) }
                Button(hasNext ? "تأكيد والتالي" : "تأكيد والعودة للملخص") {
                    error = confirm(date, description, amount, direction)
                    if error == nil && !hasNext { dismiss() }
                }.disabled(!checked || !["in", "out"].contains(direction))
            }
            .navigationTitle("تأكيد العملية")
            .toolbar { ToolbarItem(placement: .cancellationAction) { Button("إلغاء") { dismiss() } } }
            .onAppear { date=row.dateText; description=row.description; amount=row.amountBaisa.map(baisa) ?? ""; direction=row.direction ?? "" }
            .onChange(of: date) { _, _ in checked=false }
            .onChange(of: description) { _, _ in checked=false }
            .onChange(of: amount) { _, _ in checked=false }
            .onChange(of: direction) { _, _ in checked=false }
        }
    }
}

@MainActor private struct StatementOriginalCropView: View {
    let row: StatementPreviewRow
    let crop: (StatementPreviewRow, StatementPreviewField?) -> UIImage?
    @State private var focus = "amount"
    @State private var image: UIImage?
    var body: some View {
        Section("الخانة الأصلية — مؤقتة في الذاكرة") {
            Picker("الخانة", selection: $focus) {
                Text("الصف كاملًا").tag("row")
                ForEach(row.fields ?? [], id: \.name) { field in
                    Text(field.name == "amount" ? "المبلغ" : field.name == "date" ? "التاريخ" : field.name == "balance" ? "الرصيد" : "الاتجاه").tag(field.name)
                }
            }
            if let image { Image(uiImage: image).resizable().scaledToFit().frame(maxHeight: 220) }
            else { Text("المقتطف غير متاح؛ راجع ملفك الأصلي").font(.caption) }
        }
        .task(id: row.id + focus) { image = crop(row, row.fields?.first { $0.name == focus }) }
        .onDisappear { image = nil }
    }
}

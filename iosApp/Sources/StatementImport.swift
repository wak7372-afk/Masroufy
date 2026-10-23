import SwiftUI
import UniformTypeIdentifiers
import PDFKit
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
}
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
}
private enum ExtractedStatement {
    case passwordNeeded(Data)
    case layout(String, String)
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
        return .layout(try jsonString(StatementLayout(pages: pages)), documentId)
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
                case .layout(let documentJson, let documentId):
                    clearSource(); needsPassword = false
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
                    selected = ["OMR", "UNKNOWN"].contains(decoded.currency)
                        ? Set(decoded.rows.filter { $0.canImport && $0.reviewRequired != true && $0.duplicateOf == nil }.map(\.id)) : []
                }
            } catch is CancellationError {
                // Closing the sheet is an ordinary cancellation; no file is retained.
            } catch {
                guard generation == token else { return }
                clearSource(); needsPassword = false
                self.error = (error as? AppError)?.errorDescription ?? "تعذر تحليل الكشف. تأكد من أنه PDF نصّي صالح، ثم حاول مجددًا."
            }
            if generation == token { busy = false }
        }
    }
    var currencySupported: Bool { preview.map { ["OMR", "UNKNOWN"].contains($0.currency) } ?? false }
    var canImport: Bool {
        !busy && result == nil && currencySupported && currencyConfirmed && !selected.isEmpty &&
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
                try decode($0.importStatement(previewJson: previewJson, selectedRowIdsJson: rowIds,
                    receivedAtMillis: received, currencyConfirmed: currencyConfirmed), as: LedgerImport.self)
            }
            result = imported
            self.previewJson = nil; preview = nil; selected = []; clearSource()
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
    }
    func total(direction: String) -> Int64 {
        (preview?.rows ?? []).filter { selected.contains($0.id) && $0.direction == direction }
            .reduce(0) { total, row in
                let sum = total.addingReportingOverflow(row.amountBaisa ?? 0)
                return sum.overflow ? total : sum.partialValue
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
        .onDisappear { store.modalDepth = max(0, store.modalDepth - 1); model.reset(); password = "" }
        .sheet(item: $editing) { row in
            StatementRowConfirmationView(row: row) { date, description, amount, direction in
                model.confirm(row, date: date, description: description, amount: amount, direction: direction, store: store)
            }
        }
    }

    @ViewBuilder private func previewSections(_ preview: StatementPreview) -> some View {
        Section("راجع قبل الإضافة") {
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
        Section("العمليات المستخرجة") {
            ForEach(preview.rows) { row in
                Toggle(isOn: Binding(get: { model.selected.contains(row.id) }, set: { checked in
                    if checked && (!row.canImport || row.reviewRequired == true && row.userConfirmed != true) { editing = row }
                    else if checked { model.selected.insert(row.id) } else { model.selected.remove(row.id) }
                })) { rowView(row, currency: preview.currency) }.disabled(model.busy || !model.currencySupported)
                Button(row.userConfirmed == true ? "تعديل البيانات المؤكدة" : "مراجعة وتصحيح وتأكيد") { editing = row }
                    .disabled(model.busy || !model.currencySupported)
            }
        }
        Section {
            Toggle("أؤكد أن مبالغ العمليات المحددة بالريال العُماني وأنني راجعت المبالغ والتواريخ", isOn: $model.currencyConfirmed)
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
                Text("صحّح القيم بالرجوع إلى كشفك الأصلي. لا يُحفظ ملف الكشف داخل التطبيق.")
                TextField("التاريخ: يوم/شهر/سنة", text: $date)
                TextField("الوصف", text: $description)
                TextField("المبلغ بالريال العُماني", text: $amount).keyboardType(.decimalPad)
                Picker("اتجاه المبلغ", selection: $direction) {
                    Text("اختر الاتجاه").tag("")
                    Text("داخل: إيداع أو مستلم").tag("in")
                    Text("خارج: دفع أو سحب").tag("out")
                }
                Toggle("راجعت التاريخ والمبلغ والاتجاه وأؤكد صحتها", isOn: $checked)
                if let error { Text(error).foregroundStyle(.red) }
                Button("تأكيد واختيار العملية") {
                    error = confirm(date, description, amount, direction)
                    if error == nil { dismiss() }
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

import SwiftUI
import UniformTypeIdentifiers
import ExpenseCore

private let green = Color(red: 0.08, green: 0.36, blue: 0.27)
private let mint = Color(red: 0.9, green: 0.95, blue: 0.91)

@MainActor private struct LedgerSheetErrors: ViewModifier {
    @ObservedObject var store: LedgerStore
    @State private var depth = 0
    func body(content: Content) -> some View {
        content
            .onAppear { store.modalDepth += 1; depth = store.modalDepth }
            .onDisappear { store.modalDepth = max(0, store.modalDepth - 1) }
            .alert("تعذر تنفيذ العملية", isPresented: Binding(
                get: { store.error != nil && depth == store.modalDepth },
                set: { if !$0 { store.error = nil } })) {
                Button("حسنًا") { store.error = nil }
            } message: { Text(store.error ?? "") }
    }
}

@MainActor struct ContentView: View {
    @ObservedObject var store = LedgerStore.shared
    @State private var tab = 0
    @State private var paste = false
    @State private var statement = false
    @State private var editing: LedgerTransaction?
    @State private var adding = false
    @State private var info: String?
    @Environment(\.scenePhase) private var scenePhase
    var body: some View {
        TabView(selection: $tab) {
            NavigationStack { dashboard.toolbar { actions } }.tabItem { Label("الرئيسية", systemImage: "house") }.tag(0)
            NavigationStack { transactions.toolbar { actions } }.tabItem { Label("العمليات", systemImage: "list.bullet") }.tag(1)
            NavigationStack { BudgetView(store: store) }.tabItem { Label("الحدود", systemImage: "chart.pie") }.tag(2)
            NavigationStack { SettingsView(store: store) }.tabItem { Label("الإعدادات", systemImage: "gearshape") }.tag(3)
        }
        .sheet(isPresented: $paste) { PasteView(store: store) { info = $0; tab = 1 } }
        .sheet(isPresented: $statement) { StatementImportView(store: store) { info = $0; tab = 1 } }
        .sheet(isPresented: $adding) { EditView(store: store, transaction: nil) }
        .sheet(item: $editing) { EditView(store: store, transaction: $0) }
        .alert("مصروفي", isPresented: Binding(get: { (info != nil || store.error != nil) && store.modalDepth == 0 }, set: { if !$0 { info = nil; store.error = nil } })) {
            Button("حسنًا") { info = nil; store.error = nil }
        } message: { Text(info ?? store.error ?? "") }
        .onChange(of: store.selectedTransaction) { _, id in
            guard let id else { return }
            editing = store.state.transactions.first { $0.id == id }; store.selectedTransaction = nil
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { Task { await Notifications.shared.flush() } }
        }
        .task {
            if let id = store.selectedTransaction {
                editing = store.state.transactions.first { $0.id == id }; store.selectedTransaction = nil
            }
            await Notifications.shared.flush()
        }
    }
    @ToolbarContentBuilder var actions: some ToolbarContent {
        ToolbarItem(placement: .topBarTrailing) { Button { paste = true } label: { Image(systemName: "text.badge.plus") }.accessibilityLabel("إضافة رسالة") }
        ToolbarItem(placement: .topBarTrailing) { Button { statement = true } label: { Image(systemName: "doc.text") }.accessibilityLabel("استيراد كشف حساب PDF") }
        ToolbarItem(placement: .topBarLeading) { Button { adding = true } label: { Image(systemName: "plus") }.accessibilityLabel("عملية جديدة") }
    }
    var dashboard: some View {
        let report = store.report()
        return ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                Text("مصروفاتك، بصورة أوضح").font(.title2.bold())
                Button { statement = true } label: {
                    Label("استيراد كشف حساب PDF", systemImage: "doc.badge.plus")
                }.buttonStyle(.bordered)
                if store.loadFailed {
                    ContentUnavailableView("البيانات غير متاحة", systemImage: "lock.trianglebadge.exclamationmark",
                        description: Text("افتح قفل الجهاز ثم أعد المحاولة، أو استعد نسخة احتياطية من الإعدادات."))
                    Button("إعادة المحاولة") { store.perform { try store.reloadIfNeeded() } }.buttonStyle(.borderedProminent)
                } else {
                VStack(alignment: .leading, spacing: 12) {
                    Text("صافي الإنفاق — المبالغ الخارجة").opacity(0.8)
                    Text("\(baisa(report.netExpense)) ر.ع").font(.system(size: 34, weight: .bold))
                    Divider().overlay(.white.opacity(0.2))
                    HStack { Text("وارد \(baisa(report.incoming))"); Spacer(); Text("استرجاع \(baisa(report.refunds))") }.font(.subheadline)
                }.foregroundStyle(.white).padding(24).frame(maxWidth: .infinity, alignment: .leading).background(LinearGradient(colors: [green, .green.opacity(0.7)], startPoint: .topLeading, endPoint: .bottomTrailing), in: RoundedRectangle(cornerRadius: 24))
                if report.reviewCount > 0 {
                    Button { tab = 1 } label: {
                        VStack(alignment: .leading, spacing: 5) { Text("\(report.reviewCount) عمليات تحتاج مراجعة").bold(); Text("\(report.excludedCount) لا تدخل الإجمالي حتى تُحسم").font(.caption) }.frame(maxWidth: .infinity, alignment: .leading).padding().background(Color.yellow.opacity(0.15), in: RoundedRectangle(cornerRadius: 16))
                    }.buttonStyle(.plain)
                }
                if store.state.transactions.isEmpty {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("ابدأ برسالة واحدة").font(.title3.bold())
                        Text("نستخرج المبلغ والجهة ونقترح الفئة على جهازك. اسم البنك اختياري.").foregroundStyle(.secondary)
                        Button("تحليل رسالة") { paste = true }.buttonStyle(.borderedProminent)
                        Button("جرّب أمثلة توضيحية") { store.perform { try store.change { try $0.seedDemo(nowMillis: millis()) } } }
                    }.padding().frame(maxWidth: .infinity, alignment: .leading).background(.white, in: RoundedRectangle(cornerRadius: 20))
                }
                Text("أين يذهب إنفاقك؟").font(.title3.bold())
                ForEach(report.categories.filter { $0.monthlySpent != 0 || $0.monthlyLimit != nil || $0.weeklyLimit != nil }) { line in CategoryCard(line: line) }
                if report.unclassified != 0 { Text("غير مصنف: \(baisa(report.unclassified)) ر.ع — محسوب في الإجمالي").font(.caption).padding().background(mint, in: RoundedRectangle(cornerRadius: 12)) }
                Text("آخر العمليات").font(.title3.bold())
                ForEach(Array(store.state.transactions.prefix(4))) { transaction in Button { editing = transaction } label: { TransactionRow(transaction: transaction, state: store.state) }.buttonStyle(.plain) }
                }
            }.padding(22)
        }.background(Color(red: 0.96, green: 0.97, blue: 0.96)).navigationTitle("مصروفي").navigationBarTitleDisplayMode(.inline)
    }
    var transactions: some View { TransactionsView(store: store) { editing = $0 } }
}

@MainActor struct CategoryCard: View {
    let line: CategoryReport
    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack { Text("\(line.symbol)  \(line.name)").bold(); Spacer(); Text("\(baisa(line.monthlySpent)) ر.ع").foregroundStyle(green) }
            if let limit = line.monthlyLimit ?? line.weeklyLimit, limit > 0 {
                ProgressView(value: max(0, min(1, Double(line.monthlyLimit != nil ? line.monthlySpent : line.weeklySpent) / Double(limit))))
            }
            Text([line.monthlyLimit.map { "شهري: \(baisa($0))" }, line.weeklyLimit.map { "أسبوعي: \(baisa(line.weeklySpent)) / \(baisa($0))" }].compactMap { $0 }.joined(separator: " • ")).font(.caption).foregroundStyle(.secondary)
        }.padding(18).background(.white, in: RoundedRectangle(cornerRadius: 18))
    }
}
@MainActor struct TransactionRow: View {
    let transaction: LedgerTransaction; let state: LedgerData
    var body: some View {
        let category = state.categories.first { $0.id == transaction.categoryId }
        HStack(spacing: 12) {
            Text(category?.symbol ?? "↔").font(.title3).frame(width: 42, height: 42).background(mint, in: Circle())
            VStack(alignment: .leading, spacing: 4) {
                Text(transaction.counterparty ?? (transaction.type == "transfer" ? "تحويل" : transaction.direction == "in" ? "عملية واردة" : "عملية مالية")).font(.subheadline.bold()).lineLimit(1)
                Text(category?.name ?? "غير مصنف").font(.caption).foregroundStyle(.secondary)
                Text("\(transaction.bankName ?? "غير محدد")\(transaction.transferPairId == nil ? "" : " • تحويل بين حساباتي")").font(.caption).foregroundStyle(.secondary)
                Text(Date(timeIntervalSince1970: Double(transaction.occurredAtMillis) / 1000), format: .dateTime.day().month().year()).font(.caption2).foregroundStyle(.secondary)
                if !transaction.reviewed { Text(transaction.duplicateOf != nil ? "تكرار محتمل" : transaction.financialVerified ? "فئة مقترحة" : "غير محتسب حتى المراجعة").font(.caption2).foregroundStyle(.orange) }
                if transaction.isDemo { Text("مثال توضيحي").font(.caption2).foregroundStyle(.secondary) }
            }
            Spacer(minLength: 0)
            Text(transaction.accountingBaisa.map(baisa) ?? "راجع المبلغ").font(.subheadline.bold()).foregroundStyle(transaction.direction == "in" ? green : .primary)
        }.padding(16).background(.white, in: RoundedRectangle(cornerRadius: 18))
    }
}
@MainActor struct TransactionsView: View {
    @ObservedObject var store: LedgerStore; let edit: (LedgerTransaction) -> Void
    @State var search = ""; @State var reviews = false
    var body: some View {
        List {
            Toggle("تحتاج مراجعة فقط", isOn: $reviews)
            ForEach(store.state.transactions.filter { (!reviews || !$0.reviewed) && (search.isEmpty || "\($0.bankName ?? "") \($0.counterparty ?? "") \($0.note)".localizedCaseInsensitiveContains(search)) }.sorted { $0.occurredAtMillis > $1.occurredAtMillis }) { transaction in
                Button { edit(transaction) } label: { TransactionRow(transaction: transaction, state: store.state) }.buttonStyle(.plain).listRowSeparator(.hidden)
            }
        }.searchable(text: $search, prompt: "جهة أو سبب").navigationTitle("سجل العمليات")
    }
}
@MainActor struct PasteView: View {
    @ObservedObject var store: LedgerStore; let completed: (String) -> Void
    @Environment(\.dismiss) var dismiss; @State var text = ""
    @State private var bank = ""; @State private var owned = true
    var body: some View {
        NavigationStack {
            Form {
                Text("التحليل محلي، ولن يُحفظ نص الرسالة. يمكنك لصق عدة رسائل معاد إرسالها.").font(.subheadline).foregroundStyle(.secondary)
                TextEditor(text: $text).frame(minHeight: 230)
                TextField("البنك — تلقائي من النص إن تركته فارغًا", text: $bank)
                Toggle("هذا البنك ضمن حساباتي", isOn: $owned)
            }.navigationTitle("رسالة مصرفية").toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("إلغاء") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("تحليل") {
                    store.perform {
                        let result: LedgerImport = try store.change { engine in
                            let name = bank.isEmpty ? engine.detectBank(text: text) : bank
                            if BankKnowledge.shared.key(value: name) != nil { try engine.setOwnedBank(bankName: name, owned: owned) }
                            return try decode(engine.importBankMessage(text: text, receivedAtMillis: millis(), source: "paste", sourceId: UUID().uuidString, bankHint: name), as: LedgerImport.self)
                        }
                        dismiss(); completed(result.ids.isEmpty ? result.reasons.joined(separator: "\n") : "أضيفت \(result.ids.count) عملية للمراجعة")
                    }
                }.disabled(text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty) }
            }
        }.modifier(LedgerSheetErrors(store: store))
    }
}
@MainActor struct EditView: View {
    @ObservedObject var store: LedgerStore; let transaction: LedgerTransaction?
    @Environment(\.dismiss) var dismiss
    @State var amount = ""; @State var party = ""; @State var note = ""; @State var effect = "expense"
    @State var category = ""; @State var date = Date(); @State var remember = false; @State var deleting = false
    @State var original = ""; @State var splitting = false
    @State private var bank = ""; @State private var ownsBank = false
    @State private var categoryKeyword = ""; @State private var bankKeyword = ""
    @State private var automatic = true; @State private var timeKnown = true
    var currentTransaction: LedgerTransaction? {
        guard let transaction else { return nil }
        return store.state.transactions.first { $0.id == transaction.id } ?? transaction
    }
    var body: some View {
        NavigationStack {
            Form {
                if let transaction {
                    ForEach(transaction.warnings, id: \.self) { Text($0).font(.caption).foregroundStyle(.orange) }
                    if transaction.duplicateOf != nil { Text("الحفظ يؤكد أن هذه عملية مستقلة. احذفها إن كانت نسخة مكررة.").font(.caption) }
                }
                TextField("المبلغ بالريال", text: $amount).keyboardType(.decimalPad)
                TextField("البنك المستخدم", text: $bank)
                Toggle("هذا البنك ضمن حساباتي", isOn: $ownsBank)
                if let transaction, transaction.currency != "OMR" {
                    Text("عملة الرسالة: \(transaction.currency). أدخل قيمة الخصم الفعلية بالريال لتدخل الحسابات.").font(.caption).foregroundStyle(.secondary)
                }
                Picker("نوع العملية", selection: $effect) { Text("اختر نوع العملية").tag(""); Text("مصروف").tag("expense"); Text("مبلغ وارد").tag("income"); Text("استرجاع").tag("refund"); Text("تحويل بين حساباتي").tag("internal") }
                if effect == "refund" {
                    Picker("المصروف الأصلي", selection: $original) {
                        Text("غير مرتبط بعملية").tag("")
                        ForEach(store.state.transactions.filter { $0.effect == "expense" && $0.financialVerified && $0.duplicateOf == nil && $0.id != transaction?.id }) { expense in
                            Text("\(expense.counterparty ?? "مصروف") — \(expense.accountingBaisa.map(baisa) ?? "") ر.ع").tag(expense.id)
                        }
                    }
                    Text("الاسترجاع مرتبط بتاريخ وصوله، ويأخذ فئة المصروف الأصلي عند الربط.").font(.caption).foregroundStyle(.secondary)
                }
                Picker("الفئة", selection: $category) { Text("غير مصنف").tag(""); ForEach(store.state.categories) { Text("\($0.symbol) \($0.name)").tag($0.id) } }
                TextField("الجهة — اختياري", text: $party)
                TextField("السبب أو الملاحظة", text: $note)
                DatePicker("التاريخ", selection: $date)
                Toggle("الساعة مؤكدة وليست تقديرية", isOn: $timeKnown)
                Toggle("مطابقة التحويلات بين حساباتي تلقائيًا", isOn: $automatic)
                if transaction?.transferPairId != nil {
                    Button("فك الربط واعتبار العمليتين مستقلتين") { store.perform { if let transaction { try store.change { try $0.unlinkTransfer(id: transaction.id) }; dismiss() } } }
                }
                if transaction?.datePrecision == "day" { Text("الرسالة ذكرت اليوم فقط، والساعة غير معروفة.").font(.caption).foregroundStyle(.secondary) }
                if transaction?.datePrecision == "received" { Text("وقت الاستلام تقديري؛ صححه إذا عرفت وقت العملية.").font(.caption).foregroundStyle(.secondary) }
                Toggle("تذكر الفئة لهذه الجهة", isOn: $remember)
                TextField("كلمة مفتاحية لتعلم الفئة — اختياري", text: $categoryKeyword)
                TextField("اختصار لتعلم البنك — اختياري", text: $bankKeyword)
                if let cash = currentTransaction, cash.type == "atm", cash.effect == "expense", cash.financialVerified,
                   cash.currency == "OMR", cash.duplicateOf == nil, (cash.accountingBaisa ?? 0) > 0 {
                    Button("توزيع جزء من النقد على فئة") { splitting = true }
                    Text("يبقى إجمالي المصروف ثابتًا؛ ينخفض النقد غير الموزع بالمبلغ المحدد.").font(.caption).foregroundStyle(.secondary)
                }
                if transaction != nil { Button("حذف العملية", role: .destructive) { deleting = true } }
            }.navigationTitle(transaction == nil ? "عملية جديدة" : "مراجعة العملية").toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("إلغاء") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("حفظ") {
                    store.perform {
                        guard let value = Money.shared.input(text: amount)?.int64Value else { throw AppError.message("المبلغ غير صالح") }
                        guard BankKnowledge.shared.key(value: bank) != nil else { throw AppError.message("حدد البنك المستخدم") }
                        guard !effect.isEmpty else { throw AppError.message("اختر مبلغًا داخلًا أو خارجًا") }
                        let payload = EditPayload(id: transaction?.id, amountBaisa: value, direction: ["income", "refund"].contains(effect) ? "in" : effect == "internal" ? transaction?.direction ?? "out" : "out", effect: effect,
                            categoryId: category.isEmpty ? nil : category, counterparty: party.isEmpty ? nil : party, note: note, occurredAtMillis: millis(date), rememberMerchant: remember,
                            originalId: effect == "refund" && !original.isEmpty ? original : nil, bankName: bank, ownsBank: ownsBank,
                            learnKeyword: categoryKeyword.isEmpty ? nil : categoryKeyword, learnBankAlias: bankKeyword.isEmpty ? nil : bankKeyword,
                            matchTransfers: automatic, timeConfirmed: timeKnown)
                        try store.edit(payload); dismiss()
                    }
                } }
            }
            .onAppear { loadTransaction() }
            .onChange(of: bank) { _, value in ownsBank = BankKnowledge.shared.key(value: value).map { store.state.ownedBanks?.contains($0) ?? false } ?? false }
            .onChange(of: original) { _, id in
                if effect == "refund", let expense = store.state.transactions.first(where: { $0.id == id }) {
                    category = expense.categoryId ?? ""
                }
            }
            .sheet(isPresented: $splitting) {
                if let transaction = currentTransaction { CashSplitView(store: store, transaction: transaction) { loadTransaction() } }
            }
            .confirmationDialog("حذف العملية؟", isPresented: $deleting, titleVisibility: .visible) {
                Button("حذف", role: .destructive) { if let transaction { store.perform { try store.change { try $0.deleteTransaction(id: transaction.id) }; dismiss() } } }
            }
        }.modifier(LedgerSheetErrors(store: store))
    }
    private func loadTransaction() {
        guard let transaction = currentTransaction else { return }
        amount = transaction.accountingBaisa.map(baisa) ?? ""; party = transaction.counterparty ?? ""; note = transaction.note
        effect = transaction.effect == "unresolved" ? (transaction.direction == "in" ? "income" : transaction.direction == "out" ? "expense" : "") : transaction.effect
        category = transaction.categoryId ?? ""; original = transaction.originalId ?? ""
        date = Date(timeIntervalSince1970: Double(transaction.occurredAtMillis) / 1000)
        bank = transaction.bankName ?? ""; ownsBank = BankKnowledge.shared.key(value: bank).map { store.state.ownedBanks?.contains($0) ?? false } ?? false
        automatic = transaction.matchTransfers ?? true; timeKnown = ["second", "minute"].contains(transaction.datePrecision)
    }
}
@MainActor struct CashSplitView: View {
    @ObservedObject var store: LedgerStore
    let transaction: LedgerTransaction
    let completed: () -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var amount = ""
    @State private var category = "groceries"
    @State private var note = ""
    var body: some View {
        NavigationStack {
            Form {
                Text("النقد المتبقي: \(transaction.accountingBaisa.map(baisa) ?? "0.000") ر.ع").font(.headline)
                TextField("المبلغ الذي صرفته — ر.ع", text: $amount).keyboardType(.decimalPad)
                Picker("الفئة", selection: $category) {
                    ForEach(store.state.categories.filter { $0.id != "cash" }) { Text("\($0.symbol) \($0.name)").tag($0.id) }
                }
                TextField("السبب — اختياري", text: $note)
                Text("هذا توزيع للسحب المسجل، ولا يضيف مصروفًا جديدًا إلى الإجمالي.").font(.caption).foregroundStyle(.secondary)
            }.navigationTitle("توزيع النقد").toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("إلغاء") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("حفظ") {
                    store.perform {
                        guard let value = Money.shared.input(text: amount)?.int64Value,
                              value > 0, value <= (transaction.accountingBaisa ?? 0) else { throw AppError.message("المبلغ يجب أن يكون ضمن النقد المتبقي") }
                        _ = try store.change { try $0.splitCash(id: transaction.id, amountBaisa: value, categoryId: category, note: note) }
                        completed(); dismiss()
                    }
                } }
            }
        }.modifier(LedgerSheetErrors(store: store))
    }
}
@MainActor struct BudgetView: View {
    @ObservedObject var store: LedgerStore; @State var selected: LedgerCategory?
    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                Text("حد أسبوعي وشهري مستقل لكل فئة. اضغط لتعديل الحد.").foregroundStyle(.secondary)
                ForEach(store.report().categories) { line in Button { selected = store.state.categories.first { $0.id == line.categoryId } } label: { CategoryCard(line: line) }.buttonStyle(.plain) }
            }.padding(20)
        }.background(Color(.systemGroupedBackground)).navigationTitle("حدود الإنفاق")
        .sheet(item: $selected) { category in BudgetEditor(store: store, category: category) }
    }
}
@MainActor struct BudgetEditor: View {
    @ObservedObject var store: LedgerStore; let category: LedgerCategory
    @Environment(\.dismiss) var dismiss; @State var weekly = ""; @State var monthly = ""
    var body: some View {
        NavigationStack {
            Form {
                TextField("أسبوعي — ر.ع", text: $weekly).keyboardType(.decimalPad)
                TextField("شهري — ر.ع", text: $monthly).keyboardType(.decimalPad)
                Text("اترك الحقل فارغًا لإلغاء الحد.").font(.caption)
            }.navigationTitle("حد \(category.name)").toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("إلغاء") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) { Button("حفظ") {
                    store.perform {
                        let w: Int64? = weekly.isEmpty ? 0 : Money.shared.input(text: weekly)?.int64Value
                        let m: Int64? = monthly.isEmpty ? 0 : Money.shared.input(text: monthly)?.int64Value
                        guard let w, let m else { throw AppError.message("مبلغ غير صالح") }
                        try store.change { try $0.setBudget(categoryId: category.id, weeklyBaisa: w, monthlyBaisa: m) }; dismiss()
                    }
                } }
            }.onAppear { let budget = store.state.budgets.first { $0.categoryId == category.id }; weekly = budget?.weeklyBaisa.map(baisa) ?? ""; monthly = budget?.monthlyBaisa.map(baisa) ?? "" }
        }.modifier(LedgerSheetErrors(store: store))
    }
}

extension UTType {
    static let masroofiBackup = UTType(exportedAs: "om.masroofi.backup", conformingTo: .data)
}
struct BackupDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.masroofiBackup, .data] }
    var data: Data
    init(data: Data) { self.data = data }
    init(configuration: ReadConfiguration) throws {
        guard let contents = configuration.file.regularFileContents else { throw AppError.message("ملف النسخة غير صالح") }
        data = contents
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper { FileWrapper(regularFileWithContents: data) }
}
@MainActor struct SettingsView: View {
    @ObservedObject var store: LedgerStore
    @State var prefs = LedgerPreferences.defaults
    @State var name = ""; @State var password = ""; @State var backupMode: String?
    @State var exporting = false; @State var importing = false; @State var clearing = false
    @State var document = BackupDocument(data: Data())
    var body: some View {
        Form {
            Section("خصوصيتك") { Text("المعالجة على جهازك، والتخزين مشفر. لا يُحفظ نص الرسالة ولا يرسل إلى خادم.").font(.subheadline) }
            Section("الاستقبال والإشعارات") {
                Text("في Shortcuts: أضف أتمتة عند وصول رسالة، وحدد المرسل أو كلمة مالية، ثم مرر نص الرسالة إلى إجراء «تسجيل رسالة مالية» من مصروفي. فعّل التشغيل الفوري واختبره على جهازك.").font(.subheadline)
                Button("السماح بالإشعارات") { Task { await Notifications.shared.ask() } }
            }
            Section("الفترات والتذكير") {
                Picker("بداية الأسبوع", selection: $prefs.weekStart) { Text("السبت").tag(Int32(6)); Text("الأحد").tag(Int32(7)); Text("الاثنين").tag(Int32(1)); Text("الثلاثاء").tag(Int32(2)); Text("الأربعاء").tag(Int32(3)); Text("الخميس").tag(Int32(4)); Text("الجمعة").tag(Int32(5)) }
                Stepper("بداية الشهر: يوم \(prefs.monthStartDay)", value: $prefs.monthStartDay, in: 1...31)
                Stepper("ساعة التذكير: \(prefs.reminderHour)", value: $prefs.reminderHour, in: 0...23)
                Toggle("تذكير يومي مجمع", isOn: $prefs.remindersEnabled)
                Toggle("إظهار تفاصيل في الإشعار", isOn: $prefs.showAmountsOnLockScreen)
                Button("حفظ الإعدادات") { store.perform { try store.change { try $0.setPreferences(json: jsonString(prefs)) } } }
            }
            Section("فئاتك وقواعدك") {
                TextField("اسم فئة جديدة", text: $name)
                Button("إضافة فئة") { store.perform { _ = try store.change { try $0.addCategory(name: name, symbol: "◈") }; name = "" } }.disabled(name.isEmpty)
                ForEach(store.state.rules) { rule in VStack(alignment: .leading) {
                    Text("\(rule.merchant) ← \(store.state.categories.first { $0.id == rule.categoryId }?.name ?? "")").font(.caption)
                    HStack { Button("تطبيق على السابق") { store.perform { try store.change { $0.applyRuleToHistory(merchant: rule.merchant) } } }; Button("إلغاء القاعدة") { store.perform { try store.change { $0.removeRule(merchant: rule.merchant) } } } }
                } }
            }
            Section("نسخ احتياطي محلي") {
                Text("النسخة مشفرة بكلمة مرور. الاستعادة تستبدل البيانات الحالية.").font(.caption)
                Button("حفظ نسخة") { backupMode = "export"; password = "" }
                Button("استعادة نسخة") { backupMode = "restore"; password = "" }
                if store.state.transactions.contains(where: { $0.isDemo }) { Button("حذف الأمثلة التوضيحية") { store.perform { try store.change { $0.clearDemo() } } } }
                Button("حذف جميع البيانات", role: .destructive) { clearing = true }
            }
            Section("بنوك حساباتي") {
                ForEach(Array(Set(["بنك ميثاق", "بنك نزوى", "بنك صحار الإسلامي"] + (store.state.ownedBanks ?? []).map { BankKnowledge.shared.canonical(value: $0) })).sorted(), id: \.self) { bank in
                    Toggle(bank, isOn: Binding(get: {
                        BankKnowledge.shared.key(value: bank).map { store.state.ownedBanks?.contains($0) ?? false } ?? false
                    }, set: { owned in store.perform { try store.change { try $0.setOwnedBank(bankName: bank, owned: owned) } } }))
                }
            }
            Section("ذاكرة الكلمات المفتاحية المحلية") {
                ForEach(Array((store.state.keywordRules ?? []).enumerated()), id: \.offset) { _, rule in
                    Text("\(rule.keyword) ← \(rule.bankName ?? store.state.categories.first { $0.id == rule.categoryId }?.name ?? "")").font(.caption)
                    Button("حذف الكلمة") { store.perform { try store.change { $0.removeKeyword(keyword: rule.keyword) } } }
                }
            }
            Text("مصروفي \(Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "") • تعلم وتصنيف محلي").font(.caption).foregroundStyle(.secondary)
        }.navigationTitle("الإعدادات").onAppear { prefs = store.state.preferences }
        .alert(backupMode == "restore" ? "استعادة واستبدال البيانات" : "كلمة مرور النسخة", isPresented: Binding(get: { backupMode != nil }, set: { if !$0 { backupMode = nil } })) {
            SecureField(backupMode == "restore" ? "كلمة مرور النسخة" : "كلمة المرور — 8 أحرف على الأقل", text: $password)
            Button("متابعة") { let mode = backupMode; backupMode = nil; store.perform {
                if mode == "export" {
                    guard password.count >= 8 else { throw AppError.message("كلمة المرور قصيرة") }
                    document = BackupDocument(data: try BackupVault.seal(store.snapshot(), password: password)); exporting = true; password = ""
                } else {
                    guard !password.isEmpty else { throw AppError.message("أدخل كلمة مرور النسخة") }
                    importing = true
                }
            } }
            Button("إلغاء", role: .cancel) { password = ""; backupMode = nil }
        } message: { Text("احتفظ بكلمة المرور. الاستعادة ستستبدل البيانات الحالية.") }
        .fileExporter(isPresented: $exporting, document: document, contentType: .masroofiBackup, defaultFilename: "Masroofi-backup") { result in
            document = BackupDocument(data: Data())
            if case .failure = result { store.error = "تعذر حفظ النسخة الاحتياطية" }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.masroofiBackup, .data]) { result in
            defer { password = "" }
            store.perform {
                let url = try result.get(); let granted = url.startAccessingSecurityScopedResource(); defer { if granted { url.stopAccessingSecurityScopedResource() } }
                let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize
                guard size.map({ $0 < 24_000_000 }) ?? false else { throw AppError.message("الملف كبير جدًا أو غير صالح") }
                let data = try Data(contentsOf: url); try store.restore(BackupVault.open(data, password: password))
            }
        }
        .confirmationDialog("حذف جميع البيانات المحلية؟", isPresented: $clearing, titleVisibility: .visible) { Button("حذف", role: .destructive) { store.perform { try store.restore(LedgerEngine(snapshot: "").stateJson()) } } }
    }
}

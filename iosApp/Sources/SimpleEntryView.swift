import SwiftUI
import ExpenseCore

private let entryGreen = Color(red: 0.08, green: 0.36, blue: 0.27)

@MainActor struct SimpleEntryView: View {
    @ObservedObject var store: LedgerStore
    @Environment(\.dismiss) private var dismiss
    @State private var amount = ""; @State private var note = ""; @State private var bank = ""; @State private var category = ""
    @State private var effect = "expense"; @State private var bankPicker = false; @State private var categoryPicker = false
    @State private var editingTime = false; @State private var date = Date(); @State private var useNow = true; @State private var timeKnown = true
    private var valid: Bool { (Money.shared.input(text: amount)?.int64Value ?? 0) > 0 }
    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text("اسم البنك").font(.subheadline)
                    Button { bankPicker = true } label: {
                        HStack { Image(systemName: "building.columns"); Text(bank.isEmpty ? "اختر البنك" : bank); Spacer(); Image(systemName: "chevron.down") }.frame(maxWidth: .infinity).padding(12)
                    }.buttonStyle(.bordered)
                    HStack(alignment: .firstTextBaseline) {
                        TextField("0.000", text: $amount).keyboardType(.decimalPad).font(.system(size: 32, weight: .semibold)).accessibilityLabel("المبلغ بالريال")
                        Text("ر.ع").foregroundStyle(.secondary)
                    }.padding().background(.quaternary, in: RoundedRectangle(cornerRadius: 14))
                    Text("نوع العملية").font(.subheadline)
                    Picker("نوع العملية", selection: $effect) { Text("مصروف").tag("expense"); Text("وارد").tag("income"); Text("استرجاع").tag("refund"); Text("تحويل").tag("internal") }.pickerStyle(.segmented)
                    Text("الفئة").font(.subheadline)
                    Button { categoryPicker = true } label: {
                        HStack { Text(store.state.categories.first { $0.id == category }?.name ?? (store.state.categories.isEmpty ? "أنشئ فئتك الأولى" : "بدون فئة")); Spacer(); Image(systemName: "chevron.down") }.frame(maxWidth: .infinity).padding(12)
                    }.buttonStyle(.bordered)
                    TextField("ملاحظة — اختياري", text: $note, axis: .vertical).lineLimit(2...3).textFieldStyle(.roundedBorder)
                }.padding(20)
            }.scrollDismissesKeyboard(.interactively).navigationTitle("إضافة عملية").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement: .cancellationAction) { Button("إغلاق") { dismiss() } } }
                .safeAreaInset(edge: .bottom) {
                    VStack(spacing: 5) {
                        Button(action: save) { Text("اعتماد العملية").font(.headline).frame(maxWidth: .infinity).padding(.vertical, 8) }.buttonStyle(.borderedProminent).disabled(!valid)
                        Button { editingTime = true } label: {
                            Label((useNow ? Date() : date).formatted(date: .numeric, time: .shortened) + (!timeKnown ? " · الساعة غير مؤكدة" : ""), systemImage: "pencil").font(.caption)
                        }.padding(.vertical, 8)
                    }.padding(.horizontal,20).padding(.vertical,10).background(.background)
                }
                .onAppear {
                    let actual=store.state.transactions.filter { !$0.isDemo && BankKnowledge.shared.key(value: $0.bankName ?? "") != nil }
                    bank=actual.first { $0.source == "manual" }?.bankName ?? actual.first?.bankName ?? ""
                    if bank.isEmpty {
                        let owned=BankKnowledge.shared.names.filter { name in BankKnowledge.shared.key(value:name).map { store.state.ownedBanks?.contains($0) ?? false } ?? false }
                        if owned.count==1 { bank=owned[0] }
                    }
                }
                .sheet(isPresented:$bankPicker) { QuickBankPicker(bank:$bank,recent:store.state.transactions.filter { !$0.isDemo }.compactMap(\.bankName)) }
                .sheet(isPresented:$categoryPicker) { UserCategoriesView(store:store) { id in category=id;categoryPicker=false } }
                .sheet(isPresented:$editingTime) {
                    NavigationStack {
                        Form {
                            DatePicker("التاريخ",selection:Binding(get:{date},set:{date=$0;useNow=false;timeKnown=false}),displayedComponents:.date)
                            Toggle("الساعة مؤكدة",isOn:$timeKnown)
                            if timeKnown { DatePicker("الساعة",selection:Binding(get:{date},set:{date=$0;useNow=false}),displayedComponents:.hourAndMinute) }
                            Button("استخدام الوقت الحالي") { useNow=true;timeKnown=true;date=Date();editingTime=false }
                        }.navigationTitle("التاريخ والوقت").navigationBarTitleDisplayMode(.inline)
                            .toolbar { ToolbarItem(placement:.confirmationAction) { Button("تم") { editingTime=false } } }
                    }.presentationDetents([.medium])
                }
                .onChange(of:amount) { _,value in if value.count>32 { amount=String(value.prefix(32)) } }
                .onChange(of:note) { _,value in if value.count>500 { note=String(value.prefix(500)) } }
        }.tint(entryGreen).environment(\.timeZone,TimeZone(identifier:store.state.preferences.timeZone) ?? .current).modifier(LedgerSheetErrors(store:store))
    }
    private func save() {
        guard valid else { return }
        guard BankKnowledge.shared.key(value:bank) != nil else { bankPicker=true;return }
        store.perform {
            guard let value=Money.shared.input(text:amount)?.int64Value,value>0 else { throw AppError.message("المبلغ غير صالح") }
            let payload=EditPayload(id:nil,amountBaisa:value,direction:["income","refund"].contains(effect) ? "in":"out",effect:effect,categoryId:category.isEmpty ? nil:category,
                counterparty:nil,note:note.trimmingCharacters(in:.whitespacesAndNewlines),occurredAtMillis:millis(useNow ? Date():date),rememberMerchant:false,originalId:nil,
                bankName:BankKnowledge.shared.canonical(value:bank),timeConfirmed:timeKnown)
            try store.edit(payload);dismiss()
        }
    }
}

@MainActor struct UserCategoriesView: View {
    @ObservedObject var store:LedgerStore
    var select:((String)->Void)? = nil
    @Environment(\.dismiss) private var dismiss
    @State private var name=""; @State private var editID:String?; @State private var deleting:LedgerCategory?
    @State private var renameText=""; @State private var renaming=false
    var body:some View {
        NavigationStack {
            List {
                Section("فئة جديدة") {
                    TextField("اسم الفئة",text:$name)
                    Button(select == nil ? "إنشاء فئة":"إنشاء واختيار") {
                        store.perform { let id=try store.change { try $0.addCategory(name:name,symbol:"◈") };name="";select?(id) }
                    }.disabled(name.trimmingCharacters(in:.whitespacesAndNewlines).isEmpty)
                }
                Section("فئاتك") {
                    if let select { Button("بدون فئة") { select("") } }
                    ForEach(store.state.categories) { item in
                        Button(item.name) { if let select { select(item.id) } else { editID=item.id;renameText=item.name;renaming=true } }
                            .swipeActions { Button("حذف",role:.destructive) { deleting=item } }
                    }
                    if store.state.categories.isEmpty { Text("لا توجد فئات ثابتة. أنشئ ما يناسبك.").font(.caption).foregroundStyle(.secondary) }
                }
            }.navigationTitle("فئاتك").navigationBarTitleDisplayMode(.inline)
                .toolbar { ToolbarItem(placement:.confirmationAction) { Button("تم") { dismiss() } } }
                .alert("تعديل اسم الفئة",isPresented:$renaming) {
                    TextField("الاسم",text:$renameText)
                    Button("حفظ") { store.perform { if let id=editID { try store.change { try $0.renameCategory(id:id,name:renameText) } } } }
                    Button("إلغاء",role:.cancel) { editID=nil }
                }
                .confirmationDialog("حذف الفئة مع إبقاء العمليات ومبالغها؟",isPresented:Binding(get:{deleting != nil},set:{if !$0 { deleting=nil }}),titleVisibility:.visible) {
                    Button("حذف الفئة",role:.destructive) { store.perform { if let item=deleting { try store.change { try $0.deleteCategory(id:item.id) };deleting=nil } } }
                    Button("إلغاء",role:.cancel) { deleting=nil }
                }
                .onChange(of:name) { _,value in if value.count>40 { name=String(value.prefix(40)) } }
        }.tint(entryGreen).modifier(LedgerSheetErrors(store:store))
    }
}

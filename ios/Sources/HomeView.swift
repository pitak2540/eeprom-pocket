import SwiftUI
import PhotosUI
import VisionKit

@main
@MainActor
struct JamesScanApp: App {
    @StateObject private var store = Store.shared
    var body: some Scene {
        WindowGroup {
            HomeView()
                .environmentObject(store)
                .preferredColorScheme(.dark)
                .tint(Theme.teal)
        }
    }
}

/// Loads images picked from Photos.
func loadPicked(_ items: [PhotosPickerItem]) async -> [UIImage] {
    var out: [UIImage] = []
    for it in items {
        if let data = try? await it.loadTransferable(type: Data.self), let img = UIImage(data: data) { out.append(img) }
    }
    return out
}

@MainActor
struct HomeView: View {
    @EnvironmentObject var store: Store
    @State private var path: [String] = []
    @State private var showScanner = false
    @State private var picked: [PhotosPickerItem] = []
    @State private var busy = false
    @State private var renaming: Doc?
    @State private var renameText = ""
    @State private var deleting: Doc?
    @State private var share: ShareItems?
    @State private var toast: String?

    private let cols = [GridItem(.flexible(), spacing: 12), GridItem(.flexible(), spacing: 12)]

    var body: some View {
        NavigationStack(path: $path) {
            ZStack(alignment: .bottom) {
                Theme.bg.ignoresSafeArea()
                if store.docs.isEmpty {
                    emptyState
                } else {
                    ScrollView {
                        LazyVGrid(columns: cols, spacing: 12) {
                            ForEach(store.docs) { d in
                                NavigationLink(value: d.id) { DocCard(doc: d) }
                                    .buttonStyle(.plain)
                                    .contextMenu { menu(d) }
                            }
                        }
                        .padding(.horizontal, 16)
                        .padding(.bottom, 120)
                    }
                }
                actionBar
                if busy { ProgressView().scaleEffect(1.4).frame(maxHeight: .infinity) }
                if let t = toast { ToastView(text: t) }
            }
            .navigationTitle("JAMES Scan")
            .navigationDestination(for: String.self) { id in EditView(docId: id) }
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    if !store.docs.isEmpty { Text("\(store.docs.count) เอกสาร").font(.footnote).foregroundColor(Theme.dim) }
                }
            }
        }
        .fullScreenCover(isPresented: $showScanner) {
            DocumentScanner(onFinish: { imgs in
                showScanner = false
                create(imgs, detect: false)
            }, onCancel: { showScanner = false })
            .ignoresSafeArea()
        }
        .onChange(of: picked) { items in
            guard !items.isEmpty else { return }
            busy = true
            Task {
                let imgs = await loadPicked(items)
                picked = []
                create(imgs, detect: true)
            }
        }
        .alert("ตั้งชื่อเอกสาร", isPresented: Binding(get: { renaming != nil }, set: { if !$0 { renaming = nil } })) {
            TextField("ชื่อ", text: $renameText)
            Button("ยกเลิก", role: .cancel) {}
            Button("ตกลง") {
                if var d = renaming, !renameText.trimmingCharacters(in: .whitespaces).isEmpty {
                    d.title = renameText.trimmingCharacters(in: .whitespaces); store.update(d)
                }
            }
        }
        .confirmationDialog("ลบเอกสารนี้?", isPresented: Binding(get: { deleting != nil }, set: { if !$0 { deleting = nil } }), titleVisibility: .visible) {
            Button("ลบ", role: .destructive) { if let d = deleting { store.delete(d.id) } }
        }
        .sheet(item: $share) { s in ShareSheet(items: s.urls) }
    }

    private var emptyState: some View {
        VStack(spacing: 10) {
            Image(systemName: "doc.viewfinder").font(.system(size: 64)).foregroundColor(Theme.dim)
            Text("ยังไม่มีเอกสาร").font(.title3.bold())
            Text("กดปุ่มสแกนเพื่อถ่ายเอกสาร ใบเสร็จ หรือใบส่งของ\nแอพจะจับขอบ ตัด และลบเงาให้อัตโนมัติ")
                .font(.subheadline).foregroundColor(Theme.dim).multilineTextAlignment(.center)
        }
        .padding(32)
        .frame(maxHeight: .infinity)
    }

    private var actionBar: some View {
        HStack(spacing: 16) {
            PhotosPicker(selection: $picked, maxSelectionCount: 50, matching: .images) {
                Image(systemName: "photo.on.rectangle")
                    .font(.title3)
                    .foregroundColor(.white)
                    .frame(width: 52, height: 52)
                    .background(Theme.surface2, in: Circle())
            }
            Button {
                if VNDocumentCameraViewController.isSupported { showScanner = true }
                else { flash("เครื่องนี้ไม่รองรับกล้องสแกนเอกสาร") }
            } label: {
                Label("สแกน", systemImage: "camera.fill")
                    .font(.headline)
                    .foregroundColor(.white)
                    .padding(.horizontal, 28)
                    .frame(height: 56)
                    .background(Theme.teal, in: Capsule())
            }
        }
        .padding(.bottom, 24)
    }

    @ViewBuilder private func menu(_ d: Doc) -> some View {
        Button { path.append(d.id) } label: { Label("เปิด", systemImage: "doc") }
        Button { renameText = d.title; renaming = d } label: { Label("เปลี่ยนชื่อ", systemImage: "pencil") }
        Button { run { _ = try Exporter.savePDF(d); return "บันทึก PDF แล้ว (ไฟล์ › JAMES Scan › PDF)" } } label: {
            Label("บันทึกเป็น PDF", systemImage: "arrow.down.doc")
        }
        Button {
            busy = true
            Task.detached {
                let urls = try? Exporter.sharePDF(d)
                await MainActor.run { busy = false; if let u = urls { share = ShareItems(urls: u) } }
            }
        } label: { Label("แชร์ PDF", systemImage: "square.and.arrow.up") }
        Button(role: .destructive) { deleting = d } label: { Label("ลบ", systemImage: "trash") }
    }

    private func run(_ work: @escaping () throws -> String) {
        busy = true
        Task.detached {
            let msg: String
            do { msg = try work() } catch { msg = "ไม่สำเร็จ: \(error.localizedDescription)" }
            await MainActor.run { busy = false; flash(msg) }
        }
    }

    private func flash(_ s: String) {
        withAnimation { toast = s }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { withAnimation { if toast == s { toast = nil } } }
    }

    private func create(_ imgs: [UIImage], detect: Bool) {
        guard !imgs.isEmpty else { busy = false; return }
        busy = true
        let id = String(Int(Date().timeIntervalSince1970 * 1000))
        Task.detached(priority: .userInitiated) {
            let pages = Store.makePages(docId: id, images: imgs, detect: detect)
            await MainActor.run {
                busy = false
                guard !pages.isEmpty else { flash("นำเข้าภาพไม่สำเร็จ"); return }
                let doc = Doc(id: id, title: Store.newTitle(), created: Date(), pages: pages)
                store.update(doc)
                path.append(id)
            }
        }
    }
}

@MainActor
struct DocCard: View {
    let doc: Doc
    @State private var thumb: UIImage?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ZStack {
                Theme.surface2
                if let t = thumb { Image(uiImage: t).resizable().scaledToFit() }
            }
            .frame(height: 190)
            .clipped()
            Text(doc.title).font(.subheadline).lineLimit(2).foregroundColor(.white)
            Text("\(doc.created.formatted(date: .numeric, time: .shortened)) · \(doc.pages.count) หน้า")
                .font(.caption).foregroundColor(Theme.dim)
        }
        .padding(8)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 8))
        .task(id: doc.pages.first?.renderKey) {
            guard let first = doc.pages.first else { return }
            let id = doc.id
            thumb = await Task.detached(priority: .utility) { Processor.render(first, docId: id, maxSide: 500) }.value
        }
    }
}

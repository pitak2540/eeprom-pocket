import SwiftUI
import PhotosUI
import VisionKit

private enum ScanTarget: Identifiable {
    case add
    case replace(Int)
    var id: String {
        switch self {
        case .add: return "add"
        case .replace(let i): return "r\(i)"
        }
    }
}

@MainActor
struct EditView: View {
    let docId: String
    @EnvironmentObject var store: Store
    @Environment(\.dismiss) private var dismiss

    @State private var index = 0
    @State private var preview: UIImage?
    @State private var thumbs: [FilterKind: UIImage] = [:]
    @State private var thumbsKey = ""
    @State private var busy = false
    @State private var toast: String?
    @State private var scanTarget: ScanTarget?
    @State private var cropIndex: Int?
    @State private var showExport = false
    @State private var showRename = false
    @State private var renameText = ""
    @State private var confirmDelete = false
    @State private var share: ShareItems?
    @State private var didAutoSave = false

    private var doc: Doc? { store.doc(docId) }
    private var page: Page? {
        guard let d = doc, d.pages.indices.contains(index) else { return nil }
        return d.pages[index]
    }

    var body: some View {
        ZStack {
            Theme.bg.ignoresSafeArea()
            VStack(spacing: 0) {
                ZStack {
                    if let p = preview {
                        Image(uiImage: p).resizable().scaledToFit().padding(20)
                    }
                    if busy || preview == nil { ProgressView().scaleEffect(1.3) }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .gesture(DragGesture(minimumDistance: 30).onEnded { g in
                    if g.translation.width < -40 { go(1) } else if g.translation.width > 40 { go(-1) }
                })

                pager.padding(.bottom, 8)
                filterStrip
                toolbar
            }
            if let t = toast { VStack { Spacer(); ToastView(text: t) } }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Button {
                    renameText = doc?.title ?? ""; showRename = true
                } label: {
                    HStack(spacing: 6) {
                        Text(doc?.title ?? "").lineLimit(1).truncationMode(.middle).foregroundColor(.white)
                        Image(systemName: "pencil").foregroundColor(Theme.dim)
                    }
                }
            }
            ToolbarItem(placement: .navigationBarTrailing) {
                Button("เพิ่ม") { startScan(.add) }.bold()
            }
        }
        .task(id: page?.renderKey) { await renderPreview() }
        .task(id: page?.baseKey) { await renderThumbs() }
        .onAppear {
            // a fresh scan lands here: save its JPGs to Photos right away
            if !didAutoSave { didAutoSave = true; autoSave(then: nil) }
        }
        .fullScreenCover(item: $scanTarget) { target in
            DocumentScanner(onFinish: { imgs in
                scanTarget = nil
                addScanned(imgs, target: target, detect: false)
            }, onCancel: { scanTarget = nil })
            .ignoresSafeArea()
        }
        .sheet(item: Binding(get: { cropIndex.map { CropTarget(index: $0) } }, set: { cropIndex = $0?.index })) { t in
            CropScreen(docId: docId, pageIndex: t.index) { cropIndex = nil }
                .environmentObject(store)
        }
        .confirmationDialog("บันทึก JPG แล้ว · \(doc?.pages.count ?? 0) หน้า", isPresented: $showExport, titleVisibility: .visible) {
            Button("บันทึกเป็น PDF") { savePDF() }
            Button("แชร์ PDF") { sharePDF() }
            Button("แชร์รูปภาพ") { shareImages() }
            Button("เสร็จสิ้น") { dismiss() }
        }
        .alert("ตั้งชื่อเอกสาร", isPresented: $showRename) {
            TextField("ชื่อ", text: $renameText)
            Button("ยกเลิก", role: .cancel) {}
            Button("ตกลง") {
                let t = renameText.trimmingCharacters(in: .whitespaces)
                if var d = doc, !t.isEmpty { d.title = t; store.update(d) }
            }
        }
        .confirmationDialog(deleteTitle, isPresented: $confirmDelete, titleVisibility: .visible) {
            Button("ลบ", role: .destructive) { deletePage() }
        }
        .sheet(item: $share) { s in ShareSheet(items: s.urls) }
    }

    private struct CropTarget: Identifiable { let index: Int; var id: Int { index } }

    private var deleteTitle: String {
        (doc?.pages.count ?? 0) <= 1 ? "ลบเอกสารนี้? (มีหน้าเดียว)" : "ลบหน้า \(index + 1)?"
    }

    // MARK: - parts

    private var pager: some View {
        HStack(spacing: 0) {
            Button { go(-1) } label: { Image(systemName: "chevron.left").frame(width: 48, height: 40) }
                .disabled(index == 0)
            Text("\(index + 1)/\(doc?.pages.count ?? 0)").monospacedDigit().frame(width: 64)
            Button { go(1) } label: { Image(systemName: "chevron.right").frame(width: 48, height: 40) }
                .disabled(index >= (doc?.pages.count ?? 1) - 1)
        }
        .foregroundColor(.white)
        .background(Theme.surface, in: RoundedRectangle(cornerRadius: 8))
    }

    private var filterStrip: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(FilterKind.allCases) { f in
                    let on = page?.filter == f
                    ZStack(alignment: .bottom) {
                        Theme.surface2
                        if let t = thumbs[f] { Image(uiImage: t).resizable().scaledToFill() }
                        Text(f.label)
                            .font(.caption)
                            .foregroundColor(.white)
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 4)
                            .background(on ? Theme.teal : Color.black.opacity(0.7))
                    }
                    .frame(width: 82, height: 92)
                    .clipShape(RoundedRectangle(cornerRadius: 6))
                    .overlay(RoundedRectangle(cornerRadius: 6).stroke(on ? Theme.teal : .clear, lineWidth: 3))
                    .onTapGesture { setFilter(f, all: false) }
                    .onLongPressGesture { setFilter(f, all: true) }
                }
            }
            .padding(.horizontal, 10)
            .padding(.vertical, 10)
        }
        .background(Theme.surface)
    }

    private var toolbar: some View {
        HStack(spacing: 0) {
            tool("ถ่ายใหม่", "camera.rotate") { startScan(.replace(index)) }
            tool("หมุนซ้าย", "rotate.left") { rotateLeft() }
            tool("ครอป", "crop") { cropIndex = index }
            tool("ลบหน้า", "trash") { confirmDelete = true }
            Button {
                autoSave { showExport = true }
            } label: {
                Image(systemName: "checkmark").font(.title2.bold()).foregroundColor(.white)
                    .frame(width: 76, height: 50)
                    .background(Theme.teal, in: RoundedRectangle(cornerRadius: 8))
            }
            .padding(.leading, 6)
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
    }

    private func tool(_ title: String, _ icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.title3)
                Text(title).font(.caption2)
            }
            .foregroundColor(.white)
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
    }

    // MARK: - rendering

    private func renderPreview() async {
        guard let p = page else { return }
        let id = docId
        let img = await Task.detached(priority: .userInitiated) { Processor.render(p, docId: id, maxSide: 1600) }.value
        if !Task.isCancelled { preview = img }
    }

    private func renderThumbs() async {
        guard let p = page, thumbsKey != p.baseKey else { return }
        let id = docId
        let key = p.baseKey
        let small: CGImage? = await Task.detached(priority: .utility) {
            Processor.base(p, docId: id, maxSide: 600).map { Processor.scaled($0, maxSide: 240) }
        }.value
        guard let s = small, !Task.isCancelled else { return }
        var out: [FilterKind: UIImage] = [:]
        for f in FilterKind.allCases {
            let img = await Task.detached(priority: .utility) { UIImage(cgImage: Processor.apply(f, s)) }.value
            out[f] = img
            if Task.isCancelled { return }
            thumbs = out
        }
        thumbsKey = key
    }

    // MARK: - actions

    private func go(_ d: Int) {
        guard let n = doc?.pages.count else { return }
        let ni = min(max(index + d, 0), n - 1)
        if ni != index { index = ni }
    }

    private func setFilter(_ f: FilterKind, all: Bool) {
        guard var d = doc, d.pages.indices.contains(index) else { return }
        if all {
            for i in d.pages.indices { d.pages[i].filter = f }
            flash("ใช้ \"\(f.label)\" กับทุกหน้าแล้ว")
        } else {
            d.pages[index].filter = f
        }
        store.update(d)
    }

    private func rotateLeft() {
        guard var d = doc, d.pages.indices.contains(index) else { return }
        d.pages[index].rotation = (d.pages[index].rotation + 270) % 360
        store.update(d)
    }

    private func deletePage() {
        guard var d = doc else { return }
        if d.pages.count <= 1 {
            store.delete(d.id)
            dismiss()
            return
        }
        d.pages.remove(at: index)
        index = min(index, d.pages.count - 1)
        store.update(d)
    }

    private func startScan(_ t: ScanTarget) {
        if VNDocumentCameraViewController.isSupported { scanTarget = t } else { flash("เครื่องนี้ไม่รองรับกล้องสแกนเอกสาร") }
    }

    private func addScanned(_ imgs: [UIImage], target: ScanTarget, detect: Bool) {
        guard !imgs.isEmpty else { return }
        busy = true
        let id = docId
        Task.detached(priority: .userInitiated) {
            let pages = Store.makePages(docId: id, images: imgs, detect: detect)
            await MainActor.run {
                busy = false
                guard var d = doc, !pages.isEmpty else { return }
                switch target {
                case .add:
                    let first = d.pages.count
                    d.pages.append(contentsOf: pages)
                    store.update(d)
                    index = first
                case .replace(let i):
                    if d.pages.indices.contains(i) {
                        var np = pages[0]
                        np.filter = d.pages[i].filter
                        d.pages[i] = np
                        store.update(d)
                    }
                }
                autoSave(then: nil)
            }
        }
    }

    /// Save new / edited pages to Photos as JPG, then run `then`.
    private func autoSave(then: (() -> Void)?) {
        guard let d = doc else { then?(); return }
        if d.pages.allSatisfy({ $0.savedKey == $0.renderKey }) { then?(); return }
        busy = true
        Task {
            do {
                let (updated, n) = try await Exporter.syncPhotos(d)
                // keep edits made while saving; only copy the saved markers
                if var cur = doc {
                    for (i, p) in updated.pages.enumerated() where cur.pages.indices.contains(i) && cur.pages[i].id == p.id {
                        cur.pages[i].savedKey = p.savedKey
                    }
                    store.update(cur)
                }
                if n > 0 { flash("บันทึก JPG ลงรูปภาพแล้ว \(n) รูป") }
            } catch {
                flash("บันทึก JPG อัตโนมัติไม่สำเร็จ: \(error.localizedDescription)")
            }
            busy = false
            then?()
        }
    }

    private func savePDF() {
        guard let d = doc else { return }
        busy = true
        Task.detached {
            let msg: String
            do { _ = try Exporter.savePDF(d); msg = "บันทึก PDF แล้ว (แอพไฟล์ › JAMES Scan › PDF)" }
            catch { msg = "บันทึก PDF ไม่สำเร็จ: \(error.localizedDescription)" }
            await MainActor.run { busy = false; flash(msg) }
        }
    }

    private func sharePDF() {
        guard let d = doc else { return }
        busy = true
        Task.detached {
            let urls = try? Exporter.sharePDF(d)
            await MainActor.run { busy = false; if let u = urls { share = ShareItems(urls: u) } }
        }
    }

    private func shareImages() {
        guard let d = doc else { return }
        busy = true
        Task.detached {
            let urls = try? Exporter.shareImages(d)
            await MainActor.run { busy = false; if let u = urls, !u.isEmpty { share = ShareItems(urls: u) } }
        }
    }

    private func flash(_ s: String) {
        withAnimation { toast = s }
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.5) { withAnimation { if toast == s { toast = nil } } }
    }
}

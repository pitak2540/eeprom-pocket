import SwiftUI
import UIKit
import VisionKit

/// Apple's document camera: live edge detection, auto capture, perspective crop, multi-page.
struct DocumentScanner: UIViewControllerRepresentable {
    var onFinish: ([UIImage]) -> Void
    var onCancel: () -> Void

    func makeUIViewController(context: Context) -> VNDocumentCameraViewController {
        let vc = VNDocumentCameraViewController()
        vc.delegate = context.coordinator
        return vc
    }

    func updateUIViewController(_ uiViewController: VNDocumentCameraViewController, context: Context) {}

    func makeCoordinator() -> Coordinator { Coordinator(self) }

    final class Coordinator: NSObject, VNDocumentCameraViewControllerDelegate {
        let parent: DocumentScanner
        init(_ p: DocumentScanner) { parent = p }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFinishWith scan: VNDocumentCameraScan) {
            var imgs: [UIImage] = []
            for i in 0..<scan.pageCount { imgs.append(scan.imageOfPage(at: i)) }
            parent.onFinish(imgs)
        }

        func documentCameraViewControllerDidCancel(_ controller: VNDocumentCameraViewController) {
            parent.onCancel()
        }

        func documentCameraViewController(_ controller: VNDocumentCameraViewController, didFailWithError error: Error) {
            parent.onCancel()
        }
    }
}

struct ShareItems: Identifiable {
    let id = UUID()
    let urls: [URL]
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }
    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) {}
}

/// Small message bubble at the bottom of the screen.
struct ToastView: View {
    let text: String
    var body: some View {
        Text(text)
            .font(.subheadline)
            .foregroundColor(.white)
            .padding(.horizontal, 16)
            .padding(.vertical, 10)
            .background(Color.black.opacity(0.85), in: Capsule())
            .padding(.bottom, 110)
            .transition(.opacity)
    }
}

/// Image with four draggable corners and a magnifier.
@MainActor
struct CropEditor: View {
    let image: UIImage
    @Binding var quad: Quad
    @State private var active: Int? = nil

    var body: some View {
        GeometryReader { geo in
            let rect = fitRect(image.size, in: geo.size, pad: 24)
            ZStack(alignment: .topLeading) {
                Image(uiImage: image)
                    .resizable()
                    .frame(width: rect.width, height: rect.height)
                    .position(x: rect.midX, y: rect.midY)

                Path { p in
                    let pts = quad.points.map { pt(rect, $0) }
                    p.addRect(rect)
                    p.move(to: pts[0]); pts.dropFirst().forEach { p.addLine(to: $0) }; p.closeSubpath()
                }
                .fill(Color.black.opacity(0.5), style: FillStyle(eoFill: true))

                Path { p in
                    let pts = quad.points.map { pt(rect, $0) }
                    p.move(to: pts[0]); pts.dropFirst().forEach { p.addLine(to: $0) }; p.closeSubpath()
                }
                .stroke(Theme.teal, lineWidth: 2.5)

                ForEach(0..<4, id: \.self) { i in
                    Circle()
                        .fill(Color.white)
                        .overlay(Circle().stroke(Theme.teal, lineWidth: 3))
                        .frame(width: 24, height: 24)
                        .position(pt(rect, quad.points[i]))
                        .gesture(
                            DragGesture(minimumDistance: 0)
                                .onChanged { g in
                                    active = i
                                    let nx = min(max((g.location.x - rect.minX) / rect.width, 0), 1)
                                    let ny = min(max((g.location.y - rect.minY) / rect.height, 0), 1)
                                    set(i, CGPoint(x: nx, y: ny))
                                }
                                .onEnded { _ in active = nil }
                        )
                }

                if let a = active {
                    magnifier(rect: rect, point: quad.points[a], geo: geo.size)
                }
            }
        }
    }

    private func magnifier(rect: CGRect, point: CGPoint, geo: CGSize) -> some View {
        let r: CGFloat = 56
        let zoom: CGFloat = 2.2
        let onLeft = point.x < 0.5
        let center = CGPoint(x: onLeft ? geo.width - r - 16 : r + 16, y: r + 16)
        let w = rect.width * zoom, h = rect.height * zoom
        return Image(uiImage: image)
            .resizable()
            .frame(width: w, height: h)
            .offset(x: r - point.x * w, y: r - point.y * h)
            .frame(width: r * 2, height: r * 2, alignment: .topLeading)
            .clipShape(Circle())
            .overlay(Circle().stroke(Color.white, lineWidth: 3))
            .overlay(
                ZStack {
                    Rectangle().fill(Theme.teal).frame(width: 24, height: 1.5)
                    Rectangle().fill(Theme.teal).frame(width: 1.5, height: 24)
                }
            )
            .position(center)
            .allowsHitTesting(false)
    }

    private func set(_ i: Int, _ p: CGPoint) {
        switch i {
        case 0: quad.tl = p
        case 1: quad.tr = p
        case 2: quad.br = p
        default: quad.bl = p
        }
    }

    private func pt(_ r: CGRect, _ p: CGPoint) -> CGPoint {
        CGPoint(x: r.minX + p.x * r.width, y: r.minY + p.y * r.height)
    }
}

func fitRect(_ img: CGSize, in box: CGSize, pad: CGFloat) -> CGRect {
    let aw = max(box.width - pad * 2, 1), ah = max(box.height - pad * 2, 1)
    let sc = min(aw / max(img.width, 1), ah / max(img.height, 1))
    let w = img.width * sc, h = img.height * sc
    return CGRect(x: (box.width - w) / 2, y: (box.height - h) / 2, width: w, height: h)
}

@MainActor
struct CropScreen: View {
    let docId: String
    let pageIndex: Int
    var onDone: () -> Void
    @EnvironmentObject var store: Store
    @State private var image: UIImage?
    @State private var quad = Quad.full
    @State private var auto: Quad?

    var body: some View {
        NavigationStack {
            ZStack {
                Theme.bg.ignoresSafeArea()
                if let img = image {
                    CropEditor(image: img, quad: $quad)
                } else {
                    ProgressView()
                }
            }
            .navigationTitle("ปรับขอบเอกสาร")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("ยกเลิก") { onDone() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("ตกลง") { save() }.bold()
                }
                ToolbarItemGroup(placement: .bottomBar) {
                    Button { if let a = auto { quad = a } } label: { Label("ตรวจจับอัตโนมัติ", systemImage: "wand.and.stars") }
                        .disabled(auto == nil)
                    Spacer()
                    Button { quad = .full } label: { Label("ทั้งภาพ", systemImage: "arrow.up.left.and.arrow.down.right") }
                }
            }
        }
        .task {
            guard let doc = store.doc(docId), doc.pages.indices.contains(pageIndex) else { return }
            let page = doc.pages[pageIndex]
            let url = Store.dir(docId).appendingPathComponent(page.src)
            let result: (UIImage?, Quad?) = await Task.detached(priority: .userInitiated) {
                guard let cg = Processor.load(url, maxSide: 1600) else { return (nil, nil) }
                return (UIImage(cgImage: cg), Processor.detect(cg))
            }.value
            image = result.0
            auto = result.1
            quad = page.quad ?? .full
        }
    }

    private func save() {
        guard var doc = store.doc(docId), doc.pages.indices.contains(pageIndex) else { onDone(); return }
        doc.pages[pageIndex].quad = quad == .full ? nil : quad
        store.update(doc)
        onDone()
    }
}

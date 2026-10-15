import Foundation
import UIKit
import SwiftUI

enum Theme {
    static let teal = Color(red: 0.098, green: 0.737, blue: 0.612)
    static let bg = Color(red: 0.090, green: 0.094, blue: 0.102)
    static let surface = Color(red: 0.149, green: 0.157, blue: 0.169)
    static let surface2 = Color(red: 0.200, green: 0.212, blue: 0.227)
    static let dim = Color(red: 0.64, green: 0.66, blue: 0.68)
}

enum FilterKind: String, Codable, CaseIterable, Identifiable {
    case noshadow, original, enhance, magic, gray, bw
    var id: String { rawValue }
    var label: String {
        switch self {
        case .noshadow: return "ลบเงา"
        case .original: return "ต้นฉบับ"
        case .enhance: return "ปรับปรุง"
        case .magic: return "เมจิกโปร"
        case .gray: return "สีเทา"
        case .bw: return "ขาวดำ"
        }
    }
    /** every new scan starts with "ลบเงา" */
    static let defaultFilter: FilterKind = .noshadow
}

/// Corner points normalized 0...1, origin top-left.
struct Quad: Codable, Equatable {
    var tl: CGPoint
    var tr: CGPoint
    var br: CGPoint
    var bl: CGPoint

    static let full = Quad(tl: CGPoint(x: 0, y: 0), tr: CGPoint(x: 1, y: 0), br: CGPoint(x: 1, y: 1), bl: CGPoint(x: 0, y: 1))
    var points: [CGPoint] { [tl, tr, br, bl] }
    var keyString: String { points.map { String(format: "%.4f,%.4f", $0.x, $0.y) }.joined(separator: ";") }
}

struct Page: Codable, Identifiable, Equatable {
    var id = UUID()
    var src: String
    var quad: Quad?
    var filter: FilterKind = FilterKind.defaultFilter
    var rotation: Int = 0          // clockwise degrees, multiple of 90
    var savedKey: String?          // renderKey at the time it was saved to Photos

    var baseKey: String { "\(src)|\(quad?.keyString ?? "-")|\(rotation)" }
    var renderKey: String { baseKey + "|" + filter.rawValue }
}

struct Doc: Codable, Identifiable, Equatable {
    var id: String
    var title: String
    var created: Date
    var pages: [Page]
}

@MainActor
final class Store: ObservableObject {
    static let shared = Store()
    @Published var docs: [Doc] = []

    nonisolated static var root: URL {
        let base = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        let r = base.appendingPathComponent("docs", isDirectory: true)
        try? FileManager.default.createDirectory(at: r, withIntermediateDirectories: true)
        return r
    }

    nonisolated static func dir(_ id: String) -> URL {
        let d = root.appendingPathComponent(id, isDirectory: true)
        try? FileManager.default.createDirectory(at: d, withIntermediateDirectories: true)
        return d
    }

    /// Visible in the Files app: On My iPhone › JAMES Scan
    nonisolated static var exportDir: URL {
        FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
    }

    init() { load() }

    func load() {
        let fm = FileManager.default
        let dec = JSONDecoder()
        var out: [Doc] = []
        for d in (try? fm.contentsOfDirectory(at: Store.root, includingPropertiesForKeys: nil)) ?? [] {
            let f = d.appendingPathComponent("doc.json")
            if let data = try? Data(contentsOf: f), let doc = try? dec.decode(Doc.self, from: data), !doc.pages.isEmpty {
                out.append(doc)
            } else {
                try? fm.removeItem(at: d)
            }
        }
        docs = out.sorted { $0.created > $1.created }
    }

    func doc(_ id: String) -> Doc? { docs.first { $0.id == id } }

    func update(_ doc: Doc) {
        if let i = docs.firstIndex(where: { $0.id == doc.id }) { docs[i] = doc } else { docs.insert(doc, at: 0) }
        Store.write(doc)
    }

    nonisolated static func write(_ doc: Doc) {
        let d = dir(doc.id)
        if let data = try? JSONEncoder().encode(doc) {
            try? data.write(to: d.appendingPathComponent("doc.json"), options: .atomic)
        }
        try? FileManager.default.removeItem(at: d.appendingPathComponent("thumb.jpg"))
        // drop source images that are no longer referenced
        let used = Set(doc.pages.map { $0.src })
        for f in (try? FileManager.default.contentsOfDirectory(at: d, includingPropertiesForKeys: nil)) ?? []
        where f.lastPathComponent.hasPrefix("src_") && !used.contains(f.lastPathComponent) {
            try? FileManager.default.removeItem(at: f)
        }
    }

    func delete(_ id: String) {
        docs.removeAll { $0.id == id }
        try? FileManager.default.removeItem(at: Store.root.appendingPathComponent(id))
    }

    nonisolated static func newTitle() -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "dd-MM-yyyy HH.mm"
        return "JAMES Scan " + f.string(from: Date())
    }

    /// Save images as new pages. `detect` runs edge detection (for gallery imports);
    /// pages from the document camera are already cropped.
    nonisolated static func makePages(docId: String, images: [UIImage], detect: Bool) -> [Page] {
        var pages: [Page] = []
        for img in images {
            autoreleasepool {
                guard let cg = Processor.normalized(img, maxSide: 3000) else { return }
                let name = "src_\(UUID().uuidString.prefix(8)).jpg"
                let url = dir(docId).appendingPathComponent(name)
                guard let data = UIImage(cgImage: cg).jpegData(compressionQuality: 0.92) else { return }
                try? data.write(to: url)
                let q = detect ? Processor.detect(cg) : nil
                pages.append(Page(src: name, quad: q))
            }
        }
        return pages
    }
}

import Foundation
import UIKit
import Photos

enum ExportError: LocalizedError {
    case noPhotoAccess
    var errorDescription: String? {
        switch self {
        case .noPhotoAccess: return "ไม่ได้รับอนุญาตให้บันทึกรูป (ตั้งค่า › JAMES Scan › รูปภาพ)"
        }
    }
}

enum Exporter {
    static let exportSide = 2400

    static func safeName(_ title: String) -> String {
        let bad = CharacterSet(charactersIn: "\\/:*?\"<>|")
        let s = title.components(separatedBy: bad).joined(separator: "_").trimmingCharacters(in: .whitespaces)
        return s.isEmpty ? "scan" : s
    }

    /// Auto-save: every page that is new or was edited since its last save goes to Photos as a JPG.
    /// Returns the updated doc and how many photos were written.
    static func syncPhotos(_ doc: Doc) async throws -> (Doc, Int) {
        var d = doc
        let todo = d.pages.indices.filter { d.pages[$0].savedKey != d.pages[$0].renderKey }
        if todo.isEmpty { return (d, 0) }
        let status = await PHPhotoLibrary.requestAuthorization(for: .addOnly)
        guard status == .authorized || status == .limited else { throw ExportError.noPhotoAccess }
        var n = 0
        for i in todo {
            let page = d.pages[i]
            guard let img = Processor.render(page, docId: d.id, maxSide: exportSide),
                  let data = img.jpegData(compressionQuality: 0.92) else { continue }
            let fname = "\(safeName(d.title))_\(i + 1).jpg"
            try await PHPhotoLibrary.shared().performChanges {
                let req = PHAssetCreationRequest.forAsset()
                let opts = PHAssetResourceCreationOptions()
                opts.originalFilename = fname
                req.addResource(with: .photo, data: data, options: opts)
            }
            d.pages[i].savedKey = page.renderKey
            n += 1
        }
        return (d, n)
    }

    static func pdfData(_ doc: Doc) -> Data {
        let a4 = CGRect(x: 0, y: 0, width: 595, height: 842)
        let renderer = UIGraphicsPDFRenderer(bounds: a4)
        return renderer.pdfData { ctx in
            for p in doc.pages {
                autoreleasepool {
                    guard let img = Processor.render(p, docId: doc.id, maxSide: 2000) else { return }
                    let landscape = img.size.width > img.size.height
                    let page = landscape ? CGRect(x: 0, y: 0, width: 842, height: 595) : a4
                    ctx.beginPage(withBounds: page, pageInfo: [:])
                    UIColor.white.setFill()
                    UIRectFill(page)
                    let sc = min(page.width / img.size.width, page.height / img.size.height)
                    let w = img.size.width * sc, h = img.size.height * sc
                    // JPEG-compress each page so the PDF stays small
                    let jpg = img.jpegData(compressionQuality: 0.85).flatMap { UIImage(data: $0) } ?? img
                    jpg.draw(in: CGRect(x: (page.width - w) / 2, y: (page.height - h) / 2, width: w, height: h))
                }
            }
        }
    }

    /// Save into Files › On My iPhone › JAMES Scan › PDF
    static func savePDF(_ doc: Doc) throws -> URL {
        let dir = Store.exportDir.appendingPathComponent("PDF", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(safeName(doc.title) + ".pdf")
        try pdfData(doc).write(to: url, options: .atomic)
        return url
    }

    static func sharePDF(_ doc: Doc) throws -> [URL] {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent(safeName(doc.title) + ".pdf")
        try pdfData(doc).write(to: url)
        return [url]
    }

    static func shareImages(_ doc: Doc) throws -> [URL] {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("share", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var urls: [URL] = []
        for (i, p) in doc.pages.enumerated() {
            guard let img = Processor.render(p, docId: doc.id, maxSide: exportSide),
                  let data = img.jpegData(compressionQuality: 0.9) else { continue }
            let url = dir.appendingPathComponent("\(safeName(doc.title))_\(i + 1).jpg")
            try data.write(to: url)
            urls.append(url)
        }
        return urls
    }
}

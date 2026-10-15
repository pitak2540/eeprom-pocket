import Foundation
import UIKit
import CoreImage
import CoreImage.CIFilterBuiltins
import ImageIO
import Vision

enum Processor {
    static let ctx = CIContext(options: [.cacheIntermediates: false])

    // MARK: - loading

    /// Load a JPEG downsampled so its longest side is at most `maxSide`.
    static func load(_ url: URL, maxSide: Int) -> CGImage? {
        guard let src = CGImageSourceCreateWithURL(url as CFURL, nil) else { return nil }
        let opts: [CFString: Any] = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceThumbnailMaxPixelSize: maxSide,
        ]
        return CGImageSourceCreateThumbnailAtIndex(src, 0, opts as CFDictionary)
    }

    /// Draw a UIImage upright (orientation applied) and limit its size.
    static func normalized(_ img: UIImage, maxSide: CGFloat) -> CGImage? {
        let w = img.size.width, h = img.size.height
        guard w > 0, h > 0 else { return nil }
        let sc = min(1, maxSide / max(w * img.scale, h * img.scale)) * img.scale
        let size = CGSize(width: floor(w * sc), height: floor(h * sc))
        let fmt = UIGraphicsImageRendererFormat()
        fmt.scale = 1
        fmt.opaque = true
        let out = UIGraphicsImageRenderer(size: size, format: fmt).image { _ in
            img.draw(in: CGRect(origin: .zero, size: size))
        }
        return out.cgImage
    }

    // MARK: - pipeline

    static func base(_ page: Page, docId: String, maxSide: Int) -> CGImage? {
        guard let src = load(Store.dir(docId).appendingPathComponent(page.src), maxSide: maxSide) else { return nil }
        return rotate(warp(src, quad: page.quad), degrees: page.rotation)
    }

    static func render(_ page: Page, docId: String, maxSide: Int) -> UIImage? {
        guard let b = base(page, docId: docId, maxSide: maxSide) else { return nil }
        return UIImage(cgImage: apply(page.filter, b))
    }

    static func warp(_ cg: CGImage, quad: Quad?) -> CGImage {
        guard let q = quad, q != .full else { return cg }
        let w = CGFloat(cg.width), h = CGFloat(cg.height)
        // Core Image uses a bottom-left origin
        func v(_ p: CGPoint) -> CGPoint { CGPoint(x: p.x * w, y: (1 - p.y) * h) }
        let f = CIFilter.perspectiveCorrection()
        f.inputImage = CIImage(cgImage: cg)
        f.topLeft = v(q.tl)
        f.topRight = v(q.tr)
        f.bottomRight = v(q.br)
        f.bottomLeft = v(q.bl)
        guard let out = f.outputImage, out.extent.width >= 16, out.extent.height >= 16 else { return cg }
        return ctx.createCGImage(out, from: out.extent.integral) ?? cg
    }

    static func rotate(_ cg: CGImage, degrees: Int) -> CGImage {
        let d = ((degrees % 360) + 360) % 360
        if d == 0 { return cg }
        let o: CGImagePropertyOrientation = d == 90 ? .right : (d == 180 ? .down : .left)
        let img = CIImage(cgImage: cg).oriented(o)
        return ctx.createCGImage(img, from: img.extent) ?? cg
    }

    static func scaled(_ cg: CGImage, maxSide: CGFloat) -> CGImage {
        let m = CGFloat(max(cg.width, cg.height))
        if m <= maxSide { return cg }
        let s = maxSide / m
        let img = CIImage(cgImage: cg).transformed(by: CGAffineTransform(scaleX: s, y: s))
        return ctx.createCGImage(img, from: img.extent.integral) ?? cg
    }

    // MARK: - edge detection (Apple Vision)

    static func detect(_ cg: CGImage) -> Quad? {
        let handler = VNImageRequestHandler(cgImage: cg, options: [:])
        let seg = VNDetectDocumentSegmentationRequest()
        let rect = VNDetectRectanglesRequest()
        rect.minimumSize = 0.2
        rect.maximumObservations = 1
        rect.minimumConfidence = 0.5
        rect.quadratureTolerance = 30
        try? handler.perform([seg, rect])
        let obs: VNRectangleObservation? = seg.results?.first ?? rect.results?.first
        guard let o = obs, o.confidence > 0.3 else { return nil }
        func p(_ v: CGPoint) -> CGPoint { CGPoint(x: min(max(v.x, 0), 1), y: min(max(1 - v.y, 0), 1)) }
        let q = Quad(tl: p(o.topLeft), tr: p(o.topRight), br: p(o.bottomRight), bl: p(o.bottomLeft))
        // ignore tiny or degenerate results
        let area = abs((q.tr.x - q.tl.x) * (q.bl.y - q.tl.y))
        return area < 0.08 ? nil : q
    }

    // MARK: - filters

    static func apply(_ f: FilterKind, _ cg: CGImage) -> CGImage {
        switch f {
        case .original: return cg
        case .enhance: return enhance(cg)
        case .noshadow: return noShadow(cg)
        case .magic: return magic(cg)
        case .gray: return gray(cg)
        case .bw: return blackWhite(cg)
        }
    }

    /// RGBA8 pixel buffer, row 0 = top.
    struct Pixels {
        var w: Int
        var h: Int
        var data: [UInt8]

        init(_ cg: CGImage) {
            w = cg.width; h = cg.height
            data = [UInt8](repeating: 255, count: w * h * 4)
            let cs = CGColorSpaceCreateDeviceRGB()
            let ww = w, hh = h
            data.withUnsafeMutableBytes { ptr in
                if let c = CGContext(data: ptr.baseAddress, width: ww, height: hh, bitsPerComponent: 8, bytesPerRow: ww * 4,
                                     space: cs, bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue) {
                    c.interpolationQuality = .high
                    c.draw(cg, in: CGRect(x: 0, y: 0, width: ww, height: hh))
                }
            }
        }

        func image() -> CGImage? {
            let cs = CGColorSpaceCreateDeviceRGB()
            guard let provider = CGDataProvider(data: Data(data) as CFData) else { return nil }
            return CGImage(width: w, height: h, bitsPerComponent: 8, bitsPerPixel: 32, bytesPerRow: w * 4, space: cs,
                           bitmapInfo: CGBitmapInfo(rawValue: CGImageAlphaInfo.noneSkipLast.rawValue),
                           provider: provider, decode: nil, shouldInterpolate: true, intent: .defaultIntent)
        }
    }

    /// Paper background (lighting + shadows): shrink, max-filter (removes ink), blur, grow back.
    static func background(_ cg: CGImage) -> Pixels {
        let w = CGFloat(cg.width), h = CGFloat(cg.height)
        let s: CGFloat = 0.25
        let ci = CIImage(cgImage: cg)
        let small = ci.transformed(by: CGAffineTransform(scaleX: s, y: s))
        let maxF = CIFilter.morphologyMaximum()
        maxF.inputImage = small.clampedToExtent()
        maxF.radius = 4
        let blur = CIFilter.gaussianBlur()
        blur.inputImage = maxF.outputImage
        blur.radius = 10
        let back = (blur.outputImage ?? small)
            .cropped(to: small.extent)
            .clampedToExtent()
            .transformed(by: CGAffineTransform(scaleX: 1 / s, y: 1 / s))
            .cropped(to: CGRect(x: 0, y: 0, width: w, height: h))
        let bgCG = ctx.createCGImage(back, from: CGRect(x: 0, y: 0, width: w, height: h)) ?? cg
        return Pixels(bgCG)
    }

    static func noShadowPixels(_ cg: CGImage) -> Pixels {
        var p = Pixels(cg)
        let bg = background(cg)
        let n = p.w * p.h * 4
        p.data.withUnsafeMutableBufferPointer { d in
            bg.data.withUnsafeBufferPointer { b in
                var i = 0
                while i < n {
                    for c in 0..<3 {
                        let bv = max(Int(b[i + c]), 1)
                        d[i + c] = UInt8(min(255, Int(d[i + c]) * 255 / bv))
                    }
                    d[i + 3] = 255
                    i += 4
                }
            }
        }
        return p
    }

    static func noShadow(_ cg: CGImage) -> CGImage {
        noShadowPixels(cg).image() ?? cg
    }

    static func magic(_ cg: CGImage) -> CGImage {
        var p = noShadowPixels(cg)
        // levels: <=60 -> black, >=235 -> white
        let lut: [UInt8] = (0..<256).map { v in UInt8(min(255, max(0, (v - 60) * 255 / 175))) }
        let n = p.w * p.h * 4
        p.data.withUnsafeMutableBufferPointer { d in
            var i = 0
            while i < n { d[i] = lut[Int(d[i])]; d[i + 1] = lut[Int(d[i + 1])]; d[i + 2] = lut[Int(d[i + 2])]; i += 4 }
        }
        guard let stretched = p.image() else { return cg }
        let cc = CIFilter.colorControls()
        cc.inputImage = CIImage(cgImage: stretched)
        cc.saturation = 1.35
        cc.contrast = 1.0
        let sh = CIFilter.sharpenLuminance()
        sh.inputImage = cc.outputImage
        sh.sharpness = 0.6
        guard let out = sh.outputImage else { return stretched }
        return ctx.createCGImage(out, from: CIImage(cgImage: stretched).extent) ?? stretched
    }

    static func enhance(_ cg: CGImage) -> CGImage {
        let ci = CIImage(cgImage: cg)
        let hs = CIFilter.highlightShadowAdjust()
        hs.inputImage = ci
        hs.shadowAmount = 0.5
        hs.highlightAmount = 1
        let cc = CIFilter.colorControls()
        cc.inputImage = hs.outputImage
        cc.contrast = 1.12
        cc.brightness = 0.03
        cc.saturation = 1.08
        let sh = CIFilter.sharpenLuminance()
        sh.inputImage = cc.outputImage
        sh.sharpness = 0.5
        guard let out = sh.outputImage else { return cg }
        return ctx.createCGImage(out, from: ci.extent) ?? cg
    }

    /// luminance after shadow removal, one byte per pixel
    static func grayPlane(_ cg: CGImage) -> (w: Int, h: Int, g: [UInt8]) {
        let p = noShadowPixels(cg)
        var g = [UInt8](repeating: 255, count: p.w * p.h)
        p.data.withUnsafeBufferPointer { d in
            for k in 0..<(p.w * p.h) {
                let i = k * 4
                g[k] = UInt8((Int(d[i]) * 299 + Int(d[i + 1]) * 587 + Int(d[i + 2]) * 114) / 1000)
            }
        }
        return (p.w, p.h, g)
    }

    static func fromGray(_ w: Int, _ h: Int, _ g: [UInt8]) -> CGImage? {
        var p = Pixels(w: w, h: h, data: [UInt8](repeating: 255, count: w * h * 4))
        for k in 0..<(w * h) { let v = g[k]; p.data[k * 4] = v; p.data[k * 4 + 1] = v; p.data[k * 4 + 2] = v }
        return p.image()
    }

    static func gray(_ cg: CGImage) -> CGImage {
        let plane = grayPlane(cg)
        let w = plane.w, h = plane.h
        var g = plane.g
        for k in 0..<g.count { g[k] = UInt8(min(255, max(0, Int(Double(g[k]) * 1.15 - 20)))) }
        return fromGray(w, h, g) ?? cg
    }

    /// adaptive threshold using an integral image
    static func blackWhite(_ cg: CGImage) -> CGImage {
        let (w, h, g) = grayPlane(cg)
        var integ = [Int](repeating: 0, count: (w + 1) * (h + 1))
        for y in 0..<h {
            var row = 0
            for x in 0..<w {
                row += Int(g[y * w + x])
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row
            }
        }
        let r = max(7, max(w, h) / 120)
        var out = [UInt8](repeating: 255, count: w * h)
        for y in 0..<h {
            let y0 = max(0, y - r), y1 = min(h - 1, y + r)
            for x in 0..<w {
                let x0 = max(0, x - r), x1 = min(w - 1, x + r)
                let sum = integ[(y1 + 1) * (w + 1) + x1 + 1] - integ[y0 * (w + 1) + x1 + 1]
                    - integ[(y1 + 1) * (w + 1) + x0] + integ[y0 * (w + 1) + x0]
                let cnt = (y1 - y0 + 1) * (x1 - x0 + 1)
                out[y * w + x] = Int(g[y * w + x]) * cnt < sum - 12 * cnt ? 0 : 255
            }
        }
        return fromGray(w, h, out) ?? cg
    }
}

extension Processor.Pixels {
    init(w: Int, h: Int, data: [UInt8]) {
        self.w = w; self.h = h; self.data = data
    }
}

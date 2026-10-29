// Generates Play Home's six built-in wallpapers (1920x1080 sRGB JPEG, the TV's fastest format).
// They're drawn entirely in code from a fixed seed, so they're original, reproducible and free of
// anyone else's artwork.
//
//   swift tools/wallpapers.swift app/src/main/res/drawable-nodpi
//
import CoreGraphics
import Foundation
import ImageIO
import UniformTypeIdentifiers

let W = 1920, H = 1080
let out = CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : "."
let srgb = CGColorSpace(name: CGColorSpace.sRGB)!

/// Deterministic random numbers (a 64-bit LCG), so every run draws the same pictures.
struct Rand {
    var s: UInt64
    mutating func next() -> Double {
        s = s &* 6364136223846793005 &+ 1442695040888963407
        return Double(s >> 11) / Double(1 << 53)
    }
    mutating func range(_ a: Double, _ b: Double) -> Double { a + (b - a) * next() }
}

func rgb(_ r: Double, _ g: Double, _ b: Double, _ a: Double = 1) -> CGColor {
    CGColor(colorSpace: srgb, components: [r / 255, g / 255, b / 255, a])!
}

func context() -> CGContext {
    let c = CGContext(data: nil, width: W, height: H, bitsPerComponent: 8, bytesPerRow: 0, space: srgb,
                      bitmapInfo: CGImageAlphaInfo.premultipliedLast.rawValue)!
    c.translateBy(x: 0, y: CGFloat(H)) // top-left origin, like Android
    c.scaleBy(x: 1, y: -1)
    return c
}

func linear(_ c: CGContext, _ colors: [CGColor], from: CGPoint, to: CGPoint) {
    let g = CGGradient(colorsSpace: srgb, colors: colors as CFArray, locations: nil)!
    c.drawLinearGradient(g, start: from, end: to, options: [.drawsBeforeStartLocation, .drawsAfterEndLocation])
}

func radial(_ c: CGContext, _ colors: [CGColor], at p: CGPoint, radius: CGFloat, scaleY: CGFloat = 1) {
    let g = CGGradient(colorsSpace: srgb, colors: colors as CFArray, locations: nil)!
    c.saveGState()
    c.translateBy(x: p.x, y: p.y)
    c.scaleBy(x: 1, y: scaleY)
    c.drawRadialGradient(g, startCenter: .zero, startRadius: 0, endCenter: .zero, endRadius: radius, options: [])
    c.restoreGState()
}

/// The shared deep-blue base every wallpaper starts from.
func base(_ c: CGContext) {
    linear(c, [rgb(2, 6, 18), rgb(6, 22, 66), rgb(3, 10, 32)], from: CGPoint(x: 0, y: 0), to: CGPoint(x: 0, y: CGFloat(H)))
}

/// A soft round light: a radial gradient fading to nothing.
func softDot(_ c: CGContext, _ x: Double, _ y: Double, _ r: Double, _ color: (Double, Double, Double), _ a: Double) {
    radial(c, [rgb(color.0, color.1, color.2, a), rgb(color.0, color.1, color.2, a * 0.35), rgb(color.0, color.1, color.2, 0)],
           at: CGPoint(x: x, y: y), radius: CGFloat(r))
}

func save(_ c: CGContext, _ name: String) {
    let url = URL(fileURLWithPath: out).appendingPathComponent("wp_\(name).jpg")
    let dest = CGImageDestinationCreateWithURL(url as CFURL, UTType.jpeg.identifier as CFString, 1, nil)!
    CGImageDestinationAddImage(dest, c.makeImage()!, [kCGImageDestinationLossyCompressionQuality: 0.88] as CFDictionary)
    CGImageDestinationFinalize(dest)
    print("wrote \(url.path)")
}

/// A ribbon of thin sine lines, each slightly out of phase: the "mesh" waves.
func ribbon(_ c: CGContext, mid: Double, amp: Double, lines: Int, spread: Double, phase: Double, alpha: Double, width: CGFloat) {
    for i in 0..<lines {
        let t = Double(i) / Double(lines - 1)
        let path = CGMutablePath()
        var x = -20.0
        while x <= Double(W) + 20 {
            let k = x / Double(W)
            let y = mid + amp * sin(k * 2 * .pi * 1.1 + phase + t * 1.4) + (t - 0.5) * spread * sin(k * .pi * 1.3 + 0.6)
            if x == -20 { path.move(to: CGPoint(x: x, y: y)) } else { path.addLine(to: CGPoint(x: x, y: y)) }
            x += 8
        }
        c.addPath(path)
        let edge = 1 - abs(t - 0.5) * 2 // brightest in the middle of the ribbon
        c.setStrokeColor(rgb(150, 200, 255, alpha * (0.35 + 0.65 * edge)))
        c.setLineWidth(width)
        c.strokePath()
    }
}

// 1. Waves: a mesh ribbon across a soft glow.
do {
    let c = context(); base(c)
    radial(c, [rgb(40, 110, 230, 0.55), rgb(20, 60, 160, 0.15), rgb(0, 0, 0, 0)], at: CGPoint(x: 700, y: 430), radius: 900, scaleY: 0.7)
    ribbon(c, mid: 560, amp: 70, lines: 70, spread: 120, phase: 0.4, alpha: 0.32, width: 1.2)
    save(c, "waves")
}

// 2. Particles: bokeh and fine dust.
do {
    let c = context(); base(c)
    radial(c, [rgb(30, 90, 220, 0.45), rgb(0, 0, 0, 0)], at: CGPoint(x: 1300, y: 300), radius: 1000, scaleY: 0.8)
    var r = Rand(s: 2)
    for _ in 0..<38 { softDot(c, r.range(0, 1920), r.range(0, 1080), r.range(30, 110), (90, 150, 255), r.range(0.06, 0.18)) }
    for _ in 0..<260 { softDot(c, r.range(0, 1920), r.range(0, 1080), r.range(2, 6), (210, 230, 255), r.range(0.4, 0.95)) }
    save(c, "particles")
}

// 3. Flow: a bright band of light with fine lines riding it.
do {
    let c = context(); base(c)
    radial(c, [rgb(110, 180, 255, 0.85), rgb(40, 110, 240, 0.4), rgb(0, 0, 0, 0)], at: CGPoint(x: 1050, y: 520), radius: 1100, scaleY: 0.16)
    radial(c, [rgb(220, 240, 255, 0.7), rgb(0, 0, 0, 0)], at: CGPoint(x: 1150, y: 500), radius: 260, scaleY: 0.35)
    ribbon(c, mid: 520, amp: 45, lines: 18, spread: 70, phase: 2.0, alpha: 0.5, width: 1.0)
    save(c, "flow")
}

// 4. Shapes: outlined triangles and circles drifting over a central glow.
do {
    let c = context(); base(c)
    radial(c, [rgb(30, 80, 200, 0.6), rgb(0, 0, 0, 0)], at: CGPoint(x: 960, y: 760), radius: 900, scaleY: 0.75)
    var r = Rand(s: 4)
    for i in 0..<34 {
        let x = r.range(0, 1920), y = r.range(0, 1080), s = r.range(16, 120), a = r.range(0.18, 0.5)
        c.setStrokeColor(rgb(170, 200, 255, a))
        c.setLineWidth(CGFloat(r.range(1.5, 3)))
        if i % 2 == 0 {
            c.strokeEllipse(in: CGRect(x: x - s / 2, y: y - s / 2, width: s, height: s))
        } else {
            let turn = r.range(0, 2 * .pi)
            let p = CGMutablePath()
            for k in 0..<3 {
                let ang = turn + Double(k) * 2 * .pi / 3
                let pt = CGPoint(x: x + cos(ang) * s / 2, y: y + sin(ang) * s / 2)
                if k == 0 { p.move(to: pt) } else { p.addLine(to: pt) }
            }
            p.closeSubpath()
            c.addPath(p); c.strokePath()
        }
    }
    save(c, "shapes")
}

// 5. Glow: a horizon of light under a field of stars.
do {
    let c = context()
    linear(c, [rgb(1, 3, 10), rgb(3, 8, 26), rgb(10, 40, 110)], from: CGPoint(x: 0, y: 0), to: CGPoint(x: 0, y: 1080))
    radial(c, [rgb(150, 200, 255, 0.95), rgb(50, 120, 240, 0.55), rgb(0, 0, 0, 0)], at: CGPoint(x: 960, y: 1080), radius: 760, scaleY: 0.62)
    var r = Rand(s: 5)
    for _ in 0..<180 { softDot(c, r.range(0, 1920), r.range(0, 900), r.range(1.5, 4), (220, 235, 255), r.range(0.3, 0.9)) }
    c.saveGState() // the horizon: a thin line, brightest in the middle
    c.clip(to: CGRect(x: 0, y: 968, width: 1920, height: 3))
    linear(c, [rgb(120, 190, 255, 0), rgb(220, 240, 255, 0.95), rgb(120, 190, 255, 0)], from: CGPoint(x: 0, y: 0), to: CGPoint(x: 1920, y: 0))
    c.restoreGState()
    save(c, "glow")
}

// 6. Lines: a burst of light rays from a bright core.
do {
    let c = context()
    linear(c, [rgb(1, 3, 10), rgb(4, 14, 44)], from: CGPoint(x: 0, y: 0), to: CGPoint(x: 0, y: 1080))
    let core = CGPoint(x: 1130, y: 420)
    var r = Rand(s: 6)
    for _ in 0..<420 {
        let ang = r.range(0, 2 * .pi), len = r.range(300, 1500), start = r.range(30, 160)
        c.setStrokeColor(rgb(140, 190, 255, r.range(0.05, 0.35)))
        c.setLineWidth(CGFloat(r.range(0.6, 2.2)))
        c.move(to: CGPoint(x: core.x + cos(ang) * start, y: core.y + sin(ang) * start))
        c.addLine(to: CGPoint(x: core.x + cos(ang) * len, y: core.y + sin(ang) * len))
        c.strokePath()
    }
    radial(c, [rgb(60, 120, 240, 0.6), rgb(0, 0, 0, 0)], at: core, radius: 520)
    radial(c, [rgb(240, 248, 255, 1), rgb(140, 200, 255, 0.8), rgb(0, 0, 0, 0)], at: core, radius: 120)
    save(c, "lines")
}

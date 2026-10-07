import AppKit
import Foundation
// Deterministic resampling only. The original root logo is never altered or cropped.
let root = URL(fileURLWithPath: CommandLine.arguments.count > 1 ? CommandLine.arguments[1] : FileManager.default.currentDirectoryPath)
let original = NSImage(contentsOf: root.appendingPathComponent("logo.png"))!
func render(_ width: Int, _ height: Int, _ relative: String, _ draw: (NSRect) -> Void) throws {
    let bitmap = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: width, pixelsHigh: height, bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false, colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0)!
    let context = NSGraphicsContext(bitmapImageRep: bitmap)!
    NSGraphicsContext.saveGraphicsState(); NSGraphicsContext.current = context
    context.imageInterpolation = .high
    draw(NSRect(x: 0, y: 0, width: width, height: height))
    NSGraphicsContext.restoreGraphicsState()
    let url = root.appendingPathComponent(relative)
    try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
    try bitmap.representation(using: .png, properties: [:])!.write(to: url)
}
for (density, scale) in [("mdpi",1.0),("hdpi",1.5),("xhdpi",2.0),("xxhdpi",3.0),("xxxhdpi",4.0)] {
    let size = Int(48 * scale)
    for name in ["ic_launcher", "ic_launcher_round"] {
        try render(size,size,"app/src/main/res/mipmap-\(density)/\(name).png") { rect in
            original.draw(in: rect, from: .zero, operation: .copy, fraction: 1)
        }
    }
    let foregroundSize = Int(108 * scale)
    try render(foregroundSize,foregroundSize,"app/src/main/res/mipmap-\(density)/ic_launcher_foreground.png") { rect in
        let side = CGFloat(46 * scale)
        original.draw(in: NSRect(x:(rect.width-side)/2,y:(rect.height-side)/2,width:side,height:side),from:.zero,operation:.sourceOver,fraction:1)
    }
    try render(Int(320*scale),Int(180*scale),"app/src/main/res/drawable-\(density)/tv_banner.png") { rect in
        NSColor(calibratedRed:0.055,green:0.063,blue:0.10,alpha:1).setFill(); NSBezierPath(rect:rect).fill()
        let side=CGFloat(116*scale)
        original.draw(in:NSRect(x:CGFloat(22*scale),y:(rect.height-side)/2,width:side,height:side),from:.zero,operation:.sourceOver,fraction:1)
        ("Yuno TV" as NSString).draw(at:NSPoint(x:CGFloat(157*scale),y:CGFloat(73*scale)),withAttributes:[.font:NSFont.systemFont(ofSize:CGFloat(30*scale),weight:.semibold),.foregroundColor:NSColor.white])
    }
}
try render(256,256,"core-ui/src/main/res/drawable-nodpi/yuno_logo.png") { original.draw(in:$0,from:.zero,operation:.copy,fraction:1) }

import AppKit
import Foundation

let output=URL(fileURLWithPath:CommandLine.arguments[1])
let folder=output.deletingLastPathComponent().appendingPathComponent("Spare.iconset")
try FileManager.default.createDirectory(at:folder,withIntermediateDirectories:true)
func icon(_ size:Int)->Data {
    let bitmap=NSBitmapImageRep(bitmapDataPlanes:nil,pixelsWide:size,pixelsHigh:size,bitsPerSample:8,samplesPerPixel:4,hasAlpha:true,isPlanar:false,colorSpaceName:.deviceRGB,bytesPerRow:0,bitsPerPixel:0)!
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current=NSGraphicsContext(bitmapImageRep:bitmap)
    let transform=NSAffineTransform();transform.scale(by:CGFloat(size)/1024);transform.concat()
    NSColor(calibratedRed:0.045,green:0.063,blue:0.09,alpha:1).setFill()
    NSBezierPath(roundedRect:NSRect(x:54,y:54,width:916,height:916),xRadius:200,yRadius:200).fill()
    let white=NSColor(calibratedRed:1,green:0.95,blue:0.86,alpha:1)
    let font=NSFont.systemFont(ofSize:164,weight:.semibold)
    let rounded=NSFont(descriptor:font.fontDescriptor.withDesign(.rounded)!,size:164)!
    ("spare" as NSString).draw(at:NSPoint(x:110,y:421),withAttributes:[.font:rounded,.foregroundColor:white,.kern:-7])
    white.setFill()
    for r in [NSRect(x:715,y:435,width:172,height:120),NSRect(x:725,y:425,width:152,height:10),
              NSRect(x:725,y:555,width:152,height:10),NSRect(x:735,y:413,width:20,height:12),
              NSRect(x:847,y:413,width:20,height:12),NSRect(x:761,y:575,width:12,height:12),
              NSRect(x:773,y:563,width:12,height:12),NSRect(x:829,y:575,width:12,height:12),
              NSRect(x:817,y:563,width:12,height:12)] { NSBezierPath(rect:r).fill() }
    NSColor(calibratedRed:0.045,green:0.063,blue:0.09,alpha:1).setFill()
    NSBezierPath(rect:NSRect(x:729,y:447,width:113,height:96)).fill()
    NSColor(calibratedRed:0.53,green:0.78,blue:0.71,alpha:1).setFill()
    NSBezierPath(rect:NSRect(x:739,y:457,width:93,height:76)).fill()
    NSColor(calibratedRed:0.045,green:0.063,blue:0.09,alpha:1).setFill()
    for y in [464,505] { NSBezierPath(rect:NSRect(x:857,y:y,width:13,height:13)).fill() }
    NSGraphicsContext.restoreGraphicsState()
    return bitmap.representation(using:.png,properties:[:])!
}
for size in [16,32,128,256,512] {
    try icon(size).write(to:folder.appendingPathComponent("icon_\(size)x\(size).png"))
    try icon(size*2).write(to:folder.appendingPathComponent("icon_\(size)x\(size)@2x.png"))
}
let process=Process();process.executableURL=URL(fileURLWithPath:"/usr/bin/iconutil")
process.arguments=["-c","icns",folder.path,"-o",output.path]
try process.run();process.waitUntilExit()
guard process.terminationStatus==0 else { exit(1) }
try FileManager.default.removeItem(at:folder)

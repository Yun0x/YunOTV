import AVFoundation
import CoreVideo
import Foundation

// Synthesizes an original test pattern. No external media or copyrighted content.
let output = URL(fileURLWithPath: CommandLine.arguments[1])
try? FileManager.default.removeItem(at: output)
let writer = try AVAssetWriter(outputURL: output, fileType: .mp4)
let input = AVAssetWriterInput(mediaType: .video, outputSettings: [
    AVVideoCodecKey: AVVideoCodecType.h264, AVVideoWidthKey: 640, AVVideoHeightKey: 360,
    AVVideoCompressionPropertiesKey: [AVVideoAverageBitRateKey: 150000, AVVideoMaxKeyFrameIntervalKey: 24]
])
let attributes: [String: Any] = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32ARGB,
    kCVPixelBufferWidthKey as String: 640, kCVPixelBufferHeightKey as String: 360]
let adaptor = AVAssetWriterInputPixelBufferAdaptor(assetWriterInput: input, sourcePixelBufferAttributes: attributes)
writer.add(input); writer.startWriting(); writer.startSession(atSourceTime: .zero)
for frame in 0..<288 {
    while !input.isReadyForMoreMediaData { Thread.sleep(forTimeInterval: 0.002) }
    var buffer: CVPixelBuffer?
    CVPixelBufferPoolCreatePixelBuffer(nil, adaptor.pixelBufferPool!, &buffer)
    let pixel = buffer!
    CVPixelBufferLockBaseAddress(pixel, [])
    let bytes = CVPixelBufferGetBaseAddress(pixel)!.assumingMemoryBound(to: UInt8.self)
    let stride = CVPixelBufferGetBytesPerRow(pixel)
    for y in 0..<360 {
        for x in 0..<640 {
            let offset = y * stride + x * 4
            bytes[offset] = 255; bytes[offset + 1] = UInt8(40 + frame % 120)
            bytes[offset + 2] = UInt8(90 + x / 6); bytes[offset + 3] = UInt8(80 + y / 3)
        }
    }
    CVPixelBufferUnlockBaseAddress(pixel, [])
    adaptor.append(pixel, withPresentationTime: CMTime(value: Int64(frame), timescale: 24))
}
input.markAsFinished()
let finished = DispatchSemaphore(value: 0)
writer.finishWriting { finished.signal() }; finished.wait()
guard writer.status == .completed else { fatalError("Video generation failed") }
print("Generated original 12 second H.264 test pattern")

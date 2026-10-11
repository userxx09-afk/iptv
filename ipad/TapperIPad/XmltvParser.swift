import Compression
import Foundation

/// XMLTV timestamps: `YYYYMMDDHHMMSS +HHMM`, everything after the year
/// optional, the offset often absent or wrong. Swift port of the shared
/// XmltvStamp. Returns epoch seconds UTC, or nil if unparseable.
enum XmltvStamp {
    static func parse(_ raw: String) -> Int? {
        let bytes = Array(raw.utf8)
        var i = 0
        while i < bytes.count, bytes[i] == 0x20 { i += 1 }
        var digits: [Int] = []
        while i < bytes.count, bytes[i] >= 0x30, bytes[i] <= 0x39 {
            digits.append(Int(bytes[i] - 0x30))
            i += 1
        }
        if digits.count < 8 { return nil }

        func num(_ from: Int, _ to: Int) -> Int? {
            if digits.count < to { return nil }
            var n = 0
            for k in from..<to { n = n * 10 + digits[k] }
            return n
        }
        let year = num(0, 4) ?? 0
        let month = num(4, 6) ?? 0
        let day = num(6, 8) ?? 0
        let hour = num(8, 10) ?? 0
        let minute = num(10, 12) ?? 0
        let second = num(12, 14) ?? 0
        if !(1...12).contains(month) || !(1...31).contains(day) || hour > 23 || minute > 59 || second > 60 {
            return nil
        }

        let base = epochSeconds(year, month, day, hour, minute, second)

        // Offset: "+HHMM" / "-HHMM" after optional spaces.
        var offset = 0
        while i < bytes.count, bytes[i] == 0x20 { i += 1 }
        if i < bytes.count, bytes[i] == 0x2B || bytes[i] == 0x2D {
            let sign = bytes[i] == 0x2B ? 1 : -1
            i += 1
            var d: [Int] = []
            while i < bytes.count, bytes[i] >= 0x30, bytes[i] <= 0x39 {
                d.append(Int(bytes[i] - 0x30))
                i += 1
            }
            if d.count >= 4 {
                let h = d[0] * 10 + d[1]
                let m = d[2] * 10 + d[3]
                if h <= 14, m <= 59 { offset = sign * (h * 3600 + m * 60) }
            }
        }
        // The offset is local time's distance from UTC, so subtract it.
        return base - offset
    }

    /// Civil date to epoch seconds (days_from_civil) - no timezone database.
    static func epochSeconds(_ y: Int, _ m: Int, _ d: Int, _ hh: Int, _ mm: Int, _ ss: Int) -> Int {
        let yAdj = m <= 2 ? y - 1 : y
        let era = (yAdj >= 0 ? yAdj : yAdj - 399) / 400
        let yoe = yAdj - era * 400
        let mp = (m + 9) % 12
        let doy = (153 * mp + 2) / 5 + d - 1
        let doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        let days = era * 146_097 + doe - 719_468
        return days * 86_400 + hh * 3600 + mm * 60 + ss
    }
}

/// Streaming pull-parse of an XMLTV file. A real provider guide is 100-200MB
/// of XML; this never holds more than one programme at a time, and drops
/// everything it doesn't need (other channels, outside the time window)
/// without even collecting the text.
final class XmltvStreamParser: NSObject, XMLParserDelegate {
    private enum Collecting { case none, title, desc }

    /// Normalised guide ids to keep; nil keeps everything.
    private let wanted: Set<String>?
    private let floor: Int
    private let ceiling: Int
    private let maxRows: Int
    private let emit: (EpgProgramme) -> Void

    private(set) var emitted = 0
    private(set) var channelsSeen = Set<String>()

    private var keeping = false
    private var channel = ""
    private var start = 0
    private var stop = 0
    private var title: String?
    private var desc: String?
    private var collecting = Collecting.none
    private var buffer = ""

    init(wanted: Set<String>?, now: Int, maxRows: Int, emit: @escaping (EpgProgramme) -> Void) {
        self.wanted = wanted
        // A little history and about a day and a half ahead; the rest is
        // dead weight on a device with a 100MB+ guide.
        self.floor = now - 3 * 3600
        self.ceiling = now + 36 * 3600
        self.maxRows = maxRows
        self.emit = emit
    }

    func parser(_ parser: XMLParser, didStartElement elementName: String, namespaceURI: String?,
                qualifiedName qName: String?, attributes attributeDict: [String: String] = [:]) {
        switch elementName {
        case "programme":
            keeping = false
            guard let rawChannel = attributeDict["channel"] else { return }
            let id = EpgDatabase.normalizeId(rawChannel)
            if let wanted, !wanted.contains(id) { return }
            guard let s = attributeDict["start"].flatMap(XmltvStamp.parse),
                  let e = attributeDict["stop"].flatMap(XmltvStamp.parse),
                  e > s, e > floor, s < ceiling else { return }
            channel = id
            start = s
            stop = e
            title = nil
            desc = nil
            keeping = true
        case "title":
            // Multi-language guides repeat title per language; keep the first.
            if keeping, title == nil {
                collecting = .title
                buffer = ""
            }
        case "desc":
            if keeping, desc == nil {
                collecting = .desc
                buffer = ""
            }
        default:
            break
        }
    }

    func parser(_ parser: XMLParser, foundCharacters string: String) {
        if collecting != .none { buffer += string }
    }

    func parser(_ parser: XMLParser, didEndElement elementName: String, namespaceURI: String?, qualifiedName qName: String?) {
        switch elementName {
        case "title":
            if collecting == .title {
                title = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
                collecting = .none
            }
        case "desc":
            if collecting == .desc {
                let text = buffer.trimmingCharacters(in: .whitespacesAndNewlines)
                // Long synopses are cut: this is a TV listing, not the full text.
                desc = text.isEmpty ? nil : String(text.prefix(400))
                collecting = .none
            }
        case "programme":
            if keeping {
                let name = (title?.isEmpty == false ? title : nil) ?? "No information"
                emit(EpgProgramme(channelId: channel, start: start, end: stop, title: name, description: desc))
                emitted += 1
                channelsSeen.insert(channel)
                keeping = false
                if emitted >= maxRows { parser.abortParsing() }
            }
        default:
            break
        }
    }
}

/// Downloads a guide to a temporary file (never into memory), unzips it if it
/// is gzip, and hands back the XML file to parse. Guides are commonly served
/// as .xml.gz with no Content-Encoding header, and equally often as plain
/// .xml behind a .gz name - so it sniffs the first bytes rather than trusting
/// either.
enum GuideFile {
    enum GuideError: LocalizedError {
        case http(Int)
        case badGzip
        case empty

        var errorDescription: String? {
            switch self {
            case .http(let code): return "HTTP \(code)"
            case .badGzip: return "the guide file wasn't readable"
            case .empty: return "the guide was empty"
            }
        }
    }

    /// Returns the URL of a plain-XML temporary file. The caller deletes it.
    static func fetchXml(from url: URL) async throws -> URL {
        var request = URLRequest(url: url, timeoutInterval: 180)
        request.setValue("TapperIPTV-iPad", forHTTPHeaderField: "User-Agent")
        let (tmp, response) = try await URLSession.shared.download(for: request)
        if let http = response as? HTTPURLResponse, !(200...299).contains(http.statusCode) {
            try? FileManager.default.removeItem(at: tmp)
            throw GuideError.http(http.statusCode)
        }
        defer { try? FileManager.default.removeItem(at: tmp) }

        let handle = try FileHandle(forReadingFrom: tmp)
        let magic = try handle.read(upToCount: 2) ?? Data()
        try handle.close()

        let xml = FileManager.default.temporaryDirectory
            .appendingPathComponent("tapper-guide-\(UUID().uuidString).xml")
        if magic.count == 2, magic[magic.startIndex] == 0x1F, magic[magic.startIndex + 1] == 0x8B {
            try gunzip(from: tmp, to: xml)
        } else {
            try FileManager.default.moveItem(at: tmp, to: xml)
        }
        return xml
    }

    /// Streams a .gz file to disk in 64KB pieces. gzip is a small header
    /// around a raw deflate stream, which is what Apple's Compression
    /// framework decodes (its "ZLIB" is raw deflate), so the header is
    /// skipped by hand and the rest fed through.
    static func gunzip(from source: URL, to destination: URL) throws {
        let input = try FileHandle(forReadingFrom: source)
        defer { try? input.close() }

        guard let head = try input.read(upToCount: 10), head.count == 10 else { throw GuideError.badGzip }
        let h = [UInt8](head)
        guard h[0] == 0x1F, h[1] == 0x8B, h[2] == 8 else { throw GuideError.badGzip }
        let flags = h[3]
        func readByte() throws -> UInt8? {
            guard let d = try input.read(upToCount: 1), let b = d.first else { return nil }
            return b
        }
        if flags & 0x04 != 0 {  // extra field
            guard let lo = try readByte(), let hi = try readByte() else { throw GuideError.badGzip }
            let n = Int(lo) | (Int(hi) << 8)
            _ = try input.read(upToCount: n)
        }
        if flags & 0x08 != 0 {  // file name, zero-terminated
            while let b = try readByte(), b != 0 {}
        }
        if flags & 0x10 != 0 {  // comment, zero-terminated
            while let b = try readByte(), b != 0 {}
        }
        if flags & 0x02 != 0 {  // header checksum
            _ = try input.read(upToCount: 2)
        }

        FileManager.default.createFile(atPath: destination.path, contents: nil)
        let output = try FileHandle(forWritingTo: destination)
        defer { try? output.close() }

        let bufferSize = 64 * 1024
        let dstBuffer = UnsafeMutablePointer<UInt8>.allocate(capacity: bufferSize)
        defer { dstBuffer.deallocate() }

        var stream = compression_stream(
            dst_ptr: dstBuffer, dst_size: 0,
            src_ptr: UnsafePointer(dstBuffer), src_size: 0,
            state: nil
        )
        guard compression_stream_init(&stream, COMPRESSION_STREAM_DECODE, COMPRESSION_ZLIB) == COMPRESSION_STATUS_OK else {
            throw GuideError.badGzip
        }
        defer { compression_stream_destroy(&stream) }

        var finished = false
        while !finished {
            guard let chunk = try input.read(upToCount: bufferSize), !chunk.isEmpty else { break }
            try chunk.withUnsafeBytes { (raw: UnsafeRawBufferPointer) in
                guard let base = raw.bindMemory(to: UInt8.self).baseAddress else { return }
                stream.src_ptr = base
                stream.src_size = chunk.count
                repeat {
                    stream.dst_ptr = dstBuffer
                    stream.dst_size = bufferSize
                    let status = compression_stream_process(&stream, 0)
                    switch status {
                    case COMPRESSION_STATUS_OK, COMPRESSION_STATUS_END:
                        let produced = bufferSize - stream.dst_size
                        if produced > 0 {
                            try output.write(contentsOf: Data(bytes: dstBuffer, count: produced))
                        }
                        if status == COMPRESSION_STATUS_END { finished = true }
                    default:
                        throw GuideError.badGzip
                    }
                } while !finished && (stream.src_size > 0 || stream.dst_size == 0)
            }
        }
    }

    /// Parses an XMLTV file, calling [emit] for each kept programme. Returns
    /// the parser so the caller can read counts.
    static func parse(file: URL, wanted: Set<String>?, now: Int, maxRows: Int,
                      emit: @escaping (EpgProgramme) -> Void) -> XmltvStreamParser? {
        guard let stream = InputStream(url: file) else { return nil }
        let delegate = XmltvStreamParser(wanted: wanted, now: now, maxRows: maxRows, emit: emit)
        let parser = XMLParser(stream: stream)
        parser.delegate = delegate
        parser.shouldProcessNamespaces = false
        parser.parse()
        return delegate
    }
}

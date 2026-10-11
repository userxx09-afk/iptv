import Foundation
import SQLite3

/// One programme in a guide. Times are epoch seconds (UTC).
struct EpgProgramme: Equatable {
    let channelId: String
    let start: Int
    let end: Int
    let title: String
    let description: String?

    func progress(at now: Int) -> Double {
        let span = max(end - start, 1)
        return min(max(Double(now - start) / Double(span), 0), 1)
    }

    func isAiring(at now: Int) -> Bool { start <= now && now < end }

    var startDate: Date { Date(timeIntervalSince1970: TimeInterval(start)) }
    var endDate: Date { Date(timeIntervalSince1970: TimeInterval(end)) }
}

private let sqliteTransient = unsafeBitCast(-1, to: sqlite3_destructor_type.self)

/// Guide storage - the iPad counterpart of Fire TV's EpgDatabase, same table
/// shape. A full provider guide is hundreds of thousands of rows, far too many
/// to hold in memory, so it lives in SQLite and only what is on screen is
/// ever read.
///
/// Two connections to the same file (write-ahead logging), each used from its
/// own serial queue: a long guide import on the write queue never blocks the
/// "what's on now" reads on the read queue.
final class EpgDatabase: @unchecked Sendable {
    static let shared = EpgDatabase()

    /// Guide ids are matched loosely on purpose: a panel reports
    /// epg_channel_id as "CNN.us" while its own XMLTV writes "cnn.us".
    static func normalizeId(_ raw: String?) -> String {
        (raw ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }

    private let writeQueue = DispatchQueue(label: "io.tapper.epg.write", qos: .utility)
    private let readQueue = DispatchQueue(label: "io.tapper.epg.read", qos: .userInitiated)
    private var writeDb: OpaquePointer?
    private var readDb: OpaquePointer?

    private init() {
        let fm = FileManager.default
        let base = (try? fm.url(for: .applicationSupportDirectory, in: .userDomainMask,
                                appropriateFor: nil, create: true))
            ?? fm.temporaryDirectory
        let path = base.appendingPathComponent("tapper-epg.sqlite3").path

        writeQueue.sync {
            writeDb = Self.open(path)
            Self.exec(writeDb, "PRAGMA journal_mode=WAL")
            Self.exec(writeDb, "PRAGMA synchronous=NORMAL")
            Self.exec(writeDb, """
                CREATE TABLE IF NOT EXISTS epg (
                  source_id TEXT NOT NULL,
                  ch        TEXT NOT NULL,
                  start_utc INTEGER NOT NULL,
                  end_utc   INTEGER NOT NULL,
                  title     TEXT NOT NULL,
                  descr     TEXT
                )
                """)
            // UNIQUE: providers repeat a <programme> node now and then, and a
            // schedule list keyed on (channel, start) must never see two.
            Self.exec(writeDb, "CREATE UNIQUE INDEX IF NOT EXISTS epg_lookup ON epg(source_id, ch, start_utc)")
            Self.exec(writeDb, "CREATE INDEX IF NOT EXISTS epg_window ON epg(source_id, start_utc, end_utc)")
            Self.exec(writeDb, "CREATE TABLE IF NOT EXISTS epg_meta (source_id TEXT PRIMARY KEY, fetched_utc INTEGER NOT NULL)")
        }
        readQueue.sync {
            readDb = Self.open(path)
        }
    }

    // MARK: - SQLite helpers

    private static func open(_ path: String) -> OpaquePointer? {
        var db: OpaquePointer?
        let flags = SQLITE_OPEN_READWRITE | SQLITE_OPEN_CREATE | SQLITE_OPEN_FULLMUTEX
        if sqlite3_open_v2(path, &db, flags, nil) != SQLITE_OK {
            AppLog.shared.log("EPG", "Couldn't open the guide database")
            return nil
        }
        sqlite3_busy_timeout(db, 8000)
        return db
    }

    @discardableResult
    private static func exec(_ db: OpaquePointer?, _ sql: String) -> Bool {
        guard let db else { return false }
        var err: UnsafeMutablePointer<CChar>?
        let rc = sqlite3_exec(db, sql, nil, nil, &err)
        if rc != SQLITE_OK {
            let message = err.map { String(cString: $0) } ?? "error \(rc)"
            AppLog.shared.log("EPG", "SQL failed: \(message)")
            sqlite3_free(err)
            return false
        }
        return true
    }

    private enum Bind {
        case text(String)
        case int(Int)
    }

    private static func bind(_ stmt: OpaquePointer?, _ values: [Bind]) {
        for (i, value) in values.enumerated() {
            let idx = Int32(i + 1)
            switch value {
            case .text(let s): sqlite3_bind_text(stmt, idx, s, -1, sqliteTransient)
            case .int(let n): sqlite3_bind_int64(stmt, idx, Int64(n))
            }
        }
    }

    private static func text(_ stmt: OpaquePointer?, _ col: Int32) -> String? {
        guard sqlite3_column_type(stmt, col) != SQLITE_NULL, let c = sqlite3_column_text(stmt, col) else { return nil }
        return String(cString: c)
    }

    private static func programme(_ stmt: OpaquePointer?) -> EpgProgramme {
        EpgProgramme(
            channelId: text(stmt, 0) ?? "",
            start: Int(sqlite3_column_int64(stmt, 1)),
            end: Int(sqlite3_column_int64(stmt, 2)),
            title: text(stmt, 3) ?? "",
            description: text(stmt, 4)
        )
    }

    /// Runs a read on the read connection and collects the rows.
    private func select<T>(_ sql: String, _ binds: [Bind], _ map: @escaping (OpaquePointer?) -> T) -> [T] {
        readQueue.sync {
            var out: [T] = []
            var stmt: OpaquePointer?
            guard sqlite3_prepare_v2(readDb, sql, -1, &stmt, nil) == SQLITE_OK else { return out }
            defer { sqlite3_finalize(stmt) }
            Self.bind(stmt, binds)
            while sqlite3_step(stmt) == SQLITE_ROW {
                out.append(map(stmt))
            }
            return out
        }
    }

    // MARK: - Reads

    func fetchedAt(_ id: String) -> Int {
        select("SELECT fetched_utc FROM epg_meta WHERE source_id = ?", [.text(id)]) {
            Int(sqlite3_column_int64($0, 0))
        }.first ?? 0
    }

    func countFor(_ sourceId: String) -> Int {
        select("SELECT COUNT(*) FROM epg WHERE source_id = ?", [.text(sourceId)]) {
            Int(sqlite3_column_int64($0, 0))
        }.first ?? 0
    }

    /// Distinct guide channel ids, so Settings can report match coverage.
    func guideChannelIds(_ sourceId: String) -> Set<String> {
        Set(select("SELECT DISTINCT ch FROM epg WHERE source_id = ?", [.text(sourceId)]) {
            Self.text($0, 0) ?? ""
        })
    }

    /// What is on right now, for every channel at once - one query for the
    /// whole list rather than one per row.
    func nowPlaying(_ sourceId: String, now: Int) -> [String: EpgProgramme] {
        let rows = select(
            "SELECT ch, start_utc, end_utc, title, descr FROM epg WHERE source_id = ? AND start_utc <= ? AND end_utc > ?",
            [.text(sourceId), .int(now), .int(now)]
        ) { Self.programme($0) }
        var out: [String: EpgProgramme] = [:]
        out.reserveCapacity(rows.count)
        for row in rows { out[row.channelId] = row }
        return out
    }

    func upcoming(_ sourceId: String, channelId: String, now: Int, limit: Int = 30) -> [EpgProgramme] {
        select(
            "SELECT ch, start_utc, end_utc, title, descr FROM epg WHERE source_id = ? AND ch = ? AND end_utc > ? ORDER BY start_utc LIMIT ?",
            [.text(sourceId), .text(Self.normalizeId(channelId)), .int(now), .int(limit)]
        ) { Self.programme($0) }
    }

    /// Programme-title search, restricted to what is airing now or later.
    func search(_ sourceId: String, query: String, now: Int, limit: Int = 60) -> [EpgProgramme] {
        select(
            "SELECT ch, start_utc, end_utc, title, descr FROM epg WHERE source_id = ? AND end_utc > ? AND title LIKE ? ORDER BY start_utc LIMIT ?",
            [.text(sourceId), .int(now), .text("%" + query + "%"), .int(limit)]
        ) { Self.programme($0) }
    }

    // MARK: - Writes

    /// Streaming replace: rows are written as they are parsed, so the whole
    /// guide is never held in memory. They land in a staging table first, so
    /// the live guide stays queryable for the whole import; only the final
    /// swap touches it. Blocks until done - call from a background thread.
    func replaceStreaming(
        sourceId: String,
        onProgress: @escaping (Int) -> Void,
        produce: (_ emit: @escaping (EpgProgramme) -> Void) -> Void
    ) -> Int {
        writeQueue.sync { () -> Int in
            guard let db = writeDb else { return 0 }
            let stage = "epg_stage_" + UUID().uuidString.replacingOccurrences(of: "-", with: "")
            Self.exec(db, "CREATE TABLE \(stage) (source_id TEXT NOT NULL, ch TEXT NOT NULL, start_utc INTEGER NOT NULL, end_utc INTEGER NOT NULL, title TEXT NOT NULL, descr TEXT)")
            Self.exec(db, "CREATE UNIQUE INDEX \(stage)_u ON \(stage)(source_id, ch, start_utc)")

            var stmt: OpaquePointer?
            let insert = "INSERT OR REPLACE INTO \(stage)(source_id, ch, start_utc, end_utc, title, descr) VALUES (?,?,?,?,?,?)"
            guard sqlite3_prepare_v2(db, insert, -1, &stmt, nil) == SQLITE_OK else {
                Self.exec(db, "DROP TABLE IF EXISTS \(stage)")
                return 0
            }

            var count = 0
            Self.exec(db, "BEGIN")
            produce { p in
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)
                sqlite3_bind_text(stmt, 1, sourceId, -1, sqliteTransient)
                sqlite3_bind_text(stmt, 2, Self.normalizeId(p.channelId), -1, sqliteTransient)
                sqlite3_bind_int64(stmt, 3, Int64(p.start))
                sqlite3_bind_int64(stmt, 4, Int64(p.end))
                sqlite3_bind_text(stmt, 5, p.title, -1, sqliteTransient)
                if let d = p.description {
                    sqlite3_bind_text(stmt, 6, d, -1, sqliteTransient)
                } else {
                    sqlite3_bind_null(stmt, 6)
                }
                sqlite3_step(stmt)
                count += 1
                // Committed in chunks so the journal never grows without bound.
                if count % 5000 == 0 {
                    Self.exec(db, "COMMIT")
                    Self.exec(db, "BEGIN")
                    onProgress(count)
                }
            }
            Self.exec(db, "COMMIT")
            sqlite3_finalize(stmt)

            if count > 0 {
                Self.exec(db, "BEGIN")
                Self.exec(db, "DELETE FROM epg WHERE source_id = '\(sourceId.replacingOccurrences(of: "'", with: "''"))'")
                Self.exec(db, "INSERT OR REPLACE INTO epg(source_id, ch, start_utc, end_utc, title, descr) SELECT source_id, ch, start_utc, end_utc, title, descr FROM \(stage)")
                Self.exec(db, "COMMIT")
            }
            Self.exec(db, "DROP TABLE IF EXISTS \(stage)")
            return count
        }
    }

    /// Adds or updates specific rows without touching the rest of a source's
    /// guide - how a supplemental guide (Marquee) layers on top of the
    /// provider's own.
    func upsert(sourceId: String, rows: [EpgProgramme]) {
        if rows.isEmpty { return }
        writeQueue.sync {
            guard let db = writeDb else { return }
            var stmt: OpaquePointer?
            let sql = "INSERT OR REPLACE INTO epg(source_id, ch, start_utc, end_utc, title, descr) VALUES (?,?,?,?,?,?)"
            guard sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK else { return }
            Self.exec(db, "BEGIN")
            for p in rows {
                sqlite3_reset(stmt)
                sqlite3_clear_bindings(stmt)
                sqlite3_bind_text(stmt, 1, sourceId, -1, sqliteTransient)
                sqlite3_bind_text(stmt, 2, Self.normalizeId(p.channelId), -1, sqliteTransient)
                sqlite3_bind_int64(stmt, 3, Int64(p.start))
                sqlite3_bind_int64(stmt, 4, Int64(p.end))
                sqlite3_bind_text(stmt, 5, p.title, -1, sqliteTransient)
                if let d = p.description {
                    sqlite3_bind_text(stmt, 6, d, -1, sqliteTransient)
                } else {
                    sqlite3_bind_null(stmt, 6)
                }
                sqlite3_step(stmt)
            }
            Self.exec(db, "COMMIT")
            sqlite3_finalize(stmt)
        }
    }

    func setFetched(_ id: String, at time: Int) {
        writeQueue.sync {
            guard let db = writeDb else { return }
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, "INSERT OR REPLACE INTO epg_meta(source_id, fetched_utc) VALUES (?, ?)", -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_text(stmt, 1, id, -1, sqliteTransient)
                sqlite3_bind_int64(stmt, 2, Int64(time))
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
        }
    }

    /// Drops everything for a source (it was removed, or the user cleared it).
    func clear(_ sourceId: String) {
        writeQueue.sync {
            guard let db = writeDb else { return }
            var stmt: OpaquePointer?
            for sql in ["DELETE FROM epg WHERE source_id = ?", "DELETE FROM epg_meta WHERE source_id = ? OR source_id = ? || '#supp'"] {
                if sqlite3_prepare_v2(db, sql, -1, &stmt, nil) == SQLITE_OK {
                    sqlite3_bind_text(stmt, 1, sourceId, -1, sqliteTransient)
                    if sql.contains("#supp") { sqlite3_bind_text(stmt, 2, sourceId, -1, sqliteTransient) }
                    sqlite3_step(stmt)
                }
                sqlite3_finalize(stmt)
                stmt = nil
            }
        }
    }

    func prune(before cutoff: Int) {
        writeQueue.sync {
            guard let db = writeDb else { return }
            var stmt: OpaquePointer?
            if sqlite3_prepare_v2(db, "DELETE FROM epg WHERE end_utc < ?", -1, &stmt, nil) == SQLITE_OK {
                sqlite3_bind_int64(stmt, 1, Int64(cutoff))
                sqlite3_step(stmt)
            }
            sqlite3_finalize(stmt)
        }
    }
}

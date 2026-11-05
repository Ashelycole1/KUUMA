package ug.kuuma.sentinel.store

import androidx.sqlite.db.SupportSQLiteDatabase
import ug.kuuma.contracts.HarmCategory
import ug.kuuma.contracts.Severity
import ug.kuuma.sentinel.screen.ContactStats
import ug.kuuma.sentinel.screen.FlagRecord

/**
 * sentinel.db: per-contact counters and last verdicts only. No message text, names or
 * numbers are ever stored (refs are salted hashes).
 */
interface SentinelStore {
    fun stats(senderRef: String): ContactStats?
    fun recordMessage(senderRef: String, fromChild: Boolean, at: Long)
    fun recordFlag(flag: FlagRecord)
    fun flag(flagId: String): FlagRecord?
    fun updateFlag(flag: FlagRecord)
    fun markDismissed(flagId: String)
    fun flagsIn(conversationRef: String): List<FlagRecord>
}

class InMemorySentinelStore : SentinelStore {
    private val contacts = HashMap<String, ContactStats>()
    private val flags = LinkedHashMap<String, FlagRecord>()

    @Synchronized override fun stats(senderRef: String) = contacts[senderRef]

    @Synchronized override fun recordMessage(senderRef: String, fromChild: Boolean, at: Long) {
        val s = contacts[senderRef] ?: ContactStats(senderRef, firstSeen = at, lastSeen = at)
        contacts[senderRef] =
            if (fromChild) {
                s.copy(
                    fromChild = s.fromChild + 1,
                    lastSeen = at
                )
            } else {
                s.copy(fromThem = s.fromThem + 1, lastSeen = at)
            }
    }

    @Synchronized override fun recordFlag(flag: FlagRecord) {
        flags[flag.flagId] = flag
        val s = contacts[flag.senderRef] ?: ContactStats(flag.senderRef, firstSeen = flag.at, lastSeen = flag.at)
        contacts[flag.senderRef] = s.copy(flags = s.flags + 1)
    }

    @Synchronized override fun flag(flagId: String) = flags[flagId]

    @Synchronized override fun updateFlag(flag: FlagRecord) {
        flags[flag.flagId] = flag
    }

    @Synchronized override fun markDismissed(flagId: String) {
        val f = flags[flagId] ?: return
        if (f.dismissed) return
        flags[flagId] = f.copy(dismissed = true)
        contacts[f.senderRef]?.let { contacts[f.senderRef] = it.copy(dismissed = it.dismissed + 1) }
    }

    @Synchronized override fun flagsIn(conversationRef: String) = flags.values.filter {
        it.conversationRef ==
            conversationRef
    }
}

/** SQLCipher-backed store (the module's own sentinel.db, opened via ModuleContext). */
class SqlSentinelStore(private val db: SupportSQLiteDatabase) : SentinelStore {
    companion object {
        const val VERSION = 1

        fun create(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS contact_stats (sender_ref TEXT PRIMARY KEY, from_them INTEGER NOT NULL, " +
                    "from_child INTEGER NOT NULL, flags INTEGER NOT NULL, dismissed INTEGER NOT NULL, " +
                    "first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS verdicts (flag_id TEXT PRIMARY KEY, conversation_ref TEXT NOT NULL, " +
                    "sender_ref TEXT NOT NULL, message_ref TEXT NOT NULL, category TEXT NOT NULL, " +
                    "severity TEXT NOT NULL, confidence REAL NOT NULL, model_version TEXT NOT NULL, " +
                    "at INTEGER NOT NULL, dismissed INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS verdicts_conversation ON verdicts(conversation_ref)")
        }
    }

    override fun stats(senderRef: String): ContactStats? = db.query(
        "SELECT from_them, from_child, flags, dismissed, first_seen, last_seen FROM contact_stats WHERE sender_ref = ?",
        arrayOf(senderRef)
    ).use { c ->
        if (!c.moveToFirst()) {
            null
        } else {
            ContactStats(
                senderRef,
                c.getInt(0),
                c.getInt(1),
                c.getInt(2),
                c.getInt(3),
                c.getLong(4),
                c.getLong(5)
            )
        }
    }

    override fun recordMessage(senderRef: String, fromChild: Boolean, at: Long) {
        db.execSQL(
            "INSERT OR IGNORE INTO contact_stats VALUES (?, 0, 0, 0, 0, ?, ?)",
            arrayOf<Any>(senderRef, at, at)
        )
        val column = if (fromChild) "from_child" else "from_them"
        db.execSQL(
            "UPDATE contact_stats SET $column = $column + 1, last_seen = ? WHERE sender_ref = ?",
            arrayOf<Any>(at, senderRef)
        )
    }

    override fun recordFlag(flag: FlagRecord) {
        updateFlag(flag)
        db.execSQL(
            "INSERT OR IGNORE INTO contact_stats VALUES (?, 0, 0, 0, 0, ?, ?)",
            arrayOf<Any>(flag.senderRef, flag.at, flag.at)
        )
        db.execSQL("UPDATE contact_stats SET flags = flags + 1 WHERE sender_ref = ?", arrayOf<Any>(flag.senderRef))
    }

    override fun updateFlag(flag: FlagRecord) {
        db.execSQL(
            "INSERT OR REPLACE INTO verdicts VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(
                flag.flagId, flag.conversationRef, flag.senderRef, flag.messageRef, flag.category.wire,
                flag.severity.wire, flag.confidence, flag.modelVersion, flag.at, if (flag.dismissed) 1 else 0
            )
        )
    }

    override fun flag(flagId: String): FlagRecord? = query("WHERE flag_id = ?", flagId).firstOrNull()

    override fun markDismissed(flagId: String) {
        val f = flag(flagId) ?: return
        if (f.dismissed) return
        db.execSQL("UPDATE verdicts SET dismissed = 1 WHERE flag_id = ?", arrayOf<Any>(flagId))
        db.execSQL("UPDATE contact_stats SET dismissed = dismissed + 1 WHERE sender_ref = ?", arrayOf<Any>(f.senderRef))
    }

    override fun flagsIn(conversationRef: String) = query("WHERE conversation_ref = ?", conversationRef)

    private fun query(where: String, arg: String): List<FlagRecord> = db.query(
        "SELECT flag_id, conversation_ref, sender_ref, message_ref, category, severity, confidence, model_version, at, dismissed FROM verdicts $where",
        arrayOf(arg)
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    FlagRecord(
                        c.getString(
                            0
                        ),
                        c.getString(1), c.getString(2), c.getString(3), HarmCategory.fromWire(c.getString(4)),
                        Severity.fromWire(c.getString(5)), c.getDouble(6), c.getString(7), c.getLong(8),
                        c.getInt(9) == 1
                    )
                )
            }
        }
    }
}

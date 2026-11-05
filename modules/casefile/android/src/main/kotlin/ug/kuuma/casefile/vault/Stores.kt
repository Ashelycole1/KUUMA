package ug.kuuma.casefile.vault

import androidx.sqlite.db.SupportSQLiteDatabase

class InMemoryVaultStore : VaultStore {
    private val items = LinkedHashMap<String, VaultItem>()
    private val chain = ArrayList<ChainEntry>()
    private val meta = HashMap<String, String>()

    @Synchronized override fun append(item: VaultItem, entry: ChainEntry) {
        require(item.itemId !in items) { "items are append-only" }
        items[item.itemId] = item
        chain += entry
    }

    @Synchronized override fun items() = items.values.toList()

    @Synchronized override fun item(itemId: String) = items[itemId]

    @Synchronized override fun chain() = chain.toList()

    @Synchronized override fun head() = chain.lastOrNull()

    @Synchronized override fun meta(key: String) = meta[key]

    @Synchronized override fun setMeta(key: String, value: String) {
        meta[key] = value
    }

    @Synchronized override fun wipe() {
        items.clear()
        chain.clear()
        meta.clear()
    }

    /** Test hooks: simulate an attacker editing the database directly. */
    @Synchronized fun tamperContent(itemId: String, json: String) {
        items[itemId] = items.getValue(itemId).copy(contentJson = json)
    }

    @Synchronized fun tamperDeleteItem(itemId: String) {
        items.remove(itemId)
    }

    @Synchronized fun tamperDeleteLink(index: Int) {
        chain.removeAt(index)
    }
}

/** vault.db (SQLCipher, opened through the module's own ModuleContext). */
class SqlVaultStore(private val db: SupportSQLiteDatabase) : VaultStore {
    companion object {
        const val VERSION = 1

        fun create(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS items (item_id TEXT PRIMARY KEY, kind TEXT NOT NULL, case_id TEXT, " +
                    "flag_id TEXT, content TEXT NOT NULL, stored_at INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS chain (idx INTEGER PRIMARY KEY, item_id TEXT NOT NULL, " +
                    "item_hash TEXT NOT NULL, " +
                    "prev_hash TEXT NOT NULL, chain_hash TEXT NOT NULL, signature TEXT NOT NULL, sealed_at INTEGER NOT NULL)"
            )
            db.execSQL("CREATE TABLE IF NOT EXISTS meta (k TEXT PRIMARY KEY, v TEXT NOT NULL)")
            // Append-only, enforced by the database too.
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS items_no_update BEFORE UPDATE ON items BEGIN SELECT RAISE(ABORT, 'append-only'); END"
            )
            db.execSQL(
                "CREATE TRIGGER IF NOT EXISTS chain_no_update BEFORE UPDATE ON chain BEGIN SELECT RAISE(ABORT, 'append-only'); END"
            )
        }
    }

    override fun append(item: VaultItem, entry: ChainEntry) {
        db.beginTransaction()
        try {
            db.execSQL(
                "INSERT INTO items VALUES (?, ?, ?, ?, ?, ?)",
                arrayOf<Any?>(item.itemId, item.kind.name, item.caseId, item.flagId, item.contentJson, item.storedAt)
            )
            db.execSQL(
                "INSERT INTO chain VALUES (?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>(
                    entry.index,
                    entry.itemId,
                    entry.itemHash,
                    entry.prevHash,
                    entry.chainHash,
                    entry.signature,
                    entry.sealedAt
                )
            )
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun items(): List<VaultItem> =
        db.query("SELECT item_id, kind, case_id, flag_id, content, stored_at FROM items ORDER BY stored_at").use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        VaultItem(
                            c.getString(0),
                            ItemKind.valueOf(c.getString(1)),
                            c.getString(2),
                            c.getString(3),
                            c.getString(4),
                            c.getLong(5)
                        )
                    )
                }
            }
        }

    override fun item(itemId: String) = items().firstOrNull { it.itemId == itemId }

    override fun chain(): List<ChainEntry> = db.query(
        "SELECT idx, item_id, item_hash, prev_hash, chain_hash, signature, sealed_at FROM chain ORDER BY idx"
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    ChainEntry(
                        c.getLong(0),
                        c.getString(1),
                        c.getString(2),
                        c.getString(3),
                        c.getString(4),
                        c.getString(5),
                        c.getLong(6)
                    )
                )
            }
        }
    }

    override fun head(): ChainEntry? = chain().lastOrNull()

    override fun meta(key: String): String? = db.query("SELECT v FROM meta WHERE k = ?", arrayOf(key)).use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

    override fun setMeta(key: String, value: String) {
        db.execSQL("INSERT OR REPLACE INTO meta VALUES (?, ?)", arrayOf<Any>(key, value))
    }

    override fun wipe() {
        db.execSQL("DROP TRIGGER IF EXISTS items_no_update")
        db.execSQL("DROP TRIGGER IF EXISTS chain_no_update")
        db.execSQL("DELETE FROM items")
        db.execSQL("DELETE FROM chain")
        db.execSQL("DELETE FROM meta")
        create(db)
    }
}

package ug.kuuma.core.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import ug.kuuma.contracts.Namespace
import ug.kuuma.core.crypto.KeyProtection
import ug.kuuma.core.crypto.SecretWrapper

/**
 * Each module's own encrypted database file (rule 2: own your data). A module can only
 * open its own file: [ModuleContext][ug.kuuma.core.module.ModuleContext] passes its namespace.
 */
object ModuleDatabases {
    /** Fixed file names per module. Casefile's is `vault.db`. */
    val fileNames: Map<Namespace, String> = mapOf(
        Namespace.SENTINEL to "sentinel.db",
        Namespace.COMPANION to "companion.db",
        Namespace.CASEFILE to "vault.db",
        Namespace.GUARDIAN to "guardian.db"
    )

    fun fileName(namespace: Namespace): String = fileNames.getValue(namespace)
}

/** An open SQLCipher database plus how its key is protected on this phone. */
class KuumaDatabase internal constructor(
    val namespace: Namespace,
    val helper: SupportSQLiteOpenHelper,
    val keyProtection: KeyProtection
) {
    val db: SupportSQLiteDatabase get() = helper.writableDatabase

    fun close() = helper.close()
}

/**
 * Opens SQLCipher databases with a random 32-byte passphrase per module, wrapped by the
 * Android Keystore ([SecretWrapper]): AES-GCM on Android 6+, RSA on 5.x, software as a last resort.
 */
class EncryptedStorage(private val context: Context) {
    init {
        System.loadLibrary("sqlcipher")
    }

    private val wrapper = SecretWrapper(context)

    fun open(
        namespace: Namespace,
        version: Int = 1,
        onCreate: (SupportSQLiteDatabase) -> Unit = {
        },
        onUpgrade: (SupportSQLiteDatabase, Int, Int) -> Unit = { _, _, _ -> }
    ): KuumaDatabase {
        val (passphrase, protection) = wrapper.getOrCreate("db.${namespace.wire}")
        val factory = SupportOpenHelperFactory(passphrase)
        val config = SupportSQLiteOpenHelper.Configuration.builder(context)
            .name(ModuleDatabases.fileName(namespace))
            .callback(
                object : SupportSQLiteOpenHelper.Callback(version) {
                    override fun onCreate(db: SupportSQLiteDatabase) = onCreate(db)
                    override fun onUpgrade(db: SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) =
                        onUpgrade(db, oldVersion, newVersion)
                }
            )
            .build()
        return KuumaDatabase(namespace, factory.create(config), protection)
    }
}

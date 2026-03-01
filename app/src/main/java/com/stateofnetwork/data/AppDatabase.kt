package com.stateofnetwork.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.stateofnetwork.data.model.DomainCheckItemEntity
import com.stateofnetwork.data.model.DomainCheckRunEntity
import com.stateofnetwork.data.model.SpeedTestResultEntity

@Database(
    entities = [
        SpeedTestResultEntity::class,
        DomainCheckRunEntity::class,
        DomainCheckItemEntity::class
    ],
    version = 3
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun speedDao(): SpeedDao
    abstract fun domainDao(): DomainDao
    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile private var instance: AppDatabase? = null

        fun get(ctx: Context): AppDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    ctx.applicationContext,
                    AppDatabase::class.java,
                    "state_of_network.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    .also { instance = it }
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Новые поля для "Пинг РФ" в результатах speed test
                db.execSQL("ALTER TABLE speed_results ADD COLUMN ruLatencyMs INTEGER")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN ruLatencyTarget TEXT")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Доп. поля для отображения расширенных деталей в истории speed test
                db.execSQL("ALTER TABLE speed_results ADD COLUMN transportLabel TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN transportsDetail TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN radioSummary TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN operatorName TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN providerName TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN providerAsn TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN providerGeo TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN publicIp TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN endpointHost TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN endpointIp TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN iface TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN mtu INTEGER")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN defaultGateway TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN privateDns TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN isValidated INTEGER")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN isCaptivePortal INTEGER")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN isMetered INTEGER")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN estDownKbps INTEGER")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN estUpKbps INTEGER")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN localIpsCsv TEXT")
                db.execSQL("ALTER TABLE speed_results ADD COLUMN dnsServersCsv TEXT")
            }
        }
    }
}

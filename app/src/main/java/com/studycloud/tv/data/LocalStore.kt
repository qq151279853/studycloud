package com.studycloud.tv.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.studycloud.gate.DayLedger
import com.studycloud.gate.ExtraPool
import com.studycloud.gate.GateEvent
import com.studycloud.gate.GateStore
import com.studycloud.gate.Question
import com.studycloud.gate.QuestionType
import java.util.UUID
import java.security.MessageDigest
import org.json.JSONObject

data class PendingEvent(val clientEventId: String, val payload: JSONObject)

/**
 * 设备本地存储：账本、加练池、题库缓存、事件队列。
 *
 * 刻意不用 Room——这里的 schema 很小且稳定，手写 SQL 可以避免引入注解处理器，
 * 构建更轻。账本相关的读写都走事务，强杀与重启不会丢失已获得的时长。
 */
class LocalStore(context: Context, private val appVersion: String) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION), GateStore {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE ledger (
                day             TEXT    PRIMARY KEY,
                balance_seconds INTEGER NOT NULL DEFAULT 0,
                earned_seconds  INTEGER NOT NULL DEFAULT 0,
                used_seconds    INTEGER NOT NULL DEFAULT 0,
                rounds_passed   INTEGER NOT NULL DEFAULT 0,
                updated_at      INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE settlements (
                round_id   TEXT    PRIMARY KEY,
                day        TEXT    NOT NULL,
                reward_sec INTEGER NOT NULL,
                settled_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE extra_pool (
                id           INTEGER PRIMARY KEY CHECK (id = 1),
                wrong_streak INTEGER NOT NULL DEFAULT 0,
                due_count    INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )
        db.execSQL("INSERT INTO extra_pool (id, wrong_streak, due_count) VALUES (1, 0, 0)")

        db.execSQL(
            """
            CREATE TABLE banks (
                bank_id  TEXT PRIMARY KEY,
                version  INTEGER NOT NULL,
                checksum TEXT    NOT NULL DEFAULT '',
                corrupt  INTEGER NOT NULL DEFAULT 0
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE questions (
                question_id TEXT PRIMARY KEY,
                bank_id     TEXT NOT NULL,
                sort_order  INTEGER NOT NULL,
                payload     TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX ix_questions_bank ON questions (bank_id, sort_order)")

        db.execSQL(
            """
            CREATE TABLE rules (
                id      INTEGER PRIMARY KEY CHECK (id = 1),
                version INTEGER NOT NULL,
                payload TEXT    NOT NULL
            )
            """.trimIndent(),
        )

        db.execSQL(
            """
            CREATE TABLE event_queue (
                client_event_id TEXT PRIMARY KEY,
                payload         TEXT    NOT NULL,
                occurred_at     INTEGER NOT NULL,
                uploaded_at     INTEGER
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX ix_event_queue_pending ON event_queue (uploaded_at, occurred_at)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // M2 只有一个版本；后续升级在这里按版本号逐步迁移，禁止 drop 用户数据。
    }

    fun appVersion(): String = appVersion

    // ---- GateStore：账本 ----

    override fun loadLedger(): DayLedger = readableDatabase
        .rawQuery("SELECT day, balance_seconds, earned_seconds, used_seconds, rounds_passed FROM ledger LIMIT 1", null)
        .use { cursor ->
            if (cursor.moveToFirst()) {
                DayLedger(
                    day = cursor.getString(0),
                    balanceSeconds = cursor.getInt(1),
                    earnedSeconds = cursor.getInt(2),
                    usedSeconds = cursor.getInt(3),
                    roundsPassed = cursor.getInt(4),
                )
            } else {
                DayLedger(day = "")
            }
        }

    override fun saveLedger(ledger: DayLedger) {
        writableDatabase.transaction {
            delete("ledger", null, null)
            insert(
                "ledger",
                null,
                ContentValues().apply {
                    put("day", ledger.day)
                    put("balance_seconds", ledger.balanceSeconds)
                    put("earned_seconds", ledger.earnedSeconds)
                    put("used_seconds", ledger.usedSeconds)
                    put("rounds_passed", ledger.roundsPassed)
                    put("updated_at", System.currentTimeMillis())
                },
            )
        }
    }

    override fun loadExtraPool(): ExtraPool = readableDatabase
        .rawQuery("SELECT wrong_streak, due_count FROM extra_pool WHERE id = 1", null)
        .use { cursor ->
            if (cursor.moveToFirst()) ExtraPool(cursor.getInt(0), cursor.getInt(1)) else ExtraPool()
        }

    override fun saveExtraPool(pool: ExtraPool) {
        writableDatabase.update(
            "extra_pool",
            ContentValues().apply {
                put("wrong_streak", pool.wrongStreak)
                put("due_count", pool.dueCount)
            },
            "id = 1",
            null,
        )
    }

    override fun isSettled(roundId: String): Boolean = readableDatabase
        .rawQuery("SELECT 1 FROM settlements WHERE round_id = ?", arrayOf(roundId))
        .use { it.moveToFirst() }

    override fun markSettled(roundId: String, rewardSeconds: Int) {
        writableDatabase.insertWithOnConflict(
            "settlements",
            null,
            ContentValues().apply {
                put("round_id", roundId)
                put("day", loadLedger().day)
                put("reward_sec", rewardSeconds)
                put("settled_at", System.currentTimeMillis())
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    // ---- 同步状态 ----

    fun ruleVersion(): Int? = readableDatabase
        .rawQuery("SELECT version FROM rules WHERE id = 1", null)
        .use { if (it.moveToFirst()) it.getInt(0) else null }

    fun rulePayload(): String? = readableDatabase
        .rawQuery("SELECT payload FROM rules WHERE id = 1", null)
        .use { if (it.moveToFirst()) it.getString(0) else null }

    fun saveRules(version: Int, payload: String) {
        writableDatabase.insertWithOnConflict(
            "rules",
            null,
            ContentValues().apply {
                put("id", 1)
                put("version", version)
                put("payload", payload)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun bankVersions(): Map<String, Int> = readableDatabase
        .rawQuery("SELECT bank_id, version FROM banks WHERE corrupt = 0", null)
        .use { cursor ->
            buildMap {
                while (cursor.moveToNext()) {
                    put(cursor.getString(0), cursor.getInt(1))
                }
            }
        }

    /**
     * 事务性替换：整批拉完、校验通过后才落库。中途失败保留旧数据，
     * 孩子不会突然无题可答。
     */
    fun applyBankDelta(
        bankId: String,
        version: Int,
        fullSync: Boolean,
        upserts: List<JSONObject>,
        removedIds: List<String>,
    ) {
        writableDatabase.transaction {
            if (fullSync) {
                delete("questions", "bank_id = ?", arrayOf(bankId))
            }

            for (id in removedIds) {
                delete("questions", "question_id = ?", arrayOf(id))
            }

            for (question in upserts) {
                insertWithOnConflict(
                    "questions",
                    null,
                    ContentValues().apply {
                        put("question_id", question.getString("id"))
                        put("bank_id", bankId)
                        put("sort_order", question.optInt("sortOrder"))
                        put("payload", question.toString())
                    },
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }

            insertWithOnConflict(
                "banks",
                null,
                ContentValues().apply {
                    put("bank_id", bankId)
                    put("version", version)
                    put("checksum", computeChecksum(this@transaction, bankId))
                    put("corrupt", 0)
                },
                SQLiteDatabase.CONFLICT_REPLACE,
            )
        }
    }

    fun checksumOf(bankId: String): String = readableDatabase
        .rawQuery("SELECT checksum FROM banks WHERE bank_id = ?", arrayOf(bankId))
        .use { if (it.moveToFirst()) it.getString(0) else "" }

    fun markBankCorrupt(bankId: String) {
        writableDatabase.update(
            "banks",
            ContentValues().apply { put("corrupt", 1) },
            "bank_id = ?",
            arrayOf(bankId),
        )
    }

    fun removeBanks(bankIds: List<String>) {
        if (bankIds.isEmpty()) return

        writableDatabase.transaction {
            for (id in bankIds) {
                delete("questions", "bank_id = ?", arrayOf(id))
                delete("banks", "bank_id = ?", arrayOf(id))
            }
        }
    }

    // ---- 答题 ----

    /** 读取本地缓存的全部可用题目。损坏的题库不参与抽题。 */
    fun loadQuestions(): List<Question> = readableDatabase.rawQuery(
        """
        SELECT q.question_id, q.bank_id, q.payload
        FROM questions q
        JOIN banks b ON b.bank_id = q.bank_id AND b.corrupt = 0
        """.trimIndent(),
        null,
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) {
                parseQuestion(cursor.getString(0), cursor.getString(1), cursor.getString(2))?.let(::add)
            }
        }
    }

    private fun parseQuestion(id: String, bankId: String, payload: String): Question? = runCatching {
        val json = JSONObject(payload)

        Question(
            id = id,
            bankId = bankId,
            type = QuestionType.valueOf(json.getString("type").uppercase()),
            stem = json.getString("stem"),
            options = json.optJSONArray("options")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            } ?: emptyList(),
            answerIndex = if (json.isNull("answerIndex")) null else json.optInt("answerIndex"),
            answerIndices = json.optJSONArray("answerIndices")?.let { array ->
                (0 until array.length()).map { array.getInt(it) }
            } ?: emptyList(),
            answers = json.optJSONArray("answers")?.let { array ->
                (0 until array.length()).map { array.getString(it) }
            } ?: emptyList(),
            modelAnswer = json.optString("modelAnswer").ifEmpty { null },
            rubric = json.optString("rubric").ifEmpty { null },
            passScore = if (json.isNull("passScore")) null else json.optInt("passScore"),
            difficulty = json.optInt("difficulty", 2),
            tip = json.optString("tip").ifEmpty { null },
        )
    }.getOrNull()

    /** 主观题作答进事件队列之外的单独队列，由同步任务上报。 */
    fun enqueueReview(clientAnswerId: String, questionId: String, answerText: String?) {
        enqueueEvent(
            clientEventId = "review:" + clientAnswerId,
            payload = JSONObject()
                .put("kind", "review")
                .put("clientAnswerId", clientAnswerId)
                .put("questionId", questionId)
                .put("answerText", answerText ?: JSONObject.NULL),
            occurredAtMillis = System.currentTimeMillis(),
        )
    }

    /** 把引擎产出的事件写入上报队列。 */
    fun enqueueGateEvent(event: GateEvent) {
        val now = System.currentTimeMillis()
        val id = UUID.randomUUID().toString()

        val payload = when (event) {
            is GateEvent.Usage -> JSONObject()
                .put("type", "usage")
                .put("durationSeconds", event.seconds)

            is GateEvent.RoundPassed -> JSONObject()
                .put("type", "roundPassed")
                .put("roundId", event.roundId)
                .put("rewardSeconds", event.rewardSeconds)

            is GateEvent.ExtraAdded -> JSONObject()
                .put("type", "extraAdded")
                .put("count", event.count)

            is GateEvent.DayRollover -> JSONObject()
                .put("type", "dayRollover")
                .put("day", event.day)

            is GateEvent.ClockDrift -> JSONObject()
                .put("type", "clockDrift")
                .put("driftSeconds", event.driftSeconds)
        }

        // roundPassed 用 roundId 当幂等键，重复结算不会产生两条上报
        val clientEventId = when (event) {
            is GateEvent.RoundPassed -> "round:" + event.roundId
            is GateEvent.DayRollover -> "day:" + event.day
            else -> id
        }

        enqueueEvent(clientEventId, payload.put("clientEventId", clientEventId), now)
    }

    // ---- 事件队列 ----

    fun enqueueEvent(clientEventId: String, payload: JSONObject, occurredAtMillis: Long) {
        writableDatabase.insertWithOnConflict(
            "event_queue",
            null,
            ContentValues().apply {
                put("client_event_id", clientEventId)
                put("payload", payload.toString())
                put("occurred_at", occurredAtMillis)
            },
            SQLiteDatabase.CONFLICT_IGNORE,
        )
    }

    fun takePendingEvents(limit: Int): List<PendingEvent> = readableDatabase
        .rawQuery(
            "SELECT client_event_id, payload FROM event_queue WHERE uploaded_at IS NULL ORDER BY occurred_at LIMIT ?",
            arrayOf(limit.toString()),
        )
        .use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(PendingEvent(cursor.getString(0), JSONObject(cursor.getString(1))))
                }
            }
        }

    /** 上报成功后标记而非删除，保留 30 天便于排查丢失。 */
    fun markEventsUploaded(clientEventIds: List<String>) {
        if (clientEventIds.isEmpty()) return

        val now = System.currentTimeMillis()

        writableDatabase.transaction {
            for (id in clientEventIds) {
                update(
                    "event_queue",
                    ContentValues().apply { put("uploaded_at", now) },
                    "client_event_id = ?",
                    arrayOf(id),
                )
            }

            delete("event_queue", "uploaded_at IS NOT NULL AND uploaded_at < ?", arrayOf((now - RETENTION_MILLIS).toString()))
        }
    }

    private fun computeChecksum(db: SQLiteDatabase, bankId: String): String {
        val digest = MessageDigest.getInstance("SHA-256")

        db.rawQuery(
            "SELECT question_id, payload FROM questions WHERE bank_id = ? ORDER BY question_id",
            arrayOf(bankId),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                digest.update(cursor.getString(0).toByteArray())
                digest.update(cursor.getString(1).toByteArray())
            }
        }

        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private inline fun <T> SQLiteDatabase.transaction(body: SQLiteDatabase.() -> T): T {
        beginTransaction()
        try {
            val result = body()
            setTransactionSuccessful()
            return result
        } finally {
            endTransaction()
        }
    }

    private companion object {
        const val DATABASE_NAME = "studycloud.db"
        const val DATABASE_VERSION = 1
        const val RETENTION_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}

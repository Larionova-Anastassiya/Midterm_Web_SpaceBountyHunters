import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

@Serializable
data class ApiError(val error: String)

@Serializable
data class WantedCriminal(
    val id: Long,
    val name: String?,
    val nickname: String,
    val reward: Long,
    val latitude: Double?,
    val longitude: Double?,
    @SerialName("location_note") val locationNote: String?,
    val difficulty: Int,
    val armed: Boolean,
    @SerialName("has_crew") val hasCrew: Boolean,
    @SerialName("escape_master") val escapeMaster: Boolean
)

@Serializable
data class HuntLog(
    val id: Long,
    @SerialName("criminal_id") val criminalId: Long,
    val success: Boolean,
    val notes: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CreateHuntRequest(
    val criminal_id: Long,
    val success: Boolean,
    val notes: String
)

@Serializable
data class PatchHuntRequest(
    val success: Boolean? = null,
    val notes: String? = null
)

class ApiValidationException(message: String) : RuntimeException(message)

object BountyDatabase {
    private val host = System.getenv("DB_HOST") ?: "localhost"
    private val port = System.getenv("DB_PORT") ?: "5432"
    private val dbName = System.getenv("DB_NAME") ?: "space_bounty"
    private val user = System.getenv("DB_USER") ?: "bounty_user"
    private val password = System.getenv("DB_PASSWORD") ?: error("DB_PASSWORD must be set")

    private fun connect(): Connection = DriverManager.getConnection(
        "jdbc:postgresql://$host:$port/$dbName", user, password
    )

    fun initialize() {
        connect().use { connection ->
            connection.createStatement().use { statement ->
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS wanted_criminals (
                        id BIGSERIAL PRIMARY KEY,
                        name VARCHAR(120),
                        nickname VARCHAR(120) NOT NULL UNIQUE,
                        reward BIGINT NOT NULL CHECK (reward >= 0),
                        latitude DOUBLE PRECISION,
                        longitude DOUBLE PRECISION,
                        location_note TEXT,
                        difficulty INTEGER NOT NULL CHECK (difficulty BETWEEN 1 AND 10),
                        armed BOOLEAN NOT NULL DEFAULT FALSE,
                        has_crew BOOLEAN NOT NULL DEFAULT FALSE,
                        escape_master BOOLEAN NOT NULL DEFAULT FALSE,
                        CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
                        CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
                        CHECK ((latitude IS NULL) = (longitude IS NULL)),
                        CHECK (latitude IS NOT NULL OR location_note IS NOT NULL)
                    )
                """.trimIndent())
                statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS hunt_logs (
                        id BIGSERIAL PRIMARY KEY,
                        criminal_id BIGINT NOT NULL REFERENCES wanted_criminals(id) ON DELETE CASCADE,
                        success BOOLEAN NOT NULL,
                        notes TEXT NOT NULL,
                        created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
                    )
                """.trimIndent())
                // Seed only once: restarting Docker never duplicates or overwrites records.
                statement.execute("""
                    DO $$
                    BEGIN
                        IF NOT EXISTS (SELECT 1 FROM wanted_criminals) THEN
                            INSERT INTO wanted_criminals
                                (name, nickname, reward, latitude, longitude, location_note, difficulty, armed, has_crew, escape_master)
                            VALUES
                                ('Kael Voss', 'Nebula Ghost', 15000, 35.7, -112.4, NULL, 8, TRUE, FALSE, TRUE),
                                (NULL, 'Void Whisper', 8500, NULL, NULL, 'Last spotted near Saturn rings', 6, FALSE, TRUE, TRUE),
                                ('Mira Sol', 'Solar Fang', 22000, -15.5, 88.2, NULL, 9, TRUE, TRUE, FALSE),
                                ('Drax Orion', 'Iron Comet', 4000, NULL, NULL, 'Europa mining station', 3, TRUE, FALSE, FALSE),
                                ('Nyx Vale', 'Starlight Thief', 12000, 45.2, 17.9, NULL, 7, FALSE, FALSE, TRUE);
                        END IF;
                        IF NOT EXISTS (SELECT 1 FROM hunt_logs) THEN
                            INSERT INTO hunt_logs (criminal_id, success, notes)
                            SELECT c.id, v.success, v.notes
                            FROM (VALUES
                                ('Nebula Ghost', FALSE, 'Escaped through an asteroid field.'),
                                ('Nebula Ghost', FALSE, 'Lost the signal in hyperspace.'),
                                ('Void Whisper', TRUE, 'Captured near Saturn rings.'),
                                ('Void Whisper', FALSE, 'Crew blocked the docking bay.'),
                                ('Solar Fang', FALSE, 'Heavy blaster fire forced retreat.'),
                                ('Solar Fang', FALSE, 'Target escaped with crew.'),
                                ('Iron Comet', TRUE, 'Captured at Europa station.'),
                                ('Iron Comet', TRUE, 'Second warrant served successfully.'),
                                ('Starlight Thief', FALSE, 'Disappeared using stealth technology.'),
                                ('Starlight Thief', TRUE, 'Captured after a long chase.')
                            ) AS v(nickname, success, notes)
                            JOIN wanted_criminals c ON c.nickname = v.nickname;
                        END IF;
                    END
                    $$;
                """.trimIndent())
            }
        }
    }

    private fun criminalFrom(rs: ResultSet): WantedCriminal = WantedCriminal(
        id = rs.getLong("id"),
        name = rs.getString("name"),
        nickname = rs.getString("nickname"),
        reward = rs.getLong("reward"),
        latitude = rs.getDouble("latitude").let { if (rs.wasNull()) null else it },
        longitude = rs.getDouble("longitude").let { if (rs.wasNull()) null else it },
        locationNote = rs.getString("location_note"),
        difficulty = rs.getInt("difficulty"),
        armed = rs.getBoolean("armed"),
        hasCrew = rs.getBoolean("has_crew"),
        escapeMaster = rs.getBoolean("escape_master")
    )

    private fun huntFrom(rs: ResultSet): HuntLog = HuntLog(
        id = rs.getLong("id"),
        criminalId = rs.getLong("criminal_id"),
        success = rs.getBoolean("success"),
        notes = rs.getString("notes"),
        createdAt = rs.getTimestamp("created_at").toInstant().toString()
    )

    fun listCriminals(
        minReward: Long?, maxReward: Long?, armed: Boolean?,
        hasCrew: Boolean?, escapeMaster: Boolean?, sort: String?
    ): List<WantedCriminal> = connect().use { connection ->
        val conditions = mutableListOf<String>()
        val params = mutableListOf<Any>()
        if (minReward != null) { conditions += "reward >= ?"; params += minReward }
        if (maxReward != null) { conditions += "reward <= ?"; params += maxReward }
        if (armed != null) { conditions += "armed = ?"; params += armed }
        if (hasCrew != null) { conditions += "has_crew = ?"; params += hasCrew }
        if (escapeMaster != null) { conditions += "escape_master = ?"; params += escapeMaster }
        val order = if (sort == "-reward") "reward DESC, id ASC" else "reward ASC, id ASC"
        val where = if (conditions.isEmpty()) "" else " WHERE " + conditions.joinToString(" AND ")
        val sql = "SELECT * FROM wanted_criminals$where ORDER BY $order"
        connection.prepareStatement(sql).use { statement ->
            params.forEachIndexed { index, value -> statement.setObject(index + 1, value) }
            statement.executeQuery().use { rs ->
                buildList { while (rs.next()) add(criminalFrom(rs)) }
            }
        }
    }

    fun getCriminal(id: Long): WantedCriminal? = connect().use { connection ->
        connection.prepareStatement("SELECT * FROM wanted_criminals WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rs -> if (rs.next()) criminalFrom(rs) else null }
        }
    }

    fun listHunts(criminalId: Long?): List<HuntLog> = connect().use { connection ->
        val sql = if (criminalId == null) "SELECT * FROM hunt_logs ORDER BY id" else
            "SELECT * FROM hunt_logs WHERE criminal_id = ? ORDER BY id"
        connection.prepareStatement(sql).use { statement ->
            if (criminalId != null) statement.setLong(1, criminalId)
            statement.executeQuery().use { rs -> buildList { while (rs.next()) add(huntFrom(rs)) } }
        }
    }

    fun getHunt(id: Long): HuntLog? = connect().use { connection ->
        connection.prepareStatement("SELECT * FROM hunt_logs WHERE id = ?").use { statement ->
            statement.setLong(1, id)
            statement.executeQuery().use { rs -> if (rs.next()) huntFrom(rs) else null }
        }
    }

    fun createHunt(input: CreateHuntRequest): HuntLog {
        if (input.criminal_id <= 0) throw ApiValidationException("criminal_id must be positive")
        if (input.notes.isBlank()) throw ApiValidationException("notes must not be blank")
        connect().use { connection ->
            connection.autoCommit = false
            try {
                val exists = connection.prepareStatement("SELECT 1 FROM wanted_criminals WHERE id = ?").use { st ->
                    st.setLong(1, input.criminal_id)
                    st.executeQuery().use { it.next() }
                }
                if (!exists) throw ApiValidationException("criminal_id does not reference an existing criminal")
                val id = connection.prepareStatement(
                    "INSERT INTO hunt_logs (criminal_id, success, notes) VALUES (?, ?, ?) RETURNING id"
                ).use { st ->
                    st.setLong(1, input.criminal_id)
                    st.setBoolean(2, input.success)
                    st.setString(3, input.notes.trim())
                    st.executeQuery().use { rs -> rs.next(); rs.getLong(1) }
                }
                val hunt = connection.prepareStatement("SELECT * FROM hunt_logs WHERE id = ?").use { st ->
                    st.setLong(1, id)
                    st.executeQuery().use { rs -> rs.next(); huntFrom(rs) }
                }
                connection.commit()
                return hunt
            } catch (e: Exception) {
                connection.rollback()
                throw e
            }
        }
    }

    fun patchHunt(id: Long, input: PatchHuntRequest): HuntLog? {
        if (input.success == null && input.notes == null) throw ApiValidationException("Provide success and/or notes")
        if (input.notes != null && input.notes.isBlank()) throw ApiValidationException("notes must not be blank")
        connect().use { connection ->
            connection.prepareStatement("""
                UPDATE hunt_logs SET
                    success = COALESCE(?, success),
                    notes = COALESCE(?, notes)
                WHERE id = ?
            """.trimIndent()).use { st ->
                if (input.success == null) st.setNull(1, java.sql.Types.BOOLEAN) else st.setBoolean(1, input.success)
                if (input.notes == null) st.setNull(2, java.sql.Types.VARCHAR) else st.setString(2, input.notes.trim())
                st.setLong(3, id)
                if (st.executeUpdate() == 0) return null
            }
        }
        return getHunt(id)
    }

    fun deleteHunt(id: Long): Boolean = connect().use { connection ->
        connection.prepareStatement("DELETE FROM hunt_logs WHERE id = ?").use { st ->
            st.setLong(1, id)
            st.executeUpdate() > 0
        }
    }
}

private fun String?.positiveId(): Long? = this?.toLongOrNull()?.takeIf { it > 0 }
private fun String?.queryLong(name: String): Long? {
    if (this == null) return null
    return this.toLongOrNull()?.takeIf { it >= 0 }
        ?: throw ApiValidationException("$name must be a non-negative integer")
}
private fun String?.queryBoolean(name: String): Boolean? = when (this?.lowercase()) {
    null -> null
    "true" -> true
    "false" -> false
    else -> throw ApiValidationException("$name must be true or false")
}

fun main() {
    BountyDatabase.initialize()
    embeddedServer(Netty, host = "0.0.0.0", port = System.getenv("PORT")?.toIntOrNull() ?: 8080) {
        install(ContentNegotiation) {
            json(Json { prettyPrint = true; ignoreUnknownKeys = false; explicitNulls = true })
        }
        routing {
            get("/api/health") { call.respond(mapOf("status" to "ok")) }

            for (path in listOf("/api/criminals", "/api/criminals/")) {
                get(path) {
                    try {
                        val q = call.request.queryParameters
                        val min = q["min_reward"].queryLong("min_reward")
                        val max = q["max_reward"].queryLong("max_reward")
                        if (min != null && max != null && min > max) throw ApiValidationException("min_reward must not exceed max_reward")
                        val armed = q["armed"].queryBoolean("armed")
                        val crew = q["has_crew"].queryBoolean("has_crew")
                        val escape = q["escape_master"].queryBoolean("escape_master")
                        val sort = q["sort"]
                        if (sort != null && sort !in listOf("reward", "-reward")) throw ApiValidationException("sort must be reward or -reward")
                        call.respond(BountyDatabase.listCriminals(min, max, armed, crew, escape, sort))
                    } catch (e: ApiValidationException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Invalid filters"))
                    }
                }
            }

            for (path in listOf("/api/criminals/{id}", "/api/criminals/{id}/")) {
                get(path) {
                    val id = call.parameters["id"].positiveId()
                    if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid criminal ID")); return@get }
                    val criminal = BountyDatabase.getCriminal(id)
                    if (criminal == null) call.respond(HttpStatusCode.NotFound, ApiError("Criminal not found"))
                    else call.respond(criminal)
                }
            }

            for (path in listOf("/api/hunts", "/api/hunts/")) {
                get(path) {
                    try {
                        val criminalId = call.request.queryParameters["criminal_id"].queryLong("criminal_id")
                        call.respond(BountyDatabase.listHunts(criminalId))
                    } catch (e: ApiValidationException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Invalid filter"))
                    }
                }
                post(path) {
                    val input = try { call.receive<CreateHuntRequest>() } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON body; required: criminal_id, success, notes"))
                        return@post
                    }
                    try {
                        val created = BountyDatabase.createHunt(input)
                        call.response.header(HttpHeaders.Location, "/api/hunts/${created.id}/")
                        call.respond(HttpStatusCode.Created, created)
                    } catch (e: ApiValidationException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Invalid hunt"))
                    }
                }
            }

            for (path in listOf("/api/hunts/{id}", "/api/hunts/{id}/")) {
                get(path) {
                    val id = call.parameters["id"].positiveId()
                    if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid hunt ID")); return@get }
                    val hunt = BountyDatabase.getHunt(id)
                    if (hunt == null) call.respond(HttpStatusCode.NotFound, ApiError("Hunt log not found"))
                    else call.respond(hunt)
                }
                patch(path) {
                    val id = call.parameters["id"].positiveId()
                    if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid hunt ID")); return@patch }
                    val input = try { call.receive<PatchHuntRequest>() } catch (_: Exception) {
                        call.respond(HttpStatusCode.BadRequest, ApiError("Invalid JSON body")); return@patch
                    }
                    try {
                        val updated = BountyDatabase.patchHunt(id, input)
                        if (updated == null) call.respond(HttpStatusCode.NotFound, ApiError("Hunt log not found"))
                        else call.respond(updated)
                    } catch (e: ApiValidationException) {
                        call.respond(HttpStatusCode.BadRequest, ApiError(e.message ?: "Invalid patch"))
                    }
                }
                delete(path) {
                    val id = call.parameters["id"].positiveId()
                    if (id == null) { call.respond(HttpStatusCode.BadRequest, ApiError("Invalid hunt ID")); return@delete }
                    if (BountyDatabase.deleteHunt(id)) call.respond(HttpStatusCode.NoContent)
                    else call.respond(HttpStatusCode.NotFound, ApiError("Hunt log not found"))
                }
            }
        }
    }.start(wait = true)
}

package com.zoho.dzide.ddmigration

import com.intellij.openapi.diagnostic.Logger
import java.sql.Connection
import java.sql.DriverManager

private const val CUSTOMER_DATABASE_TABLE = "CustomerDatabase"

class MigrationSqlExecutor {

    private val log = Logger.getInstance(MigrationSqlExecutor::class.java)

    fun execute(summary: MigrationSummary, progressCallback: (String) -> Unit): MigrationResult {
        val db = summary.dbConnection
        log.info("[DDMigration] Executing migration: ${summary.sqlQueries.size} statements on ${db.host}:${db.port}/${db.database}")
        progressCallback("Starting migration on ${db.host}:${db.port}/${db.database}")

        val jdbcUrl = if (db.isMySQL) {
            "jdbc:mysql://${db.host}:${db.port}/${db.database}"
        } else {
            "jdbc:postgresql://${db.host}:${db.port}/${db.database}"
        }

        val driverClass = if (db.isMySQL) "com.mysql.cj.jdbc.Driver" else "org.postgresql.Driver"
        val schemaResults = mutableListOf<SchemaResult>()

        try {
            Class.forName(driverClass)
            DriverManager.getConnection(jdbcUrl, db.username, db.password).use { conn ->
                val schemas = getSchemas(conn, db)
                if (schemas.isEmpty()) {
                    progressCallback("No schemas found for migration")
                    return MigrationResult(false, 0, emptyList())
                }

                for (schema in schemas) {
                    val result = processSchema(conn, schema, summary.sqlQueries, db, progressCallback)
                    schemaResults.add(result)
                }

                val success = schemaResults.all { it.success }
                progressCallback("Migration complete: ${schemaResults.size} schemas processed")
                return MigrationResult(success, schemas.size, schemaResults)
            }
        } catch (e: Exception) {
            log.error("[DDMigration] Migration failed", e)
            progressCallback("Migration error: ${e.message}")
            return MigrationResult(false, 0, schemaResults)
        }
    }

    private fun getSchemas(conn: Connection, db: DBConnectionDetails): List<String> {
        return try {
            if (db.isMySQL) {
                conn.catalog = db.schemaName
            } else {
                conn.createStatement().use { it.execute("SET search_path=${db.schemaName}") }
            }
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT SCHEMANAME FROM $CUSTOMER_DATABASE_TABLE").use { rs ->
                    mutableListOf<String>().apply {
                        while (rs.next()) add(rs.getString("SCHEMANAME"))
                    }
                }
            }
        } catch (_: Exception) {
            listOf(db.schemaName)
        }
    }

    private fun processSchema(
        conn: Connection,
        schemaName: String,
        queries: List<String>,
        db: DBConnectionDetails,
        progress: (String) -> Unit
    ): SchemaResult {
        progress("Processing schema: $schemaName")
        var successful = 0
        var failed = 0
        val errors = mutableListOf<String>()

        try {
            if (db.isMySQL) {
                conn.catalog = schemaName
            } else {
                conn.createStatement().use { it.execute("SET search_path=$schemaName") }
            }

            for ((index, query) in queries.withIndex()) {
                try {
                    val executableQuery = query.replace("\${querynumber}", (index + 1).toString())
                    conn.createStatement().use { it.execute(executableQuery) }
                    successful++
                } catch (e: Exception) {
                    failed++
                    errors.add("${e.message} [Query: ${query.take(100)}]")
                    progress("Query failed in $schemaName: ${e.message}")
                }
            }
        } catch (e: Exception) {
            progress("Error processing schema $schemaName: ${e.message}")
            return SchemaResult(schemaName, false, queries.size, 0, queries.size, listOf(e.message ?: "Unknown error"))
        }

        progress("Schema $schemaName: $successful/${queries.size} successful")
        return SchemaResult(schemaName, failed == 0, queries.size, successful, failed, errors)
    }
}

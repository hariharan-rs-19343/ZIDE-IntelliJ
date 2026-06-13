package com.zoho.dzide.db

import com.intellij.openapi.diagnostic.Logger
import java.sql.Connection
import java.sql.DriverManager

interface DatabaseReinitializer {
    fun reinit(host: String, port: Int, dbName: String, user: String, pass: String, schemaName: String)
}

class PostgresReinitializer : DatabaseReinitializer {

    private val log = Logger.getInstance(PostgresReinitializer::class.java)

    override fun reinit(host: String, port: Int, dbName: String, user: String, pass: String, schemaName: String) {
        requireValidIdentifier(dbName, "database name")
        requireValidIdentifier(schemaName, "schema name")

        val adminUrl = "jdbc:postgresql://$host:$port/postgres"
        Class.forName("org.postgresql.Driver")

        DriverManager.getConnection(adminUrl, user, pass).use { conn ->
            conn.autoCommit = true

            log.info("[ReinitDB] Terminating connections to $dbName")
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '$dbName' AND pid <> pg_backend_pid()"
                )
            }

            log.info("[ReinitDB] Dropping database $dbName")
            conn.createStatement().use { it.execute("DROP DATABASE IF EXISTS \"$dbName\"") }

            log.info("[ReinitDB] Creating database $dbName")
            conn.createStatement().use { it.execute("CREATE DATABASE \"$dbName\"") }
        }

        val dbUrl = "jdbc:postgresql://$host:$port/$dbName"
        DriverManager.getConnection(dbUrl, user, pass).use { conn ->
            conn.autoCommit = true

            if (schemaName.isNotBlank() && schemaName != "public") {
                log.info("[ReinitDB] Creating schema $schemaName")
                conn.createStatement().use { it.execute("CREATE SCHEMA IF NOT EXISTS \"$schemaName\"") }
            }

            executePostgresFunctions(conn)
        }

        log.info("[ReinitDB] PostgreSQL reinit complete for $dbName")
    }

    private fun executePostgresFunctions(conn: Connection) {
        val sql = javaClass.getResourceAsStream("/sql/postgres_functions.sql")?.bufferedReader()?.readText()
        if (sql == null) {
            log.warn("[ReinitDB] postgres_functions.sql not found in plugin resources, skipping")
            return
        }
        try {
            log.info("[ReinitDB] Executing postgres_functions.sql (MySQL compatibility functions + extensions)")
            conn.createStatement().use { it.execute(sql) }
            log.info("[ReinitDB] postgres_functions.sql executed successfully")
        } catch (e: Exception) {
            log.warn("[ReinitDB] postgres_functions.sql execution had errors (some functions may already exist): ${e.message}")
        }
    }
}

class MySQLReinitializer : DatabaseReinitializer {

    private val log = Logger.getInstance(MySQLReinitializer::class.java)

    override fun reinit(host: String, port: Int, dbName: String, user: String, pass: String, schemaName: String) {
        requireValidIdentifier(dbName, "database name")

        val adminUrl = "jdbc:mysql://$host:$port/information_schema"
        Class.forName("com.mysql.cj.jdbc.Driver")

        DriverManager.getConnection(adminUrl, user, pass).use { conn ->
            conn.autoCommit = true

            val dbExists = conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT SCHEMA_NAME FROM INFORMATION_SCHEMA.SCHEMATA WHERE SCHEMA_NAME = '$dbName'").use { rs ->
                    rs.next()
                }
            }

            if (!dbExists) {
                log.info("[ReinitDB] Database $dbName does not exist, creating")
                conn.createStatement().use { it.execute("CREATE DATABASE `$dbName`") }
                return
            }

            log.info("[ReinitDB] Dropping database $dbName")
            conn.createStatement().use { it.execute("DROP DATABASE `$dbName`") }

            log.info("[ReinitDB] Creating database $dbName")
            conn.createStatement().use { it.execute("CREATE DATABASE `$dbName`") }
        }

        log.info("[ReinitDB] MySQL reinit complete for $dbName")
    }
}

private val IDENTIFIER_REGEX = Regex("[A-Za-z0-9_]+")

private fun requireValidIdentifier(value: String, label: String) {
    require(value.isNotBlank() && IDENTIFIER_REGEX.matches(value)) {
        "Invalid $label: '$value' — must contain only alphanumeric characters and underscores"
    }
}

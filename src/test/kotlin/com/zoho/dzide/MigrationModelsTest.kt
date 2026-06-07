package com.zoho.dzide

import com.zoho.dzide.ddmigration.DBConnectionDetails
import com.zoho.dzide.ddmigration.MigrationSummary
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class MigrationSummaryTest {

    private val pgConnection = DBConnectionDetails(
        host = "localhost", port = 5432, database = "testdb",
        username = "user", password = "pass", schemaName = "public", isMySQL = false
    )

    private val mysqlConnection = pgConnection.copy(isMySQL = true, port = 3306)

    @Test
    fun `fromSqlFile with empty file returns zero statements`() {
        val tempFile = File.createTempFile("dd-test", ".sql")
        tempFile.writeText("")
        try {
            val summary = MigrationSummary.fromSqlFile(tempFile.absolutePath, pgConnection)
            assertEquals(0, summary.statementCount)
            assertTrue(summary.sqlQueries.isEmpty())
            assertTrue(summary.operationTypes.isEmpty())
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromSqlFile extracts INSTALL directives`() {
        val content = """
            INSTALL - $ CREATE TABLE foo (id INT);
            REVERT - $ DROP TABLE foo;
            INSTALL - $ ALTER TABLE bar ADD COLUMN x VARCHAR(100);
        """.trimIndent()

        val tempFile = File.createTempFile("dd-test", ".sql")
        tempFile.writeText(content)
        try {
            val summary = MigrationSummary.fromSqlFile(tempFile.absolutePath, pgConnection)
            assertEquals(2, summary.statementCount)
            assertTrue(summary.operationTypes.contains("CREATE"))
            assertTrue(summary.operationTypes.contains("ALTER"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromSqlFile filters by database section`() {
        val content = """
            <POSTGRES>
            INSTALL - $ CREATE TABLE pg_only (id INT);
            </POSTGRES>
            <MYSQL>
            INSTALL - $ CREATE TABLE mysql_only (id INT);
            </MYSQL>
        """.trimIndent()

        val tempFile = File.createTempFile("dd-test", ".sql")
        tempFile.writeText(content)
        try {
            val pgSummary = MigrationSummary.fromSqlFile(tempFile.absolutePath, pgConnection)
            assertEquals(1, pgSummary.statementCount)
            assertTrue(pgSummary.sqlQueries[0].contains("pg_only"))

            val mysqlSummary = MigrationSummary.fromSqlFile(tempFile.absolutePath, mysqlConnection)
            assertEquals(1, mysqlSummary.statementCount)
            assertTrue(mysqlSummary.sqlQueries[0].contains("mysql_only"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `fromSqlFile strips ISUQueryExecutionTracker updates`() {
        val content = """
            INSTALL - $ CREATE TABLE foo (id INT); UPDATE ISUQueryExecutionTracker SET status='done';
        """.trimIndent()

        val tempFile = File.createTempFile("dd-test", ".sql")
        tempFile.writeText(content)
        try {
            val summary = MigrationSummary.fromSqlFile(tempFile.absolutePath, pgConnection)
            assertEquals(1, summary.statementCount)
            assertFalse(summary.sqlQueries[0].contains("ISUQueryExecutionTracker"))
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `operationTypes detects multiple types`() {
        val content = """
            INSTALL - $ CREATE TABLE foo (id INT);
            INSTALL - $ ALTER TABLE bar ADD COLUMN x INT;
            INSTALL - $ DROP INDEX idx_foo;
            INSTALL - $ INSERT INTO foo VALUES (1);
        """.trimIndent()

        val tempFile = File.createTempFile("dd-test", ".sql")
        tempFile.writeText(content)
        try {
            val summary = MigrationSummary.fromSqlFile(tempFile.absolutePath, pgConnection)
            assertTrue(summary.operationTypes.containsAll(listOf("CREATE", "ALTER", "DROP", "INSERT")))
        } finally {
            tempFile.delete()
        }
    }
}

class CmToolsApiTest {

    @Test
    fun `Product model fields are accessible`() {
        val product = com.zoho.dzide.cmtools.Product(
            id = 1, name = "test", moduleName = "mod",
            serviceName = "svc", repositoryUrl = "https://repo", downloadUrl = "https://dl"
        )
        assertEquals(1, product.id)
        assertEquals("test", product.name)
        assertEquals("https://repo", product.repositoryUrl)
    }

    @Test
    fun `BuildLog model holds url`() {
        val log = com.zoho.dzide.cmtools.BuildLog("https://build/123")
        assertEquals("https://build/123", log.url)
    }
}

class DeploymentPropertiesValidatorTest {

    @Test
    fun `PORT_NUMBER validator accepts valid ports`() {
        val validator = com.zoho.dzide.zide.DeploymentPropertiesDialog.FieldValidator.PORT_NUMBER
        assertNull(validator.validate("8080"))
        assertNull(validator.validate("443"))
        assertNull(validator.validate("1"))
        assertNull(validator.validate("65535"))
    }

    @Test
    fun `PORT_NUMBER validator rejects invalid ports`() {
        val validator = com.zoho.dzide.zide.DeploymentPropertiesDialog.FieldValidator.PORT_NUMBER
        assertNotNull(validator.validate("0"))
        assertNotNull(validator.validate("65536"))
        assertNotNull(validator.validate("abc"))
        assertNotNull(validator.validate(""))
    }

    @Test
    fun `NOT_EMPTY validator rejects blank`() {
        val validator = com.zoho.dzide.zide.DeploymentPropertiesDialog.FieldValidator.NOT_EMPTY
        assertNotNull(validator.validate(""))
        assertNotNull(validator.validate("   "))
        assertNull(validator.validate("value"))
    }

    @Test
    fun `NONE validator always passes`() {
        val validator = com.zoho.dzide.zide.DeploymentPropertiesDialog.FieldValidator.NONE
        assertNull(validator.validate(""))
        assertNull(validator.validate("anything"))
    }
}

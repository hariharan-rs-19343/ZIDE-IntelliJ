package com.zoho.dzide.ddmigration

data class DBConnectionDetails(
    val host: String,
    val port: Int,
    val database: String,
    val username: String,
    val password: String,
    val schemaName: String,
    val isMySQL: Boolean
)

data class MigrationSummary(
    val sqlContent: String,
    val sqlFilePath: String,
    val fileName: String,
    val statementCount: Int,
    val operationTypes: List<String>,
    val sqlQueries: List<String>,
    val dbConnection: DBConnectionDetails
) {
    companion object {
        private const val INSTALL_MARKER = "INSTALL"
        private const val INSTALL_SEPARATOR = " - \$ "
        private val SQL_OPERATION_PATTERNS = listOf("CREATE", "ALTER", "DROP", "INSERT", "UPDATE", "DELETE", "TRUNCATE")
        private val TRACKER_PATTERN = Regex("UPDATE\\s+(?:`)?ISUQueryExecutionTracker(?:`)?.*?;", RegexOption.IGNORE_CASE)

        fun fromSqlFile(sqlFilePath: String, dbConnection: DBConnectionDetails): MigrationSummary {
            val file = java.io.File(sqlFilePath)
            val content = if (file.exists()) file.readText() else ""
            val queries = extractSqlQueries(content, dbConnection)
            return MigrationSummary(
                sqlContent = content,
                sqlFilePath = sqlFilePath,
                fileName = file.name,
                statementCount = queries.size,
                operationTypes = extractOperationTypes(content),
                sqlQueries = queries,
                dbConnection = dbConnection
            )
        }

        private fun extractSqlQueries(content: String, dbConnection: DBConnectionDetails): List<String> {
            if (content.isBlank()) return emptyList()
            val sectionContent = extractDbSection(content, dbConnection)
            return sectionContent.lines()
                .map { it.trim() }
                .filter { it.contains(INSTALL_MARKER) && INSTALL_SEPARATOR in it }
                .mapNotNull { line ->
                    val sql = TRACKER_PATTERN.replace(
                        line.substringAfter(INSTALL_SEPARATOR).trim(), ""
                    ).trim()
                    sql.ifBlank { null }
                }
        }

        private fun extractDbSection(content: String, dbConnection: DBConnectionDetails): String {
            val tag = if (dbConnection.isMySQL) "MYSQL" else "POSTGRES"
            val regex = Regex("<$tag>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL)
            return regex.find(content)?.groupValues?.get(1)?.trim() ?: content
        }

        private fun extractOperationTypes(content: String): List<String> {
            if (content.isBlank()) return emptyList()
            val upper = content.uppercase()
            return SQL_OPERATION_PATTERNS.filter { Regex("\\b$it\\s+").containsMatchIn(upper) }
        }
    }
}

data class MigrationResult(
    val success: Boolean,
    val totalSchemas: Int,
    val schemaResults: List<SchemaResult>
)

data class SchemaResult(
    val schemaName: String,
    val success: Boolean,
    val totalQueries: Int,
    val successful: Int,
    val failed: Int,
    val errors: List<String>
)

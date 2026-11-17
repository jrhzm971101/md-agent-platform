package com.md.agentplatform.server.tool.mysql;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqlTextTest {

    @Test
    void recognizesReadOnlyStatements() {
        assertTrue(SqlText.isReadOnly("SELECT 1"));
        assertTrue(SqlText.isReadOnly("-- comment\nSELECT * FROM t"));
        assertTrue(SqlText.isReadOnly("/* comment */ SHOW TABLES"));
        assertTrue(SqlText.isReadOnly("WITH x AS (SELECT 1) SELECT * FROM x"));
        assertTrue(SqlText.isReadOnly("SELECT 'update; delete' AS text"));
    }

    @Test
    void rejectsWriteAndCteWriteStatements() {
        assertFalse(SqlText.isReadOnly("UPDATE t SET a = 1"));
        assertFalse(SqlText.isReadOnly("WITH x AS (SELECT 1) UPDATE t SET a = 1"));
        assertFalse(SqlText.isReadOnly("WITH x AS (SELECT 1) DELETE FROM t"));
    }

    @Test
    void detectsMultipleStatementsOutsideLiteralsAndComments() {
        assertFalse(SqlText.hasMultipleStatements("SELECT 1;"));
        assertFalse(SqlText.hasMultipleStatements("SELECT 1;;"));
        assertFalse(SqlText.hasMultipleStatements("SELECT ';' AS text;"));
        assertFalse(SqlText.hasMultipleStatements("SELECT 1; -- trailing comment"));
        assertTrue(SqlText.hasMultipleStatements("SELECT 1; DELETE FROM t"));
        assertTrue(SqlText.hasMultipleStatements("; SELECT 1"));
    }

    @Test
    void detectsAdministrativePhrasesButIgnoresLiterals() {
        assertTrue(SqlText.containsForbiddenAdmin("GRANT ALL ON *.* TO demo"));
        assertTrue(SqlText.containsForbiddenAdmin("SET GLOBAL max_connections = 10"));
        assertTrue(SqlText.containsForbiddenAdmin("SELECT * FROM t INTO OUTFILE '/tmp/x'"));
        assertTrue(SqlText.containsForbiddenAdmin("CREATE USER demo IDENTIFIED BY 'x'"));
        assertFalse(SqlText.containsForbiddenAdmin("SELECT * FROM t WHERE name = 'GRANT'"));
        assertFalse(SqlText.containsForbiddenAdmin("DELETE FROM t WHERE id = 1"));
    }

    @Test
    void classifiesSqlAndQuotesIdentifiers() {
        assertEquals("QUERY", SqlText.classify("SELECT 1"));
        assertEquals("DML", SqlText.classify("UPDATE t SET a = 1"));
        assertEquals("DDL", SqlText.classify("ALTER TABLE t ADD COLUMN c INT"));
        assertEquals("DCL", SqlText.classify("REVOKE SELECT ON db.* FROM demo"));
        assertEquals("`a``b`", SqlText.quoteIdentifier("a`b"));
    }
}

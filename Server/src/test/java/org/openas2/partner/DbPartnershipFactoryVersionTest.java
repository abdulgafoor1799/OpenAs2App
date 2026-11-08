package org.openas2.partner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the optional version based change detection and the fail-safe refresh of the
 * DbPartnershipFactory: a reload only happens when the version changes, and a database
 * failure during a refresh leaves the loaded partnerships in place.
 */
public class DbPartnershipFactoryVersionTest {

    private static final String VERSION_QUERY = "SELECT VER FROM config_version WHERE AREA = 'partnerships'";

    private String connectString;
    private Connection seedConn;
    private DbPartnershipFactory factory;
    private static int dbCounter = 0;

    @BeforeEach
    public void setUp() throws Exception {
        connectString = "jdbc:h2:mem:db_partnership_version_test_" + (dbCounter++) + ";DB_CLOSE_DELAY=-1";
        seedConn = DriverManager.getConnection(connectString, "sa", "");
        try (Statement s = seedConn.createStatement()) {
            String ddl = new String(Files.readAllBytes(Paths.get("src", "config", "db_ddl.sql")));
            for (String statement : ddl.split(";")) {
                if (!statement.trim().isEmpty()) {
                    s.execute(statement);
                }
            }
            s.execute("CREATE TABLE config_version (AREA VARCHAR(50) PRIMARY KEY, VER BIGINT NOT NULL)");
            s.executeUpdate("INSERT INTO config_version VALUES ('partnerships', 1)");
            s.executeUpdate("INSERT INTO partner (ID, NAME) VALUES (1, 'A')");
            s.executeUpdate("INSERT INTO partner (ID, NAME) VALUES (2, 'B')");
            s.executeUpdate("INSERT INTO partner_attribute (PARTNER_ID, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (1, 'as2_id', 'A_ID')");
            s.executeUpdate("INSERT INTO partner_attribute (PARTNER_ID, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (2, 'as2_id', 'B_ID')");
            s.executeUpdate("INSERT INTO partnership (ID, NAME, SENDER_PARTNER_ID, RECEIVER_PARTNER_ID) VALUES (1, 'A-to-B', 1, 2)");
            s.executeUpdate("INSERT INTO partnership_attribute (PARTNERSHIP_ID, CATEGORY, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (1, 'attribute', 'subject', 'first')");
        }
        factory = new DbPartnershipFactory();
        Map<String, String> params = new HashMap<String, String>();
        params.put(DbPartnershipFactory.PARAM_USE_EMBEDDED_DB, "true");
        params.put("tcp_server_start", "false");
        params.put(DbPartnershipFactory.PARAM_DB_USER, "sa");
        params.put(DbPartnershipFactory.PARAM_DB_PWD, "");
        params.put(DbPartnershipFactory.PARAM_JDBC_CONNECT_STRING, connectString);
        params.put(DbPartnershipFactory.PARAM_VERSION_QUERY, VERSION_QUERY);
        // Disable the periodic full refresh so only version changes trigger reloads
        params.put(DbPartnershipFactory.PARAM_FULL_REFRESH_INTERVAL, "0");
        factory.init(null, params);
    }

    @AfterEach
    public void tearDown() throws Exception {
        // Stopping the factory's embedded DB handler shuts the in-memory database down; every test uses its own
        factory.destroy();
        try {
            seedConn.close();
        } catch (Exception e) {
            // already closed by the shutdown
        }
    }

    private void exec(String sql) throws Exception {
        try (Statement s = seedConn.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private void bumpVersion() throws Exception {
        exec("UPDATE config_version SET VER = VER + 1 WHERE AREA = 'partnerships'");
    }

    private Partnership find(String name) {
        for (Partnership p : factory.getPartnerships()) {
            if (name.equals(p.getName())) {
                return p;
            }
        }
        return null;
    }

    @Test
    public void doesNotReloadWhenVersionUnchanged() throws Exception {
        assertFalse(factory.refreshIfChanged());
        // A change written without bumping the version is not picked up by the version check
        exec("UPDATE partnership_attribute SET ATTRIBUTE_VALUE = 'second' WHERE ATTRIBUTE_NAME = 'subject'");
        assertFalse(factory.refreshIfChanged());
        assertEquals("first", find("A-to-B").getAttribute("subject"));
    }

    @Test
    public void reloadsWhenVersionChanges() throws Exception {
        exec("INSERT INTO partnership (ID, NAME, SENDER_PARTNER_ID, RECEIVER_PARTNER_ID) VALUES (2, 'B-to-A', 2, 1)");
        exec("UPDATE partnership_attribute SET ATTRIBUTE_VALUE = 'second' WHERE ATTRIBUTE_NAME = 'subject'");
        bumpVersion();

        assertTrue(factory.refreshIfChanged());
        assertEquals(2, factory.getPartnerships().size());
        assertNotNull(find("B-to-A"));
        assertEquals("second", find("A-to-B").getAttribute("subject"));
        // Nothing changed since the reload
        assertFalse(factory.refreshIfChanged());
    }

    @Test
    public void failedReadKeepsLoadedPartnerships() throws Exception {
        // Make the next read fail part way through (attribute table unavailable) after a version bump
        exec("ALTER TABLE partnership_attribute RENAME TO partnership_attribute_moved");
        bumpVersion();

        assertThrows(Exception.class, () -> factory.refreshIfChanged());
        assertEquals(1, factory.getPartnerships().size());
        assertEquals("first", find("A-to-B").getAttribute("subject"));

        // Once the database is healthy again the pending change is picked up
        exec("ALTER TABLE partnership_attribute_moved RENAME TO partnership_attribute");
        assertTrue(factory.refreshIfChanged());
        assertEquals(1, factory.getPartnerships().size());
    }

    @Test
    public void inconsistentDataIsRejectedAndOldStateKept() throws Exception {
        exec("INSERT INTO partner (ID, NAME) VALUES (3, 'C')");
        exec("INSERT INTO partnership (ID, NAME, SENDER_PARTNER_ID, RECEIVER_PARTNER_ID) VALUES (3, 'C-to-B', 3, 2)");
        // Unknown category makes the whole read fail
        exec("INSERT INTO partnership_attribute (PARTNERSHIP_ID, CATEGORY, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (3, 'bogus', 'x', 'y')");
        bumpVersion();

        assertThrows(Exception.class, () -> factory.refreshIfChanged());
        assertNull(find("C-to-B"));
        assertEquals(1, factory.getPartnerships().size());
    }

    @Test
    public void missingVersionRowFailsWithoutDroppingState() throws Exception {
        exec("DELETE FROM config_version");
        assertThrows(Exception.class, () -> factory.refreshIfChanged());
        assertEquals(1, factory.getPartnerships().size());
    }
}

package org.openas2.cert;

import jakarta.mail.internet.MimeBodyPart;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.bouncycastle.operator.OutputEncryptor;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openas2.OpenAS2Exception;
import org.openas2.lib.helper.BCCryptoHelper;
import org.openas2.lib.helper.ICryptoHelper;
import org.openas2.util.AS2Util;

import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the database certificate factory: own key pairs (encrypted PKCS#8) from as2_own_key and partner
 * certificates from as2_partner_certificate, validated against the partner tables, assembled into an in-memory
 * keystore that works for AS2 signing/encryption in both directions, and refused (startup fails, reloads keep
 * the current keystore) when anything is inconsistent.
 */
public class DbCertificateFactoryTest {

    private static final String STORE_PASSWORD = "store-pw";
    private static final String KEY_PASSWORD = "key-encryption-pw";
    private static final String VERSION_QUERY = "SELECT VER FROM config_version WHERE AREA = 'certificates'";

    private static KeyPair ownKeys;
    private static X509Certificate ownCert;
    private static KeyPair retailerKeys;
    private static X509Certificate retailerCert;
    private static KeyPair otherKeys;
    private static X509Certificate otherCert;
    private static X509Certificate expiredCert;
    private static int dbCounter = 0;

    private String connectString;
    private Connection seedConn;
    private DbCertificateFactory factory;

    @BeforeAll
    public static void createKeys() throws Exception {
        AS2Util.getCryptoHelper(); // registers the BC provider
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        ownKeys = kpg.generateKeyPair();
        ownCert = cert(ownKeys, "CN=own", -1, 365);
        retailerKeys = kpg.generateKeyPair();
        retailerCert = cert(retailerKeys, "CN=retailer", -1, 365);
        otherKeys = kpg.generateKeyPair();
        otherCert = cert(otherKeys, "CN=other", -1, 365);
        expiredCert = cert(otherKeys, "CN=expired", -10, -1);
    }

    @BeforeEach
    public void setUp() throws Exception {
        connectString = "jdbc:h2:mem:db_cert_factory_test_" + (dbCounter++) + ";DB_CLOSE_DELAY=-1";
        seedConn = DriverManager.getConnection(connectString, "sa", "");
        try (Statement s = seedConn.createStatement()) {
            String ddl = new String(Files.readAllBytes(Paths.get("src", "config", "db_ddl.sql")));
            for (String statement : ddl.split(";")) {
                if (!statement.trim().isEmpty()) {
                    s.execute(statement);
                }
            }
            s.execute("CREATE TABLE as2_own_key (ALIAS VARCHAR(255) PRIMARY KEY, PRIVATE_KEY_PEM CLOB NOT NULL, CERT_CHAIN_PEM CLOB NOT NULL, ENABLED BOOLEAN DEFAULT TRUE NOT NULL)");
            s.execute("CREATE TABLE as2_partner_certificate (ALIAS VARCHAR(255) PRIMARY KEY, CERT_PEM CLOB NOT NULL, ENABLED BOOLEAN DEFAULT TRUE NOT NULL)");
            s.execute("CREATE TABLE config_version (AREA VARCHAR(50) PRIMARY KEY, VER BIGINT NOT NULL)");
            s.executeUpdate("INSERT INTO config_version VALUES ('certificates', 1)");
            // Our hosted identity and one retailer, with a partnership in each direction
            s.executeUpdate("INSERT INTO partner (ID, NAME) VALUES (1, 'OWN'), (2, 'RETAILER')");
            s.executeUpdate("INSERT INTO partner_attribute (PARTNER_ID, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES"
                    + " (1, 'as2_id', 'OWN_ID'), (1, 'x509_alias', 'own'), (2, 'as2_id', 'RETAILER_ID'), (2, 'x509_alias', 'retailer')");
            s.executeUpdate("INSERT INTO partnership (ID, NAME, SENDER_PARTNER_ID, RECEIVER_PARTNER_ID) VALUES (1, 'OWN-to-RETAILER', 1, 2), (2, 'RETAILER-to-OWN', 2, 1)");
        }
        insertOwnKey("own", encryptedPem(ownKeys, KEY_PASSWORD), pem(ownCert));
        insertPartnerCert("retailer", pem(retailerCert), true);
    }

    @AfterEach
    public void tearDown() throws Exception {
        if (factory != null) {
            factory.destroy();
            factory = null;
        }
        try {
            seedConn.close();
        } catch (Exception e) {
            // the embedded DB handler may already have shut the database down
        }
    }

    // ---- helpers ----

    private DbCertificateFactory newFactory() throws Exception {
        DbCertificateFactory f = new DbCertificateFactory();
        Map<String, String> params = new HashMap<String, String>();
        params.put(DbCertificateFactory.PARAM_PASSWORD, STORE_PASSWORD);
        params.put(DbCertificateFactory.PARAM_PRIVATE_KEY_PASSWORD, KEY_PASSWORD);
        params.put(DbCertificateFactory.PARAM_USE_EMBEDDED_DB, "true");
        params.put("tcp_server_start", "false");
        params.put(DbCertificateFactory.PARAM_DB_USER, "sa");
        params.put(DbCertificateFactory.PARAM_DB_PWD, "");
        params.put(DbCertificateFactory.PARAM_JDBC_CONNECT_STRING, connectString);
        params.put(DbCertificateFactory.PARAM_VERSION_QUERY, VERSION_QUERY);
        params.put(DbCertificateFactory.PARAM_FULL_REFRESH_INTERVAL, "0");
        try {
            f.init(null, params);
        } catch (Exception e) {
            f.destroy();
            throw e;
        }
        factory = f;
        return f;
    }

    /** Asserts that starting the factory fails with a validation error mentioning the given text. */
    private void assertStartupFails(String expectedText) {
        DbCertificateFactory.CertificateValidationException e =
                assertThrows(DbCertificateFactory.CertificateValidationException.class, this::newFactory);
        assertTrue(e.getMessage().contains(expectedText), "expected \"" + expectedText + "\" in: " + e.getMessage());
    }

    private void insertOwnKey(String alias, String keyPem, String chainPem) throws Exception {
        try (PreparedStatement ps = seedConn.prepareStatement("INSERT INTO as2_own_key (ALIAS, PRIVATE_KEY_PEM, CERT_CHAIN_PEM) VALUES (?, ?, ?)")) {
            ps.setString(1, alias);
            ps.setString(2, keyPem);
            ps.setString(3, chainPem);
            ps.executeUpdate();
        }
    }

    private void insertPartnerCert(String alias, String pem, boolean enabled) throws Exception {
        try (PreparedStatement ps = seedConn.prepareStatement("INSERT INTO as2_partner_certificate (ALIAS, CERT_PEM, ENABLED) VALUES (?, ?, ?)")) {
            ps.setString(1, alias);
            ps.setString(2, pem);
            ps.setBoolean(3, enabled);
            ps.executeUpdate();
        }
    }

    private void exec(String sql) throws Exception {
        try (Statement s = seedConn.createStatement()) {
            s.executeUpdate(sql);
        }
    }

    private void bumpVersion() throws Exception {
        exec("UPDATE config_version SET VER = VER + 1 WHERE AREA = 'certificates'");
    }

    // ---- successful load ----

    @Test
    public void buildsKeystoreFromBothTables() throws Exception {
        newFactory();
        assertEquals(ownKeys.getPrivate(), factory.getPrivateKey("own"));
        assertEquals(ownCert, factory.getCertificate("own"));
        assertEquals(retailerCert, factory.getCertificate("retailer"));
        assertFalse(factory.getKeyStore().isKeyEntry("retailer"));
        assertEquals(2, factory.getCertificates().size());
    }

    @Test
    public void keystoreWorksForSendingAndReceiving() throws Exception {
        newFactory();
        BCCryptoHelper crypto = new BCCryptoHelper();
        String payload = "ISA*00*TEST~";

        // Outbound: sign with our key, encrypt for the retailer; the retailer decrypts and verifies
        MimeBodyPart signed = crypto.sign(part(payload), factory.getCertificate("own"), factory.getPrivateKey("own"),
                ICryptoHelper.DIGEST_SHA256, "binary", false, false);
        MimeBodyPart encrypted = crypto.encrypt(signed, factory.getCertificate("retailer"), ICryptoHelper.CRYPT_3DES, "binary");
        MimeBodyPart retailerDecrypted = crypto.decrypt(encrypted, retailerCert, retailerKeys.getPrivate());
        assertEquals(payload, content(crypto.verifySignature(retailerDecrypted, ownCert)));

        // Inbound: the retailer signs and encrypts for us; we decrypt and verify using the factory
        MimeBodyPart retailerSigned = crypto.sign(part(payload), retailerCert, retailerKeys.getPrivate(),
                ICryptoHelper.DIGEST_SHA256, "binary", false, false);
        MimeBodyPart retailerEncrypted = crypto.encrypt(retailerSigned, ownCert, ICryptoHelper.CRYPT_3DES, "binary");
        MimeBodyPart decrypted = crypto.decrypt(retailerEncrypted, factory.getCertificate("own"), factory.getPrivateKey("own"));
        assertEquals(payload, content(crypto.verifySignature(decrypted, factory.getCertificate("retailer"))));
    }

    @Test
    public void disabledRowsAreIgnored() throws Exception {
        insertPartnerCert("old_expired", pem(expiredCert), false);
        newFactory();
        assertThrows(CertificateNotFoundException.class, () -> factory.getCertificate("old_expired"));
    }

    // ---- startup validation failures ----

    @Test
    public void startupFailsWhenAPartnerAliasHasNoCertificate() throws Exception {
        exec("INSERT INTO partner (ID, NAME) VALUES (3, 'SUPPLIER')");
        exec("INSERT INTO partner_attribute (PARTNER_ID, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (3, 'x509_alias', 'supplier_key')");
        assertStartupFails("\"supplier_key\"");
    }

    @Test
    public void startupFailsWhenTablesAreEmpty() throws Exception {
        exec("DELETE FROM as2_own_key");
        exec("DELETE FROM as2_partner_certificate");
        assertStartupFails("\"own\"");
    }

    @Test
    public void startupFailsWhenAPartnerHasNoAlias() throws Exception {
        exec("INSERT INTO partner (ID, NAME) VALUES (3, 'NO_ALIAS')");
        assertStartupFails("partner \"NO_ALIAS\" has no x509_alias");
    }

    @Test
    public void startupFailsWhenAPartnershipOverrideAliasIsMissing() throws Exception {
        exec("INSERT INTO partnership_attribute (PARTNERSHIP_ID, CATEGORY, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (1, 'sender', 'x509_alias', 'own_2027')");
        assertStartupFails("\"own_2027\"");
    }

    @Test
    public void startupFailsWhenNoSideOfAPartnershipHasAPrivateKey() throws Exception {
        insertPartnerCert("retailer2", pem(otherCert), true);
        exec("INSERT INTO partner (ID, NAME) VALUES (3, 'RETAILER2')");
        exec("INSERT INTO partner_attribute (PARTNER_ID, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (3, 'x509_alias', 'retailer2')");
        exec("INSERT INTO partnership (ID, NAME, SENDER_PARTNER_ID, RECEIVER_PARTNER_ID) VALUES (3, 'RETAILER-to-RETAILER2', 2, 3)");
        assertStartupFails("neither the sender");
    }

    @Test
    public void startupFailsWhenPrivateKeyDoesNotMatchCertificate() throws Exception {
        exec("DELETE FROM as2_own_key");
        insertOwnKey("own", encryptedPem(otherKeys, KEY_PASSWORD), pem(ownCert));
        assertStartupFails("private key does not match the certificate");
    }

    @Test
    public void startupFailsWithWrongKeyEncryptionPassword() throws Exception {
        exec("DELETE FROM as2_own_key");
        insertOwnKey("own", encryptedPem(ownKeys, "some-other-password"), pem(ownCert));
        assertStartupFails("decryption failed");
    }

    @Test
    public void startupFailsForUnencryptedPrivateKey() throws Exception {
        exec("DELETE FROM as2_own_key");
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter w = new JcaPEMWriter(sw)) {
            w.writeObject(new JcaPKCS8Generator(ownKeys.getPrivate(), null));
        }
        insertOwnKey("own", sw.toString(), pem(ownCert));
        assertStartupFails("not encrypted");
    }

    @Test
    public void startupFailsForExpiredCertificate() throws Exception {
        exec("UPDATE as2_partner_certificate SET CERT_PEM = '" + pem(expiredCert) + "' WHERE ALIAS = 'retailer'");
        assertStartupFails("certificate expired");
    }

    @Test
    public void startupFailsWhenAliasIsInBothTables() throws Exception {
        insertPartnerCert("OWN", pem(otherCert), true); // differs only by case
        assertStartupFails("clashes with");
    }

    @Test
    public void startupFailsWhenPartnerRowContainsAPrivateKey() throws Exception {
        exec("UPDATE as2_partner_certificate SET CERT_PEM = '" + pem(retailerCert) + encryptedPem(retailerKeys, "x") + "' WHERE ALIAS = 'retailer'");
        assertStartupFails("contains private key material");
    }

    @Test
    public void startupFailsForBrokenCertificateChain() throws Exception {
        exec("DELETE FROM as2_own_key");
        insertOwnKey("own", encryptedPem(ownKeys, KEY_PASSWORD), pem(ownCert) + pem(otherCert));
        assertStartupFails("is not signed by chain certificate");
    }

    @Test
    public void startupFailsForUnparsablePartnerCertificate() throws Exception {
        exec("UPDATE as2_partner_certificate SET CERT_PEM = '-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----' WHERE ALIAS = 'retailer'");
        assertStartupFails("cannot be parsed");
    }

    // ---- reloads ----

    @Test
    public void reloadsOnlyWhenVersionChanges() throws Exception {
        newFactory();
        assertFalse(factory.refreshIfChanged());
        exec("INSERT INTO partner (ID, NAME) VALUES (3, 'SUPPLIER')");
        exec("INSERT INTO partner_attribute (PARTNER_ID, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (3, 'x509_alias', 'supplier')");
        exec("INSERT INTO partnership (ID, NAME, SENDER_PARTNER_ID, RECEIVER_PARTNER_ID) VALUES (3, 'SUPPLIER-to-RETAILER', 3, 2)");
        insertOwnKey("supplier", encryptedPem(otherKeys, KEY_PASSWORD), pem(otherCert));
        assertFalse(factory.refreshIfChanged());

        KeyStore before = factory.getKeyStore();
        bumpVersion();
        assertTrue(factory.refreshIfChanged());
        assertNotSame(before, factory.getKeyStore());
        assertEquals(otherKeys.getPrivate(), factory.getPrivateKey("supplier"));
        // The previous instance is left intact for lookups still using it
        assertFalse(before.containsAlias("supplier"));
    }

    @Test
    public void invalidReloadKeepsTheCurrentKeystore() throws Exception {
        newFactory();
        KeyStore before = factory.getKeyStore();
        exec("UPDATE as2_partner_certificate SET CERT_PEM = 'garbage' WHERE ALIAS = 'retailer'");
        bumpVersion();

        assertThrows(DbCertificateFactory.CertificateValidationException.class, () -> factory.refreshIfChanged());
        assertSame(before, factory.getKeyStore());
        assertEquals(retailerCert, factory.getCertificate("retailer"));

        exec("UPDATE as2_partner_certificate SET CERT_PEM = '" + pem(retailerCert) + "' WHERE ALIAS = 'retailer'");
        bumpVersion();
        assertTrue(factory.refreshIfChanged());
    }

    @Test
    public void databaseFailureDuringReloadKeepsTheCurrentKeystore() throws Exception {
        newFactory();
        KeyStore before = factory.getKeyStore();
        exec("ALTER TABLE as2_partner_certificate RENAME TO moved_away");
        bumpVersion();
        assertThrows(Exception.class, () -> factory.refreshIfChanged());
        assertSame(before, factory.getKeyStore());
    }

    @Test
    public void keystoreIsReadOnly() throws Exception {
        newFactory();
        assertThrows(OpenAS2Exception.class, () -> factory.addCertificate("x", otherCert, true));
        assertThrows(OpenAS2Exception.class, () -> factory.addPrivateKey("own", ownKeys.getPrivate(), STORE_PASSWORD));
        assertThrows(OpenAS2Exception.class, () -> factory.removeCertificate("retailer"));
        assertThrows(OpenAS2Exception.class, () -> factory.clearCertificates());
        assertThrows(OpenAS2Exception.class, () -> factory.save());
    }

    // ---- fixtures ----

    private static MimeBodyPart part(String payload) throws Exception {
        MimeBodyPart part = new MimeBodyPart();
        part.setContent(payload, "application/edi-x12");
        part.setHeader("Content-Type", "application/edi-x12");
        return part;
    }

    private static String content(MimeBodyPart part) throws Exception {
        return new String(part.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String pem(X509Certificate cert) throws Exception {
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(cert);
        }
        return sw.toString();
    }

    private static String encryptedPem(KeyPair keyPair, String password) throws Exception {
        OutputEncryptor encryptor = new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC)
                .setProvider("BC").setPassword(password.toCharArray()).build();
        StringWriter sw = new StringWriter();
        try (JcaPEMWriter writer = new JcaPEMWriter(sw)) {
            writer.writeObject(new JcaPKCS8Generator(keyPair.getPrivate(), encryptor));
        }
        return sw.toString();
    }

    private static X509Certificate cert(KeyPair keyPair, String dn, int fromDays, int toDays) throws Exception {
        X500Name subject = new X500Name(dn);
        long now = System.currentTimeMillis();
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.nanoTime()),
                new Date(now + fromDays * 24L * 3600_000L), new Date(now + toDays * 24L * 3600_000L),
                subject, keyPair.getPublic());
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate())));
    }
}

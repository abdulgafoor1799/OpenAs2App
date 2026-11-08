package org.openas2.cert;

import jakarta.mail.internet.MimeBodyPart;
import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openas2.OpenAS2Exception;
import org.openas2.lib.helper.BCCryptoHelper;
import org.openas2.lib.helper.ICryptoHelper;
import org.openas2.util.AS2Util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.StringWriter;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the read-only DbCertificateFactory: own keys from a PKCS12 file, partner certificates from the
 * database, change detection, fail-safe reloads and that the resulting keystore works for AS2 signing,
 * encryption, decryption and signature verification in both directions.
 */
public class DbCertificateFactoryTest {

    private static final String PASSWORD = "testpw";
    private static final String VERSION_QUERY = "SELECT VER FROM config_version WHERE AREA = 'certificates'";

    private static KeyPair ownKeys;
    private static X509Certificate ownCert;
    private static KeyPair partnerKeys;
    private static X509Certificate partnerCert;
    private static X509Certificate otherPartnerCert;
    private static int dbCounter = 0;

    @TempDir
    File tempDir;

    private Connection seedConn;
    private DbCertificateFactory factory;
    private File ownKeysFile;

    @BeforeAll
    public static void createKeys() throws Exception {
        AS2Util.getCryptoHelper(); // registers the BC provider
        KeyPairGenerator kpg = KeyPairGenerator.getInstance("RSA");
        kpg.initialize(2048);
        ownKeys = kpg.generateKeyPair();
        ownCert = selfSignedCert(ownKeys, "CN=own");
        partnerKeys = kpg.generateKeyPair();
        partnerCert = selfSignedCert(partnerKeys, "CN=partner");
        otherPartnerCert = selfSignedCert(kpg.generateKeyPair(), "CN=other");
    }

    @BeforeEach
    public void setUp() throws Exception {
        ownKeysFile = new File(tempDir, "own_keys.p12");
        KeyStore ks = AS2Util.getCryptoHelper().getKeyStore();
        ks.load(null, PASSWORD.toCharArray());
        ks.setKeyEntry("own", ownKeys.getPrivate(), PASSWORD.toCharArray(), new Certificate[]{ownCert});
        try (FileOutputStream out = new FileOutputStream(ownKeysFile)) {
            ks.store(out, PASSWORD.toCharArray());
        }

        String connectString = "jdbc:h2:mem:db_cert_factory_test_" + (dbCounter++) + ";DB_CLOSE_DELAY=-1";
        seedConn = DriverManager.getConnection(connectString, "sa", "");
        try (Statement s = seedConn.createStatement()) {
            s.execute("CREATE TABLE as2_partner_certificate (ALIAS VARCHAR(255) PRIMARY KEY, CERT_PEM CLOB NOT NULL, ENABLED BOOLEAN DEFAULT TRUE NOT NULL)");
            s.execute("CREATE TABLE config_version (AREA VARCHAR(50) PRIMARY KEY, VER BIGINT NOT NULL)");
            s.executeUpdate("INSERT INTO config_version VALUES ('certificates', 1)");
        }
        insertCert("partner", pem(partnerCert), true);
        insertCert("disabled_partner", pem(otherPartnerCert), false);

        factory = new DbCertificateFactory();
        Map<String, String> params = new HashMap<String, String>();
        params.put(DbCertificateFactory.PARAM_OWN_KEYS_FILE, ownKeysFile.getAbsolutePath());
        params.put(DbCertificateFactory.PARAM_PASSWORD, PASSWORD);
        params.put(DbCertificateFactory.PARAM_USE_EMBEDDED_DB, "true");
        params.put("tcp_server_start", "false");
        params.put(DbCertificateFactory.PARAM_DB_USER, "sa");
        params.put(DbCertificateFactory.PARAM_DB_PWD, "");
        params.put(DbCertificateFactory.PARAM_JDBC_CONNECT_STRING, connectString);
        params.put(DbCertificateFactory.PARAM_VERSION_QUERY, VERSION_QUERY);
        params.put(DbCertificateFactory.PARAM_FULL_REFRESH_INTERVAL, "0");
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

    private void insertCert(String alias, String pem, boolean enabled) throws Exception {
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

    @Test
    public void loadsOwnKeysAndEnabledPartnerCertificates() throws Exception {
        assertEquals(ownKeys.getPrivate(), factory.getPrivateKey("own"));
        assertEquals(ownCert, factory.getCertificate("own"));
        assertEquals(partnerCert, factory.getCertificate("partner"));
        assertThrows(CertificateNotFoundException.class, () -> factory.getCertificate("disabled_partner"));
        assertEquals(2, factory.getCertificates().size());
    }

    @Test
    public void keystoreWorksForSendingAndReceiving() throws Exception {
        BCCryptoHelper crypto = new BCCryptoHelper();
        String payload = "ISA*00*TEST~";

        // Outbound: we sign with our key and encrypt for the partner; the partner decrypts and verifies
        MimeBodyPart outbound = part(payload);
        MimeBodyPart signed = crypto.sign(outbound, factory.getCertificate("own"), factory.getPrivateKey("own"),
                ICryptoHelper.DIGEST_SHA256, "binary", false, false);
        MimeBodyPart encrypted = crypto.encrypt(signed, factory.getCertificate("partner"), ICryptoHelper.CRYPT_3DES, "binary");
        MimeBodyPart partnerDecrypted = crypto.decrypt(encrypted, partnerCert, partnerKeys.getPrivate());
        MimeBodyPart partnerVerified = crypto.verifySignature(partnerDecrypted, ownCert);
        assertEquals(payload, content(partnerVerified));

        // Inbound: the partner signs with its key and encrypts for us; we decrypt and verify with the factory
        MimeBodyPart inbound = part(payload);
        MimeBodyPart partnerSigned = crypto.sign(inbound, partnerCert, partnerKeys.getPrivate(),
                ICryptoHelper.DIGEST_SHA256, "binary", false, false);
        MimeBodyPart partnerEncrypted = crypto.encrypt(partnerSigned, ownCert, ICryptoHelper.CRYPT_3DES, "binary");
        MimeBodyPart decrypted = crypto.decrypt(partnerEncrypted, factory.getCertificate("own"), factory.getPrivateKey("own"));
        MimeBodyPart verified = crypto.verifySignature(decrypted, factory.getCertificate("partner"));
        assertEquals(payload, content(verified));
    }

    @Test
    public void reloadsOnlyWhenVersionChanges() throws Exception {
        assertFalse(factory.refreshIfChanged());
        insertCert("new_partner", pem(otherPartnerCert), true);
        // Not visible until the version changes
        assertFalse(factory.refreshIfChanged());
        assertThrows(CertificateNotFoundException.class, () -> factory.getCertificate("new_partner"));

        bumpVersion();
        assertTrue(factory.refreshIfChanged());
        assertEquals(otherPartnerCert, factory.getCertificate("new_partner"));
        assertFalse(factory.refreshIfChanged());
    }

    @Test
    public void reloadPublishesANewKeystoreInstance() throws Exception {
        KeyStore before = factory.getKeyStore();
        bumpVersion();
        assertTrue(factory.refreshIfChanged());
        KeyStore after = factory.getKeyStore();
        assertFalse(before == after);
        // The old instance is left intact for any lookup still using it
        assertEquals(partnerCert, before.getCertificate("partner"));
    }

    @Test
    public void unparsableUpdateKeepsCurrentCertificateAndSkipsNewAlias() throws Exception {
        exec("UPDATE as2_partner_certificate SET CERT_PEM = 'not a certificate' WHERE ALIAS = 'partner'");
        insertCert("broken_new", "-----BEGIN CERTIFICATE-----\nAAAA\n-----END CERTIFICATE-----", true);
        bumpVersion();

        assertTrue(factory.refreshIfChanged());
        assertEquals(partnerCert, factory.getCertificate("partner"));
        assertThrows(CertificateNotFoundException.class, () -> factory.getCertificate("broken_new"));
    }

    @Test
    public void databaseAliasCannotReplaceOwnKey() throws Exception {
        insertCert("own", pem(otherPartnerCert), true);
        bumpVersion();

        assertTrue(factory.refreshIfChanged());
        assertEquals(ownCert, factory.getCertificate("own"));
        assertEquals(ownKeys.getPrivate(), factory.getPrivateKey("own"));
    }

    @Test
    public void databaseFailureKeepsCurrentKeystore() throws Exception {
        KeyStore before = factory.getKeyStore();
        exec("ALTER TABLE as2_partner_certificate RENAME TO as2_partner_certificate_moved");
        bumpVersion();

        assertThrows(Exception.class, () -> factory.refreshIfChanged());
        assertSame(before, factory.getKeyStore());
        assertEquals(partnerCert, factory.getCertificate("partner"));

        exec("ALTER TABLE as2_partner_certificate_moved RENAME TO as2_partner_certificate");
        assertTrue(factory.refreshIfChanged());
    }

    @Test
    public void missingOwnKeysFileFailsReloadAndKeepsKeystore() throws Exception {
        KeyStore before = factory.getKeyStore();
        assertTrue(ownKeysFile.delete());
        assertThrows(OpenAS2Exception.class, () -> factory.refreshIfChanged());
        assertSame(before, factory.getKeyStore());
        assertNotNull(factory.getPrivateKey("own"));
    }

    @Test
    public void keystoreIsReadOnly() {
        assertThrows(OpenAS2Exception.class, () -> factory.addCertificate("x", partnerCert, true));
        assertThrows(OpenAS2Exception.class, () -> factory.addPrivateKey("own", ownKeys.getPrivate(), PASSWORD));
        assertThrows(OpenAS2Exception.class, () -> factory.removeCertificate("partner"));
        assertThrows(OpenAS2Exception.class, () -> factory.clearCertificates());
        assertThrows(OpenAS2Exception.class, () -> factory.save());
    }

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

    private static X509Certificate selfSignedCert(KeyPair keyPair, String dn) throws Exception {
        X500Name subject = new X500Name(dn);
        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                subject, BigInteger.valueOf(System.nanoTime()),
                new Date(System.currentTimeMillis() - 3600_000L),
                new Date(System.currentTimeMillis() + 24 * 3600_000L),
                subject, keyPair.getPublic());
        return new JcaX509CertificateConverter().getCertificate(
                builder.build(new JcaContentSignerBuilder("SHA256withRSA").build(keyPair.getPrivate())));
    }
}

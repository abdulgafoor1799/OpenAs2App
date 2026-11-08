package org.openas2.cert;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.openas2.OpenAS2Exception;
import org.openas2.WrappedException;
import org.openas2.params.ComponentParameters;
import org.openas2.params.CompositeParameters;
import org.openas2.params.InvalidParameterException;
import org.openas2.params.ParameterParser;
import org.openas2.processor.msgtracking.EmbeddedDBHandler;
import org.openas2.processor.msgtracking.ExternalDBHandler;
import org.openas2.processor.msgtracking.IDBHandler;
import org.openas2.schedule.HasSchedule;
import org.openas2.util.AS2Util;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.Certificate;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAKey;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A read-only certificate factory that builds the AS2 keystore in memory from two database tables:
 * <ul>
 * <li>{@code as2_own_key}: key pairs for the AS2 identities hosted by this server (our own IDs, the IDs of the
 * suppliers we operate, test/health-check IDs). The private key is stored as an <b>encrypted</b> PKCS#8 PEM
 * ("BEGIN ENCRYPTED PRIVATE KEY"), decrypted with the "private_key_password" parameter, together with the
 * certificate chain (leaf first).</li>
 * <li>{@code as2_partner_certificate}: public certificates of remote trading partners (retailers etc.).</li>
 * </ul>
 * Nothing is read from or written to the file system.
 * <p>
 * Every load reads the key tables and the partner tables in one database snapshot, validates everything,
 * builds a complete new keystore, verifies it and only then publishes it (atomic swap of the volatile keystore
 * reference). Validation errors are collected and reported together. At startup any validation error fails the
 * component, so OpenAS2 does not start with an incomplete or inconsistent keystore. On a scheduled reload a
 * validation error keeps the current keystore in use.
 * <p>
 * Validations (errors unless stated otherwise):
 * <ul>
 * <li>own keys: the private key PEM is encrypted (unless "allow_unencrypted_keys" is true), decrypts with the
 * configured password, the certificate chain parses, the private key matches the leaf certificate (sign/verify
 * test), each chain certificate is signed by the next one;</li>
 * <li>partner certificates: the PEM parses as an X.509 certificate and contains no private key;</li>
 * <li>all certificates: currently valid (expired or not yet valid is an error when "fail_on_expired" is true,
 * otherwise a warning); expiring within "expiry_warning_days" (default 30) is a warning; RSA keys below 2048 bits
 * are a warning;</li>
 * <li>aliases are unique across both tables (case-insensitively, as PKCS12 aliases are case-insensitive);</li>
 * <li>every partner's x509_alias, every per-partnership sender/receiver x509_alias override, and every
 * x509_alias_fallback (certificate rollover) resolves to an enabled entry in one of the two tables;</li>
 * <li>every partnership has a private key for at least one side (otherwise it can neither sign/decrypt nor
 * receive);</li>
 * <li>certificates not referenced by any partner are a warning.</li>
 * </ul>
 * Change detection: when "version_query" is set, each scheduled tick (every "interval" seconds) only runs that
 * query and the keystore is rebuilt when the value changes, or every "full_refresh_interval" seconds
 * (default 3600, 0 disables).
 * <p>
 * Certificates are managed outside OpenAS2, so every method that would change the keystore throws.
 */
public class DbCertificateFactory extends X509CertificateFactory implements HasSchedule {

    public static final String PARAM_PRIVATE_KEY_PASSWORD = "private_key_password";
    public static final String PARAM_DB_USER = "db_user";
    public static final String PARAM_DB_PWD = "db_pwd";
    public static final String PARAM_JDBC_CONNECT_STRING = "jdbc_connect_string";
    public static final String PARAM_JDBC_DRIVER = "jdbc_driver";
    public static final String PARAM_FORCE_LOAD_JDBC_DRIVER = "force_load_jdbc_driver";
    public static final String PARAM_USE_EMBEDDED_DB = "use_embedded_db";
    public static final String PARAM_OWN_KEY_QUERY = "own_key_query";
    public static final String PARAM_PARTNER_CERT_QUERY = "partner_cert_query";
    public static final String PARAM_PARTNER_ALIAS_QUERY = "partner_alias_query";
    public static final String PARAM_PARTNERSHIP_ALIAS_QUERY = "partnership_alias_query";
    public static final String PARAM_FALLBACK_ALIAS_QUERY = "fallback_alias_query";
    public static final String PARAM_VALIDATE_PARTNER_ALIASES = "validate_partner_aliases";
    public static final String PARAM_FAIL_ON_EXPIRED = "fail_on_expired";
    public static final String PARAM_EXPIRY_WARNING_DAYS = "expiry_warning_days";
    public static final String PARAM_ALLOW_UNENCRYPTED_KEYS = "allow_unencrypted_keys";
    public static final String PARAM_VERSION_QUERY = "version_query";
    public static final String PARAM_FULL_REFRESH_INTERVAL = "full_refresh_interval";

    public static final int DEFAULT_FULL_REFRESH_INTERVAL = 3600;
    public static final int DEFAULT_EXPIRY_WARNING_DAYS = 30;

    public static final String DEFAULT_OWN_KEY_QUERY =
            "SELECT ALIAS, PRIVATE_KEY_PEM, CERT_CHAIN_PEM FROM as2_own_key WHERE ENABLED = TRUE ORDER BY ALIAS";
    public static final String DEFAULT_PARTNER_CERT_QUERY =
            "SELECT ALIAS, CERT_PEM FROM as2_partner_certificate WHERE ENABLED = TRUE ORDER BY ALIAS";
    /** Partner name and its x509_alias attribute (null when the partner has none). */
    public static final String DEFAULT_PARTNER_ALIAS_QUERY =
            "SELECT p.NAME, a.ATTRIBUTE_VALUE FROM partner p"
            + " LEFT JOIN partner_attribute a ON a.PARTNER_ID = p.ID AND a.ATTRIBUTE_NAME = 'x509_alias'"
            + " ORDER BY p.NAME";
    /** Partnership name with the effective sender and receiver x509_alias (partnership override, else partner). */
    public static final String DEFAULT_PARTNERSHIP_ALIAS_QUERY =
            "SELECT ps.NAME, COALESCE(so.ATTRIBUTE_VALUE, sa.ATTRIBUTE_VALUE), COALESCE(ro.ATTRIBUTE_VALUE, ra.ATTRIBUTE_VALUE)"
            + " FROM partnership ps"
            + " LEFT JOIN partner_attribute sa ON sa.PARTNER_ID = ps.SENDER_PARTNER_ID AND sa.ATTRIBUTE_NAME = 'x509_alias'"
            + " LEFT JOIN partner_attribute ra ON ra.PARTNER_ID = ps.RECEIVER_PARTNER_ID AND ra.ATTRIBUTE_NAME = 'x509_alias'"
            + " LEFT JOIN partnership_attribute so ON so.PARTNERSHIP_ID = ps.ID AND so.CATEGORY = 'sender' AND so.ATTRIBUTE_NAME = 'x509_alias'"
            + " LEFT JOIN partnership_attribute ro ON ro.PARTNERSHIP_ID = ps.ID AND ro.CATEGORY = 'receiver' AND ro.ATTRIBUTE_NAME = 'x509_alias'"
            + " ORDER BY ps.NAME";
    /**
     * Every x509_alias_fallback (certificate rollover alias, tried by the receiver when decryption or signature
     * verification with x509_alias fails), on partners and as partnership sender/receiver overrides.
     */
    public static final String DEFAULT_FALLBACK_ALIAS_QUERY =
            "SELECT p.NAME, a.ATTRIBUTE_VALUE FROM partner p"
            + " JOIN partner_attribute a ON a.PARTNER_ID = p.ID AND a.ATTRIBUTE_NAME = 'x509_alias_fallback'"
            + " UNION ALL SELECT ps.NAME, pa.ATTRIBUTE_VALUE FROM partnership ps"
            + " JOIN partnership_attribute pa ON pa.PARTNERSHIP_ID = ps.ID AND pa.CATEGORY IN ('sender', 'receiver')"
            + " AND pa.ATTRIBUTE_NAME = 'x509_alias_fallback'";

    private static final String READ_ONLY_MSG =
            "Certificates are managed in the database (as2_own_key / as2_partner_certificate); the keystore is read-only";

    private final Logger logger = LoggerFactory.getLogger(DbCertificateFactory.class);

    private IDBHandler dbHandler = null;
    private volatile String loadedVersion = null;
    private volatile long lastFullLoadMillis = 0;
    private int consecutiveFailures = 0;

    /** Thrown when the data read from the database does not produce a valid keystore. */
    public static class CertificateValidationException extends OpenAS2Exception {
        private static final long serialVersionUID = 1L;
        private final List<String> problems;

        public CertificateValidationException(List<String> problems) {
            super("AS2 keystore validation failed with " + problems.size() + " error(s):\n  - " + String.join("\n  - ", problems));
            this.problems = problems;
        }

        public List<String> getProblems() {
            return problems;
        }
    }

    // ---- Data read from the database in one snapshot ----

    private static final class Snapshot {
        String version;
        final Map<String, String[]> ownKeys = new LinkedHashMap<String, String[]>();   // alias -> {key pem, chain pem}
        final Map<String, String> partnerCerts = new LinkedHashMap<String, String>();  // alias -> cert pem
        final Map<String, String> partnerAliases = new LinkedHashMap<String, String>(); // partner -> x509_alias
        final List<String[]> partnershipAliases = new ArrayList<String[]>();           // {name, sender alias, receiver alias}
        final List<String[]> fallbackAliases = new ArrayList<String[]>();              // {partner or partnership, alias}
    }

    /** Messages collected while validating; errors fail the load, warnings are only logged. */
    private static final class Findings {
        final List<String> errors = new ArrayList<String>();
        final List<String> warnings = new ArrayList<String>();
    }

    @Override
    public String getFilename() {
        // There is no keystore file; used only in log and error messages by inherited code
        return "database";
    }

    // ---- Loading ----

    /**
     * Reads both certificate tables and the partner tables, validates them, builds a new keystore and publishes
     * it. On failure the current keystore (if any) stays in use and the exception lists every problem found.
     */
    @Override
    public synchronized void load() throws OpenAS2Exception {
        ensureDbHandler();
        Snapshot snapshot = readSnapshot();
        Findings findings = new Findings();
        KeyStore newKs = buildKeyStore(snapshot, findings);
        if (findings.errors.isEmpty()) {
            verifyKeyStore(newKs, snapshot, findings);
        }
        for (String warning : findings.warnings) {
            logger.warn("Keystore " + getIdentifier() + ": " + warning);
        }
        if (!findings.errors.isEmpty()) {
            // ERROR (which may trigger alert emails) only for the first failure; scheduled retries of the same
            // broken data are logged as warnings until a reload succeeds
            boolean repeatedFailure = consecutiveFailures > 0;
            for (String error : findings.errors) {
                if (repeatedFailure) {
                    logger.warn("Keystore " + getIdentifier() + ": " + error);
                } else {
                    logger.error("Keystore " + getIdentifier() + ": " + error);
                }
            }
            throw new CertificateValidationException(findings.errors);
        }
        // Publish the complete, verified keystore in one step (the field is volatile)
        setKeyStore(newKs);
        loadedVersion = snapshot.version;
        lastFullLoadMillis = System.currentTimeMillis();
        logger.info("Loaded " + snapshot.ownKeys.size() + " own key pairs and " + snapshot.partnerCerts.size()
                + " partner certificates" + (snapshot.version != null ? " (version " + snapshot.version + ")" : "")
                + " into keystore " + getIdentifier() + " after validating " + snapshot.partnerAliases.size()
                + " partners and " + snapshot.partnershipAliases.size() + " partnerships");
    }

    private Snapshot readSnapshot() throws OpenAS2Exception {
        Snapshot snapshot = new Snapshot();
        String versionQuery = getVersionQuery();
        boolean validateAliases = isValidatePartnerAliases();
        try (Connection conn = dbHandler.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            int isolation = conn.getTransactionIsolation();
            try {
                // One snapshot so keys, certificates, partners and the version are consistent with each other
                conn.setAutoCommit(false);
                try {
                    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                } catch (SQLException e) {
                    logger.debug("Repeatable read isolation not supported, using the default: " + e.getMessage());
                }
                if (versionQuery != null) {
                    snapshot.version = readVersion(conn, versionQuery);
                }
                try (PreparedStatement s = conn.prepareStatement(getParameter(PARAM_OWN_KEY_QUERY, DEFAULT_OWN_KEY_QUERY));
                     ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        putUnique(snapshot.ownKeys, rs.getString(1), new String[]{rs.getString(2), rs.getString(3)}, "as2_own_key");
                    }
                }
                try (PreparedStatement s = conn.prepareStatement(getParameter(PARAM_PARTNER_CERT_QUERY, DEFAULT_PARTNER_CERT_QUERY));
                     ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        putUnique(snapshot.partnerCerts, rs.getString(1), rs.getString(2), "as2_partner_certificate");
                    }
                }
                if (validateAliases) {
                    try (PreparedStatement s = conn.prepareStatement(getParameter(PARAM_PARTNER_ALIAS_QUERY, DEFAULT_PARTNER_ALIAS_QUERY));
                         ResultSet rs = s.executeQuery()) {
                        while (rs.next()) {
                            snapshot.partnerAliases.put(rs.getString(1), rs.getString(2));
                        }
                    }
                    try (PreparedStatement s = conn.prepareStatement(getParameter(PARAM_PARTNERSHIP_ALIAS_QUERY, DEFAULT_PARTNERSHIP_ALIAS_QUERY));
                         ResultSet rs = s.executeQuery()) {
                        while (rs.next()) {
                            snapshot.partnershipAliases.add(new String[]{rs.getString(1), rs.getString(2), rs.getString(3)});
                        }
                    }
                    try (PreparedStatement s = conn.prepareStatement(getParameter(PARAM_FALLBACK_ALIAS_QUERY, DEFAULT_FALLBACK_ALIAS_QUERY));
                         ResultSet rs = s.executeQuery()) {
                        while (rs.next()) {
                            snapshot.fallbackAliases.add(new String[]{rs.getString(1), rs.getString(2)});
                        }
                    }
                }
                conn.commit();
                return snapshot;
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException re) {
                    logger.debug("Rollback after failed certificate read failed: " + re.getMessage());
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(autoCommit);
                    conn.setTransactionIsolation(isolation);
                } catch (SQLException e) {
                    logger.debug("Failed to restore connection settings: " + e.getMessage());
                }
            }
        } catch (OpenAS2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new WrappedException(e);
        }
    }

    private static <V> void putUnique(Map<String, V> map, String alias, V value, String table) throws OpenAS2Exception {
        if (map.put(alias, value) != null) {
            throw new OpenAS2Exception("Alias \"" + alias + "\" is returned more than once from " + table);
        }
    }

    // ---- Building and validating ----

    private KeyStore buildKeyStore(Snapshot snapshot, Findings findings) throws OpenAS2Exception {
        char[] password = getPassword();
        KeyStore ks;
        try {
            ks = AS2Util.getCryptoHelper().getKeyStore();
            ks.load(null, password);
        } catch (Exception e) {
            throw new WrappedException(e);
        }
        boolean failOnExpired = !"false".equalsIgnoreCase(getParameter(PARAM_FAIL_ON_EXPIRED, "true"));
        int warningDays = getParameterInt(PARAM_EXPIRY_WARNING_DAYS, false, DEFAULT_EXPIRY_WARNING_DAYS);
        boolean allowUnencrypted = "true".equalsIgnoreCase(getParameter(PARAM_ALLOW_UNENCRYPTED_KEYS, "false"));
        char[] keyPassword = getPrivateKeyPassword();

        // Aliases must be unique across both tables; PKCS12 keystore aliases are case-insensitive
        Map<String, String> aliasOwner = new HashMap<String, String>();
        Map<String, String> fingerprints = new HashMap<String, String>();

        for (Map.Entry<String, String[]> row : snapshot.ownKeys.entrySet()) {
            String alias = row.getKey();
            String where = "own key \"" + alias + "\"";
            if (!checkAlias(alias, "as2_own_key", aliasOwner, findings)) {
                continue;
            }
            PrivateKey key;
            X509Certificate[] chain;
            try {
                key = decryptPrivateKey(row.getValue()[0], keyPassword, allowUnencrypted);
            } catch (Exception e) {
                findings.errors.add(where + ": private key cannot be read: " + e.getMessage());
                continue;
            }
            try {
                chain = parseCertificates(row.getValue()[1]);
            } catch (Exception e) {
                findings.errors.add(where + ": certificate chain cannot be parsed: " + e.getMessage());
                continue;
            }
            if (!keyMatchesCertificate(key, chain[0])) {
                findings.errors.add(where + ": private key does not match the certificate (" + chain[0].getSubjectX500Principal() + ")");
                continue;
            }
            for (int i = 0; i + 1 < chain.length; i++) {
                try {
                    chain[i].verify(chain[i + 1].getPublicKey());
                } catch (Exception e) {
                    findings.errors.add(where + ": chain certificate " + (i + 1) + " is not signed by chain certificate " + (i + 2));
                }
            }
            checkValidity(where, chain[0], failOnExpired, warningDays, findings);
            checkKeySize(where, chain[0].getPublicKey(), findings);
            recordFingerprint(where, chain[0], fingerprints, findings);
            try {
                ks.setKeyEntry(alias, key, password, chain);
            } catch (Exception e) {
                findings.errors.add(where + ": cannot be added to the keystore: " + e.getMessage());
            }
        }

        for (Map.Entry<String, String> row : snapshot.partnerCerts.entrySet()) {
            String alias = row.getKey();
            String where = "partner certificate \"" + alias + "\"";
            if (!checkAlias(alias, "as2_partner_certificate", aliasOwner, findings)) {
                continue;
            }
            String pem = row.getValue();
            if (pem != null && pem.contains("PRIVATE KEY")) {
                findings.errors.add(where + ": contains private key material; partner rows must hold the public certificate only");
                continue;
            }
            X509Certificate cert;
            try {
                cert = parseCertificates(pem)[0];
            } catch (Exception e) {
                findings.errors.add(where + ": certificate cannot be parsed: " + e.getMessage());
                continue;
            }
            checkValidity(where, cert, failOnExpired, warningDays, findings);
            checkKeySize(where, cert.getPublicKey(), findings);
            recordFingerprint(where, cert, fingerprints, findings);
            try {
                ks.setCertificateEntry(alias, cert);
            } catch (Exception e) {
                findings.errors.add(where + ": cannot be added to the keystore: " + e.getMessage());
            }
        }

        if (isValidatePartnerAliases()) {
            validatePartnerAliases(snapshot, aliasOwner, findings);
        }
        return ks;
    }

    private boolean checkAlias(String alias, String table, Map<String, String> aliasOwner, Findings findings) {
        if (alias == null || alias.trim().isEmpty()) {
            findings.errors.add(table + " has a row with an empty alias");
            return false;
        }
        String previous = aliasOwner.put(alias.toLowerCase(Locale.ROOT), table + ":" + alias);
        if (previous != null) {
            findings.errors.add("alias \"" + alias + "\" (" + table + ") clashes with " + previous
                    + "; aliases must be unique across as2_own_key and as2_partner_certificate (case-insensitive)");
            return false;
        }
        return true;
    }

    private void validatePartnerAliases(Snapshot snapshot, Map<String, String> aliasOwner, Findings findings) {
        java.util.Set<String> referenced = new java.util.HashSet<String>();
        for (Map.Entry<String, String> partner : snapshot.partnerAliases.entrySet()) {
            String alias = partner.getValue();
            if (alias == null || alias.trim().isEmpty()) {
                findings.errors.add("partner \"" + partner.getKey() + "\" has no x509_alias attribute");
                continue;
            }
            referenced.add(alias.toLowerCase(Locale.ROOT));
            if (!aliasOwner.containsKey(alias.toLowerCase(Locale.ROOT))) {
                findings.errors.add("partner \"" + partner.getKey() + "\" uses x509_alias \"" + alias
                        + "\" but there is no enabled row for it in as2_own_key or as2_partner_certificate");
            }
        }
        for (String[] ps : snapshot.partnershipAliases) {
            String name = ps[0];
            boolean senderOwn = isOwnKey(ps[1], aliasOwner);
            boolean receiverOwn = isOwnKey(ps[2], aliasOwner);
            for (int i = 1; i <= 2; i++) {
                String alias = ps[i];
                String side = i == 1 ? "sender" : "receiver";
                if (alias == null || alias.trim().isEmpty()) {
                    findings.errors.add("partnership \"" + name + "\": " + side + " has no x509_alias");
                    continue;
                }
                referenced.add(alias.toLowerCase(Locale.ROOT));
                if (!aliasOwner.containsKey(alias.toLowerCase(Locale.ROOT))) {
                    findings.errors.add("partnership \"" + name + "\": " + side + " x509_alias \"" + alias
                            + "\" has no enabled row in as2_own_key or as2_partner_certificate");
                }
            }
            if (ps[1] != null && ps[2] != null && aliasOwner.containsKey(ps[1].toLowerCase(Locale.ROOT))
                    && aliasOwner.containsKey(ps[2].toLowerCase(Locale.ROOT)) && !senderOwn && !receiverOwn) {
                findings.errors.add("partnership \"" + name + "\": neither the sender (\"" + ps[1] + "\") nor the receiver (\""
                        + ps[2] + "\") has a private key in as2_own_key, so this server can neither send nor receive it");
            }
        }
        for (String[] fallback : snapshot.fallbackAliases) {
            String alias = fallback[1];
            if (alias == null || alias.trim().isEmpty()) {
                continue;
            }
            referenced.add(alias.toLowerCase(Locale.ROOT));
            if (!aliasOwner.containsKey(alias.toLowerCase(Locale.ROOT))) {
                findings.errors.add("\"" + fallback[0] + "\" uses x509_alias_fallback \"" + alias
                        + "\" but there is no enabled row for it in as2_own_key or as2_partner_certificate");
            }
        }
        for (String owner : aliasOwner.values()) {
            String alias = owner.substring(owner.indexOf(':') + 1);
            if (!referenced.contains(alias.toLowerCase(Locale.ROOT))) {
                findings.warnings.add(owner + " is not referenced by any partner or partnership");
            }
        }
    }

    private static boolean isOwnKey(String alias, Map<String, String> aliasOwner) {
        if (alias == null) {
            return false;
        }
        String owner = aliasOwner.get(alias.toLowerCase(Locale.ROOT));
        return owner != null && owner.startsWith("as2_own_key:");
    }

    /** Re-reads every entry from the built keystore and checks it behaves as the AS2 code will use it. */
    private void verifyKeyStore(KeyStore ks, Snapshot snapshot, Findings findings) throws OpenAS2Exception {
        char[] password = getPassword();
        try {
            for (String alias : snapshot.ownKeys.keySet()) {
                Key key = ks.getKey(alias, password);
                Certificate[] chain = ks.getCertificateChain(alias);
                if (!(key instanceof PrivateKey) || chain == null || chain.length == 0 || !(chain[0] instanceof X509Certificate)) {
                    findings.errors.add("own key \"" + alias + "\": keystore entry is incomplete after loading");
                } else if (!keyMatchesCertificate((PrivateKey) key, (X509Certificate) chain[0])) {
                    findings.errors.add("own key \"" + alias + "\": keystore entry failed the sign/verify check");
                }
            }
            for (String alias : snapshot.partnerCerts.keySet()) {
                if (ks.isKeyEntry(alias) || !(ks.getCertificate(alias) instanceof X509Certificate)) {
                    findings.errors.add("partner certificate \"" + alias + "\": keystore entry is missing or not a certificate entry");
                }
            }
            int expected = snapshot.ownKeys.size() + snapshot.partnerCerts.size();
            if (ks.size() != expected) {
                findings.errors.add("keystore holds " + ks.size() + " entries, expected " + expected);
            }
        } catch (Exception e) {
            findings.errors.add("keystore verification failed: " + e.getMessage());
        }
    }

    // ---- Crypto helpers ----

    private PrivateKey decryptPrivateKey(String pem, char[] keyPassword, boolean allowUnencrypted) throws Exception {
        if (pem == null || pem.trim().isEmpty()) {
            throw new OpenAS2Exception("empty private key");
        }
        Object parsed;
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            parsed = parser.readObject();
        }
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
        if (parsed instanceof PKCS8EncryptedPrivateKeyInfo) {
            if (keyPassword == null) {
                throw new OpenAS2Exception("the key is encrypted but no private_key_password is configured");
            }
            InputDecryptorProvider decryptor = new JceOpenSSLPKCS8DecryptorProviderBuilder().setProvider("BC").build(keyPassword);
            try {
                return converter.getPrivateKey(((PKCS8EncryptedPrivateKeyInfo) parsed).decryptPrivateKeyInfo(decryptor));
            } catch (Exception e) {
                throw new OpenAS2Exception("decryption failed (wrong private_key_password or corrupt key)");
            }
        }
        if (parsed instanceof PrivateKeyInfo) {
            if (!allowUnencrypted) {
                throw new OpenAS2Exception("the private key is not encrypted; store it as an encrypted PKCS#8 PEM (BEGIN ENCRYPTED PRIVATE KEY)");
            }
            return converter.getPrivateKey((PrivateKeyInfo) parsed);
        }
        throw new OpenAS2Exception("expected an encrypted PKCS#8 PEM (BEGIN ENCRYPTED PRIVATE KEY), found "
                + (parsed == null ? "nothing" : parsed.getClass().getSimpleName()));
    }

    private static X509Certificate[] parseCertificates(String pem) throws Exception {
        if (pem == null || pem.trim().isEmpty()) {
            throw new OpenAS2Exception("empty certificate");
        }
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        Collection<? extends Certificate> certs;
        try (InputStream in = new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.US_ASCII))) {
            certs = cf.generateCertificates(in);
        }
        if (certs.isEmpty()) {
            throw new OpenAS2Exception("no X.509 certificate found");
        }
        List<X509Certificate> list = new ArrayList<X509Certificate>();
        for (Certificate c : certs) {
            if (!(c instanceof X509Certificate)) {
                throw new OpenAS2Exception("not an X.509 certificate");
            }
            list.add((X509Certificate) c);
        }
        return list.toArray(new X509Certificate[0]);
    }

    /** True if data signed with the private key verifies with the certificate's public key. */
    static boolean keyMatchesCertificate(PrivateKey key, X509Certificate cert) {
        String algorithm;
        String keyAlg = key.getAlgorithm();
        if ("RSA".equalsIgnoreCase(keyAlg)) {
            algorithm = "SHA256withRSA";
        } else if ("EC".equalsIgnoreCase(keyAlg) || "ECDSA".equalsIgnoreCase(keyAlg)) {
            algorithm = "SHA256withECDSA";
        } else if ("DSA".equalsIgnoreCase(keyAlg)) {
            algorithm = "SHA256withDSA";
        } else {
            return false;
        }
        try {
            byte[] data = new byte[32];
            new SecureRandom().nextBytes(data);
            Signature signer = Signature.getInstance(algorithm);
            signer.initSign(key);
            signer.update(data);
            byte[] sig = signer.sign();
            Signature verifier = Signature.getInstance(algorithm);
            verifier.initVerify(cert.getPublicKey());
            verifier.update(data);
            return verifier.verify(sig);
        } catch (Exception e) {
            return false;
        }
    }

    private static void checkValidity(String where, X509Certificate cert, boolean failOnExpired, int warningDays, Findings findings) {
        try {
            cert.checkValidity();
            long daysLeft = (cert.getNotAfter().getTime() - System.currentTimeMillis()) / (24L * 3600 * 1000);
            if (warningDays > 0 && daysLeft < warningDays) {
                findings.warnings.add(where + ": certificate expires in " + daysLeft + " day(s) on " + cert.getNotAfter());
            }
        } catch (CertificateExpiredException e) {
            (failOnExpired ? findings.errors : findings.warnings).add(where + ": certificate expired on " + cert.getNotAfter());
        } catch (CertificateNotYetValidException e) {
            (failOnExpired ? findings.errors : findings.warnings).add(where + ": certificate is not valid before " + cert.getNotBefore());
        }
    }

    private static void checkKeySize(String where, PublicKey publicKey, Findings findings) {
        if (publicKey instanceof RSAKey && ((RSAKey) publicKey).getModulus().bitLength() < 2048) {
            findings.warnings.add(where + ": RSA key is only " + ((RSAKey) publicKey).getModulus().bitLength() + " bits");
        }
    }

    private static void recordFingerprint(String where, X509Certificate cert, Map<String, String> fingerprints, Findings findings) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(cert.getEncoded());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            String previous = fingerprints.put(sb.toString(), where);
            if (previous != null) {
                findings.warnings.add(where + " has the same certificate as " + previous);
            }
        } catch (Exception e) {
            findings.warnings.add(where + ": cannot compute fingerprint: " + e.getMessage());
        }
    }

    // ---- Parameters and database ----

    private char[] getPrivateKeyPassword() throws InvalidParameterException {
        String pwd = getParameter(PARAM_PRIVATE_KEY_PASSWORD, false);
        return (pwd == null || pwd.isEmpty()) ? null : pwd.toCharArray();
    }

    private boolean isValidatePartnerAliases() throws InvalidParameterException {
        return !"false".equalsIgnoreCase(getParameter(PARAM_VALIDATE_PARTNER_ALIASES, "true"));
    }

    private synchronized void ensureDbHandler() throws OpenAS2Exception {
        if (dbHandler != null) {
            return;
        }
        CompositeParameters paramParser = new CompositeParameters(true);
        paramParser.add("component", new ComponentParameters(this));
        String jdbcConnectString = ParameterParser.parse(getParameter(PARAM_JDBC_CONNECT_STRING, true), paramParser);
        boolean useEmbeddedDB = "true".equals(getParameter(PARAM_USE_EMBEDDED_DB, "false"));
        if (!useEmbeddedDB && "true".equals(getParameter(PARAM_FORCE_LOAD_JDBC_DRIVER, "false"))) {
            String jdbcDriver = getParameter(PARAM_JDBC_DRIVER, true);
            try {
                Class.forName(jdbcDriver);
            } catch (ClassNotFoundException e) {
                throw new OpenAS2Exception("Failed to load JDBC driver for certificate factory: " + jdbcDriver, e);
            }
        }
        IDBHandler handler = useEmbeddedDB ? new EmbeddedDBHandler() : new ExternalDBHandler();
        handler.start(jdbcConnectString, getParameter(PARAM_DB_USER, true), getParameter(PARAM_DB_PWD, true), getParameters());
        dbHandler = handler;
    }

    private String getVersionQuery() throws InvalidParameterException {
        String query = getParameter(PARAM_VERSION_QUERY, false);
        return (query == null || query.trim().isEmpty()) ? null : query;
    }

    private String readVersion(Connection conn, String versionQuery) throws Exception {
        try (PreparedStatement s = conn.prepareStatement(versionQuery);
             ResultSet rs = s.executeQuery()) {
            if (!rs.next()) {
                throw new OpenAS2Exception("The certificate version_query returned no rows: " + versionQuery);
            }
            return rs.getString(1);
        }
    }

    // ---- Scheduled refresh ----

    /**
     * Rebuilds the keystore if the database version changed or a full reload is due.
     *
     * @return true if the keystore was rebuilt
     * @throws OpenAS2Exception if the check or the reload failed; the current keystore stays in use
     */
    public boolean refreshIfChanged() throws OpenAS2Exception {
        String versionQuery = getVersionQuery();
        if (versionQuery == null) {
            load();
            return true;
        }
        int fullRefreshInterval = getParameterInt(PARAM_FULL_REFRESH_INTERVAL, false, DEFAULT_FULL_REFRESH_INTERVAL);
        boolean fullRefreshDue = fullRefreshInterval > 0
                && System.currentTimeMillis() - lastFullLoadMillis >= fullRefreshInterval * 1000L;
        if (!fullRefreshDue) {
            ensureDbHandler();
            String version;
            try (Connection conn = dbHandler.getConnection()) {
                version = readVersion(conn, versionQuery);
            } catch (OpenAS2Exception e) {
                throw e;
            } catch (Exception e) {
                throw new WrappedException(e);
            }
            if (version != null && version.equals(loadedVersion)) {
                return false;
            }
            logger.info("Certificate version changed from " + loadedVersion + " to " + version + ", reloading keystore");
        }
        load();
        return true;
    }

    @Override
    public void schedule(ScheduledExecutorService executor) throws OpenAS2Exception {
        int interval = getParameterInt(PARAM_INTERVAL, false);
        if (interval <= 0) {
            return;
        }
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshIfChanged();
                    if (consecutiveFailures > 0) {
                        logger.info("Certificate refresh recovered after " + consecutiveFailures + " failed attempt(s)");
                        consecutiveFailures = 0;
                    }
                } catch (Exception e) {
                    consecutiveFailures++;
                    if (consecutiveFailures == 1) {
                        logger.error("Failed to refresh certificates, keeping the current keystore: " + e.getMessage());
                    } else {
                        logger.warn("Certificate refresh still failing (attempt " + consecutiveFailures + "): " + e.getMessage());
                    }
                }
            }
        }, interval, interval, TimeUnit.SECONDS);
    }

    @Override
    public void destroy() throws Exception {
        if (dbHandler != null) {
            dbHandler.stop();
            dbHandler = null;
        }
        super.destroy();
    }

    // ---- Read-only: everything that would modify the keystore is rejected ----

    @Override
    public void addCertificate(String alias, X509Certificate cert, boolean overwrite) throws OpenAS2Exception {
        throw new OpenAS2Exception(READ_ONLY_MSG);
    }

    @Override
    public void addPrivateKey(String alias, Key key, String password) throws OpenAS2Exception {
        throw new OpenAS2Exception(READ_ONLY_MSG);
    }

    @Override
    public boolean importPrivateKey(String alias, KeyStore ks, String password) throws OpenAS2Exception {
        throw new OpenAS2Exception(READ_ONLY_MSG);
    }

    @Override
    public void removeCertificate(X509Certificate cert) throws OpenAS2Exception {
        throw new OpenAS2Exception(READ_ONLY_MSG);
    }

    @Override
    public void removeCertificate(String alias) throws OpenAS2Exception {
        throw new OpenAS2Exception(READ_ONLY_MSG);
    }

    @Override
    public void clearCertificates() throws OpenAS2Exception {
        throw new OpenAS2Exception(READ_ONLY_MSG);
    }

    @Override
    public void save() throws OpenAS2Exception {
        throw new OpenAS2Exception(READ_ONLY_MSG);
    }

    @Override
    public void save(String filename, char[] password) throws OpenAS2Exception {
        throw new OpenAS2Exception(READ_ONLY_MSG);
    }

    @Override
    public void save(OutputStream out, char[] password) throws OpenAS2Exception {
        throw new OpenAS2Exception(READ_ONLY_MSG);
    }

    @Override
    public void load(String filename, char[] password) throws OpenAS2Exception {
        load();
    }

    @Override
    public void load(InputStream in, char[] password) throws OpenAS2Exception {
        throw new OpenAS2Exception(READ_ONLY_MSG);
    }

}

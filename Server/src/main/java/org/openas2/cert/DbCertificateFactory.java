package org.openas2.cert;

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
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Collection;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A read-only certificate factory that assembles the AS2 keystore in memory from two sources:
 * <ul>
 * <li>a PKCS12 file holding our own key pairs ("own_keys_file"), normally mounted as a secret, and</li>
 * <li>partner public certificates stored as PEM in a database table (any JDBC database, using the same
 * connection parameters as the DbTrackingModule and DbPartnershipFactory).</li>
 * </ul>
 * No private keys are read from the database.
 * <p>
 * Every reload builds a complete new keystore and only then publishes it, so lookups never see a partially
 * loaded keystore and a failed reload leaves the current keystore in use. A database row whose certificate
 * cannot be parsed is skipped; if the alias is already loaded the currently loaded certificate is kept so a bad
 * update does not take a working partner offline. A database alias that clashes with an own key alias is ignored.
 * <p>
 * Change detection: when "version_query" is set, each scheduled tick (every "interval" seconds) only runs that
 * query and the keystore is rebuilt when the value changes, when the own keys file changes, or every
 * "full_refresh_interval" seconds (default 3600, 0 disables). Without it every tick rebuilds the keystore.
 * <p>
 * Certificates are managed outside OpenAS2, so every method that would change the keystore throws.
 * <p>
 * Example:
 * <pre>
 * &lt;certificates classname="org.openas2.cert.DbCertificateFactory" identifier="as2_certs"
 *     own_keys_file="/opt/openas2/secrets/own_keys.p12" password="$properties.as2_keystore_password$"
 *     db_user="..." db_pwd="..." jdbc_connect_string="jdbc:postgresql://..." jdbc_driver="org.postgresql.Driver"
 *     force_load_jdbc_driver="true"
 *     version_query="SELECT version FROM config_version WHERE area = 'certificates'"
 *     interval="10"/&gt;
 * </pre>
 */
public class DbCertificateFactory extends X509CertificateFactory implements HasSchedule {

    public static final String PARAM_OWN_KEYS_FILE = "own_keys_file";
    public static final String PARAM_OWN_KEYS_PASSWORD = "own_keys_password";
    public static final String PARAM_DB_USER = "db_user";
    public static final String PARAM_DB_PWD = "db_pwd";
    public static final String PARAM_JDBC_CONNECT_STRING = "jdbc_connect_string";
    public static final String PARAM_JDBC_DRIVER = "jdbc_driver";
    public static final String PARAM_FORCE_LOAD_JDBC_DRIVER = "force_load_jdbc_driver";
    public static final String PARAM_USE_EMBEDDED_DB = "use_embedded_db";
    public static final String PARAM_CERT_QUERY = "cert_query";
    public static final String PARAM_VERSION_QUERY = "version_query";
    public static final String PARAM_FULL_REFRESH_INTERVAL = "full_refresh_interval";
    public static final int DEFAULT_FULL_REFRESH_INTERVAL = 3600;
    public static final String DEFAULT_CERT_QUERY =
            "SELECT ALIAS, CERT_PEM FROM as2_partner_certificate WHERE ENABLED = TRUE ORDER BY ALIAS";

    private static final String READ_ONLY_MSG =
            "Certificates are managed outside OpenAS2 (database and own keys file); the keystore is read-only";

    private final Logger logger = LoggerFactory.getLogger(DbCertificateFactory.class);

    private IDBHandler dbHandler = null;
    private volatile String loadedVersion = null;
    private volatile long lastFullLoadMillis = 0;
    private volatile long ownKeysLastModified = -1;
    private int consecutiveFailures = 0;

    @Override
    public String getFilename() throws InvalidParameterException {
        return getParameter(PARAM_OWN_KEYS_FILE, true);
    }

    /**
     * Builds a new keystore from the own keys file and the database and publishes it. On any failure the
     * current keystore stays in use.
     */
    @Override
    public synchronized void load() throws OpenAS2Exception {
        ensureDbHandler();
        String versionQuery = getVersionQuery();
        try {
            char[] password = getPassword();
            File ownKeysFile = new File(getFilename());
            long ownKeysModified = ownKeysFile.lastModified();

            KeyStore newKs = AS2Util.getCryptoHelper().getKeyStore();
            newKs.load(null, password);
            int ownKeys = copyOwnKeys(ownKeysFile, newKs, password);

            String version;
            int partnerCerts;
            try (Connection conn = dbHandler.getConnection()) {
                boolean autoCommit = conn.getAutoCommit();
                try {
                    conn.setAutoCommit(false);
                    version = versionQuery == null ? null : readVersion(conn, versionQuery);
                    partnerCerts = addPartnerCertificates(conn, newKs);
                    conn.commit();
                } finally {
                    try {
                        conn.setAutoCommit(autoCommit);
                    } catch (Exception e) {
                        logger.debug("Failed to restore auto commit: " + e.getMessage());
                    }
                }
            }

            // Publish the complete keystore in one step (the field is volatile)
            setKeyStore(newKs);
            loadedVersion = version;
            ownKeysLastModified = ownKeysModified;
            lastFullLoadMillis = System.currentTimeMillis();
            logger.info("Loaded " + ownKeys + " own key entries and " + partnerCerts + " partner certificates"
                    + (version != null ? " (version " + version + ")" : "") + " into keystore " + getIdentifier());
        } catch (OpenAS2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new WrappedException(e);
        }
    }

    private int copyOwnKeys(File ownKeysFile, KeyStore target, char[] password) throws Exception {
        if (!ownKeysFile.isFile()) {
            throw new OpenAS2Exception("Own keys file not found: " + ownKeysFile.getAbsolutePath());
        }
        String ownKeysPwd = getParameter(PARAM_OWN_KEYS_PASSWORD, false);
        char[] sourcePassword = ownKeysPwd == null ? password : ownKeysPwd.toCharArray();
        KeyStore source;
        try (InputStream in = new FileInputStream(ownKeysFile)) {
            source = AS2Util.getCryptoHelper().loadKeyStore(in, sourcePassword);
        }
        int count = 0;
        Enumeration<String> aliases = source.aliases();
        while (aliases.hasMoreElements()) {
            String alias = aliases.nextElement();
            if (source.isKeyEntry(alias)) {
                Key key = source.getKey(alias, sourcePassword);
                Certificate[] chain = source.getCertificateChain(alias);
                if (key == null || chain == null || chain.length == 0) {
                    throw new OpenAS2Exception("Own keys file entry has no usable key or certificate chain: " + alias);
                }
                // Stored with the factory password because that is what getPrivateKey() reads it back with
                target.setKeyEntry(alias, key, password, chain);
                warnIfNotCurrentlyValid(alias, chain[0]);
            } else {
                target.setCertificateEntry(alias, source.getCertificate(alias));
            }
            count++;
        }
        return count;
    }

    private int addPartnerCertificates(Connection conn, KeyStore target) throws Exception {
        KeyStore current = getKeyStore();
        String certQuery = getParameter(PARAM_CERT_QUERY, DEFAULT_CERT_QUERY);
        java.security.cert.CertificateFactory cf = java.security.cert.CertificateFactory.getInstance("X.509");
        Map<String, String> rows = new LinkedHashMap<String, String>();
        try (PreparedStatement s = conn.prepareStatement(certQuery);
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                rows.put(rs.getString(1), rs.getString(2));
            }
        }
        int count = 0;
        for (Map.Entry<String, String> row : rows.entrySet()) {
            String alias = row.getKey();
            if (alias == null || alias.trim().isEmpty()) {
                logger.error("Ignoring a partner certificate row with an empty alias");
                continue;
            }
            if (target.isKeyEntry(alias)) {
                logger.error("Ignoring partner certificate for alias \"" + alias + "\": the alias belongs to an own key entry");
                continue;
            }
            X509Certificate cert;
            try {
                cert = parseCertificate(cf, row.getValue());
            } catch (Exception e) {
                X509Certificate previous = currentCertificate(current, alias);
                if (previous != null) {
                    logger.error("Partner certificate for alias \"" + alias + "\" could not be parsed, keeping the currently loaded certificate: " + e.getMessage());
                    target.setCertificateEntry(alias, previous);
                    count++;
                } else {
                    logger.error("Partner certificate for alias \"" + alias + "\" could not be parsed and is skipped: " + e.getMessage());
                }
                continue;
            }
            warnIfNotCurrentlyValid(alias, cert);
            target.setCertificateEntry(alias, cert);
            count++;
        }
        return count;
    }

    private X509Certificate parseCertificate(java.security.cert.CertificateFactory cf, String pem) throws CertificateException, OpenAS2Exception {
        if (pem == null || pem.trim().isEmpty()) {
            throw new OpenAS2Exception("empty certificate");
        }
        Collection<? extends Certificate> certs = cf.generateCertificates(new ByteArrayInputStream(pem.trim().getBytes(StandardCharsets.US_ASCII)));
        if (certs.isEmpty() || !(certs.iterator().next() instanceof X509Certificate)) {
            throw new OpenAS2Exception("no X.509 certificate found");
        }
        // The first certificate is the partner certificate; any following ones are its issuers
        return (X509Certificate) certs.iterator().next();
    }

    private X509Certificate currentCertificate(KeyStore current, String alias) {
        if (current == null) {
            return null;
        }
        try {
            Certificate cert = current.getCertificate(alias);
            return cert instanceof X509Certificate ? (X509Certificate) cert : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void warnIfNotCurrentlyValid(String alias, Certificate cert) {
        if (!(cert instanceof X509Certificate)) {
            return;
        }
        try {
            ((X509Certificate) cert).checkValidity();
        } catch (CertificateExpiredException e) {
            logger.warn("Certificate for alias \"" + alias + "\" has expired: " + ((X509Certificate) cert).getNotAfter());
        } catch (CertificateNotYetValidException e) {
            logger.warn("Certificate for alias \"" + alias + "\" is not valid until " + ((X509Certificate) cert).getNotBefore());
        }
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

    /**
     * Rebuilds the keystore if the database version or the own keys file changed, or a full reload is due.
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
        boolean ownKeysChanged = new File(getFilename()).lastModified() != ownKeysLastModified;
        if (!fullRefreshDue && !ownKeysChanged) {
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
        } else if (ownKeysChanged) {
            logger.info("Own keys file changed, reloading keystore");
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
                        logger.error("Failed to refresh certificates, keeping the current keystore: " + e.getMessage(), e);
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

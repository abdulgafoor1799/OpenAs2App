package org.openas2.partner;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.openas2.BaseSession;
import org.openas2.OpenAS2Exception;
import org.openas2.Session;
import org.openas2.cert.CertificateFactory;
import org.openas2.cert.DbCertificateFactory;
import org.openas2.processor.receiver.DirectoryPollingModule;
import org.openas2.WrappedException;
import org.openas2.XMLSession;
import org.openas2.params.ComponentParameters;
import org.openas2.params.CompositeParameters;
import org.openas2.params.InvalidParameterException;
import org.openas2.params.ParameterParser;
import org.openas2.processor.msgtracking.EmbeddedDBHandler;
import org.openas2.processor.msgtracking.ExternalDBHandler;
import org.openas2.processor.msgtracking.IDBHandler;
import org.openas2.schedule.HasSchedule;
import org.openas2.util.AS2Util;
import org.openas2.util.XMLUtil;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A partnership factory that stores partners and partnerships in a database instead of
 * the partnerships XML file. Any database with a JDBC driver is supported (including the
 * embedded H2 database and external databases such as Azure SQL, PostgreSQL, MySQL and
 * Oracle) using the same connection parameters as the DbTrackingModule.
 * <p>
 * Partnerships can be managed directly in the database by external tooling or through
 * the partner/partnership console and REST API commands. The in-memory state is loaded
 * at startup, after every change made through this factory, on the "refresh" console
 * command and (if the "interval" parameter is set) on a fixed schedule.
 * <p>
 * Change detection (optional): when the "version_query" parameter is set, each scheduled tick
 * only runs that query (it must return a single value, typically a counter bumped by a database
 * trigger whenever the partnership tables change) and the partnerships are reloaded only when
 * the value differs from the one read with the currently loaded state. A full reload is still
 * done every "full_refresh_interval" seconds (default 3600, 0 disables) as a safety net.
 * <p>
 * Reloads are fail-safe: the database is read completely before anything is changed, so a
 * database error during a refresh leaves the current partnerships and their directory pollers
 * running. If the new poller configuration cannot be applied the previous one is restored.
 * <p>
 * Table structure (see Server/src/resources/db/openas2-schema.xml and db_ddl.sql):
 * <ul>
 * <li>partner - one row per partner with a unique name</li>
 * <li>partner_attribute - name/value pairs per partner (as2_id, x509_alias, email ...)</li>
 * <li>partnership - one row per partnership linking a sender and receiver partner</li>
 * <li>partnership_attribute - name/value pairs per partnership. The category column is
 * "attribute" for regular partnership attributes or "sender"/"receiver" for attribute
 * overrides applied on top of the linked partner's attributes for this partnership only</li>
 * </ul>
 */
public class DbPartnershipFactory extends BasePartnershipFactory implements RefreshablePartnershipFactory, HasSchedule {

    public static final String PARAM_DB_USER = "db_user";
    public static final String PARAM_DB_PWD = "db_pwd";
    public static final String PARAM_JDBC_CONNECT_STRING = "jdbc_connect_string";
    public static final String PARAM_JDBC_DRIVER = "jdbc_driver";
    public static final String PARAM_FORCE_LOAD_JDBC_DRIVER = "force_load_jdbc_driver";
    public static final String PARAM_USE_EMBEDDED_DB = "use_embedded_db";
    public static final String PARAM_INTERVAL = "interval";
    public static final String PARAM_VERSION_QUERY = "version_query";
    public static final String PARAM_FULL_REFRESH_INTERVAL = "full_refresh_interval";
    public static final int DEFAULT_FULL_REFRESH_INTERVAL = 3600;

    public static final String CATEGORY_ATTRIBUTE = "attribute";
    public static final String CATEGORY_SENDER = Partnership.PTYPE_SENDER;
    public static final String CATEGORY_RECEIVER = Partnership.PTYPE_RECEIVER;
    public static final String CATEGORY_POLLER_CONFIG = Partnership.PCFG_POLLER;

    private IDBHandler dbHandler = null;

    private Map<String, Object> partners = new HashMap<String, Object>();

    // Set once init() completes so refreshes triggered at runtime restart the partnership
    // pollers (at startup Session.start() does that after the whole system is wired up)
    private boolean initialized = false;

    // The state currently applied, kept so a failed apply can restore the previous pollers
    private LoadedState currentState = null;
    // Rendered poller configuration per partnership name for the pollers currently loaded
    private Map<String, Element> currentPollers = null;
    // Version value read in the same transaction as the currently applied state (null if not tracked)
    private volatile String loadedVersion = null;
    private volatile long lastFullLoadMillis = 0;
    // Consecutive scheduled refresh failures, used to avoid an ERROR (and alert email) on every tick
    private int consecutiveFailures = 0;

    /**
     * Everything read from the database for one refresh. Building it has no side effects on the
     * session, so a failure while reading leaves the running configuration untouched.
     */
    private static final class LoadedState {
        final Map<String, Object> partners;
        final List<Partnership> partnerships;
        final List<Map.Entry<Partnership, Map<String, String>>> pollerConfigs;
        final String version;

        LoadedState(Map<String, Object> partners, List<Partnership> partnerships,
                    List<Map.Entry<Partnership, Map<String, String>>> pollerConfigs, String version) {
            this.partners = partners;
            this.partnerships = partnerships;
            this.pollerConfigs = pollerConfigs;
            this.version = version;
        }
    }

    private Logger logger = LoggerFactory.getLogger(DbPartnershipFactory.class);

    public Map<String, Object> getPartners() {
        return partners;
    }

    private int getRefreshInterval() throws InvalidParameterException {
        return getParameterInt(PARAM_INTERVAL, false);
    }

    public void init(Session session, Map<String, String> parameters) throws OpenAS2Exception {
        super.init(session, parameters);

        CompositeParameters paramParser = new CompositeParameters(true);
        paramParser.add("component", new ComponentParameters(this));
        // Support component attributes in connect string
        String jdbcConnectString = ParameterParser.parse(getParameter(PARAM_JDBC_CONNECT_STRING, true), paramParser);

        boolean useEmbeddedDB = "true".equals(getParameter(PARAM_USE_EMBEDDED_DB, "false"));
        if (!useEmbeddedDB && "true".equals(getParameter(PARAM_FORCE_LOAD_JDBC_DRIVER, "false"))) {
            String jdbcDriver = getParameter(PARAM_JDBC_DRIVER, true);
            try {
                Class.forName(jdbcDriver);
            } catch (ClassNotFoundException e) {
                throw new OpenAS2Exception("Failed to load JDBC driver for partnership factory: " + jdbcDriver, e);
            }
        }
        if (useEmbeddedDB) {
            dbHandler = new EmbeddedDBHandler();
        } else {
            dbHandler = new ExternalDBHandler();
        }
        dbHandler.start(jdbcConnectString, getParameter(PARAM_DB_USER, true), getParameter(PARAM_DB_PWD, true), getParameters());

        refresh();
        initialized = true;
    }

    /**
     * Reloads the partners and partnerships from the database. The database is read completely
     * first; the directory pollers are only rebuilt once the new state has been read successfully.
     *
     * @throws OpenAS2Exception if the database cannot be read or the new state cannot be applied;
     *                          the previously loaded state stays active in both cases
     */
    public synchronized void refresh() throws OpenAS2Exception {
        LoadedState state = readState();
        applyState(state);
        if (logger.isInfoEnabled()) {
            logger.info("Loaded " + state.partners.size() + " partners and " + state.partnerships.size()
                    + " partnerships from database" + (state.version != null ? " (version " + state.version + ")" : ""));
        }
    }

    private LoadedState readState() throws OpenAS2Exception {
        String versionQuery = getVersionQuery();
        try (Connection conn = dbHandler.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            int isolation = conn.getTransactionIsolation();
            try {
                // Read the version and all tables in one snapshot so the version matches the data
                conn.setAutoCommit(false);
                try {
                    conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                } catch (SQLException e) {
                    logger.debug("Repeatable read isolation not supported, using the default: " + e.getMessage());
                }
                String version = versionQuery == null ? null : readVersion(conn, versionQuery);
                Map<Long, Map<String, String>> partnersById = loadPartners(conn);
                Map<String, Object> newPartners = new HashMap<String, Object>();
                for (Map<String, String> partner : partnersById.values()) {
                    String name = partner.get(Partnership.PID_NAME);
                    if (newPartners.put(name, partner) != null) {
                        throw new OpenAS2Exception("Partner is defined more than once: " + name);
                    }
                }
                List<Map.Entry<Partnership, Map<String, String>>> pollerConfigs = new ArrayList<Map.Entry<Partnership, Map<String, String>>>();
                List<Partnership> newPartnerships = loadPartnerships(conn, partnersById, pollerConfigs);
                conn.commit();
                return new LoadedState(newPartners, newPartnerships, pollerConfigs, version);
            } catch (Exception e) {
                try {
                    conn.rollback();
                } catch (SQLException re) {
                    logger.debug("Rollback after failed partnership read failed: " + re.getMessage());
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

    /**
     * Applies a state read from the database. Poller configurations are rendered first (no side effects), then only
     * the pollers whose rendered configuration changed are stopped and started; unchanged pollers keep running and
     * inbound processing is never paused. If loading a new poller fails the previous pollers are restored.
     */
    private void applyState(LoadedState state) throws OpenAS2Exception {
        Session session = getSession();
        Map<String, Element> newPollers = renderPollers(state);
        if (session == null) {
            publish(state, newPollers);
            return;
        }
        if (!initialized || currentPollers == null) {
            // Startup: Session.start() starts the pollers once everything is wired up
            session.destroyPartnershipPollers(Session.PARTNERSHIP_POLLER);
            for (Map.Entry<String, Element> poller : newPollers.entrySet()) {
                session.loadPartnershipPoller(poller.getValue(), poller.getKey(), Session.PARTNERSHIP_POLLER);
            }
            publish(state, newPollers);
            return;
        }
        Set<String> toStop = new HashSet<String>();
        Set<String> toStart = new HashSet<String>();
        for (Map.Entry<String, Element> old : currentPollers.entrySet()) {
            Element replacement = newPollers.get(old.getKey());
            if (replacement == null || !signature(replacement).equals(signature(old.getValue()))) {
                toStop.add(old.getKey());
            }
        }
        for (Map.Entry<String, Element> poller : newPollers.entrySet()) {
            Element previous = currentPollers.get(poller.getKey());
            if (previous == null || !signature(previous).equals(signature(poller.getValue()))) {
                toStart.add(poller.getKey());
            }
        }
        // Stop first so a poller moving between partnerships never has two owners of one outbox directory
        for (String name : toStop) {
            session.destroyPartnershipPoller(name);
        }
        try {
            for (String name : toStart) {
                session.loadPartnershipPoller(newPollers.get(name), name, Session.PARTNERSHIP_POLLER);
            }
        } catch (OpenAS2Exception e) {
            logger.error("Failed to apply the partnership poller configuration read from the database, restoring the previous pollers: " + e.getMessage(), e);
            for (String name : toStart) {
                session.destroyPartnershipPoller(name);
            }
            for (String name : toStop) {
                try {
                    session.loadPartnershipPoller(currentPollers.get(name), name, Session.PARTNERSHIP_POLLER);
                } catch (OpenAS2Exception re) {
                    logger.error("Failed to restore the previous poller for partnership " + name + ": " + re.getMessage(), re);
                }
            }
            startStoppedPollers(session);
            throw e;
        }
        publish(state, newPollers);
        startStoppedPollers(session);
        if (logger.isInfoEnabled()) {
            logger.info("Partnership pollers: " + (newPollers.size() - toStart.size()) + " kept running, "
                    + toStart.size() + " (re)started, " + (toStop.size() - countIn(toStop, toStart)) + " removed");
        }
    }

    private static int countIn(Set<String> names, Set<String> other) {
        int n = 0;
        for (String name : names) {
            if (other.contains(name)) {
                n++;
            }
        }
        return n;
    }

    private void publish(LoadedState state, Map<String, Element> pollers) {
        synchronized (this) {
            this.partners = state.partners;
            setPartnerships(state.partnerships);
            this.currentState = state;
            this.currentPollers = pollers;
            this.loadedVersion = state.version;
            this.lastFullLoadMillis = System.currentTimeMillis();
        }
    }

    /** Starts the partnership pollers that are loaded but not running, leaving running ones untouched. */
    private void startStoppedPollers(Session session) throws OpenAS2Exception {
        if (!(session instanceof BaseSession)) {
            session.startPartnershipPollers();
            return;
        }
        for (Map<String, Object> meta : ((BaseSession) session).getPolledDirectories().values()) {
            Object poller = meta.get("pollerInstance");
            if (Session.PARTNERSHIP_POLLER.equals(meta.get("configSource")) && poller instanceof DirectoryPollingModule
                    && !((DirectoryPollingModule) poller).isRunning()) {
                ((DirectoryPollingModule) poller).start();
            }
        }
    }

    private Map<String, Element> renderPollers(LoadedState state) throws OpenAS2Exception {
        Map<String, Element> pollers = new LinkedHashMap<String, Element>();
        for (Map.Entry<Partnership, Map<String, String>> entry : state.pollerConfigs) {
            Element element = renderPoller(entry.getKey(), entry.getValue());
            if (element != null) {
                pollers.put(entry.getKey().getName(), element);
            }
        }
        return pollers;
    }

    /** A stable string of the rendered poller attributes, used to detect configuration changes. */
    private static String signature(Element element) {
        return new TreeMap<String, String>(XMLUtil.mapAttributes(element)).toString();
    }

    private String getVersionQuery() throws InvalidParameterException {
        String query = getParameter(PARAM_VERSION_QUERY, false);
        return (query == null || query.trim().isEmpty()) ? null : query;
    }

    private String readVersion(Connection conn, String versionQuery) throws Exception {
        try (PreparedStatement s = conn.prepareStatement(versionQuery);
             ResultSet rs = s.executeQuery()) {
            if (!rs.next()) {
                throw new OpenAS2Exception("The partnership version_query returned no rows: " + versionQuery);
            }
            return rs.getString(1);
        }
    }

    private String readVersion(String versionQuery) throws OpenAS2Exception {
        try (Connection conn = dbHandler.getConnection()) {
            return readVersion(conn, versionQuery);
        } catch (OpenAS2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new WrappedException(e);
        }
    }

    /**
     * Scheduled check: without a version query this is a plain refresh. With one, only the version
     * is read and the partnerships are reloaded when it changed or a full refresh is due.
     *
     * @return true if the partnerships were reloaded
     * @throws OpenAS2Exception if the version or the partnerships could not be read
     */
    public boolean refreshIfChanged() throws OpenAS2Exception {
        String versionQuery = getVersionQuery();
        if (versionQuery == null) {
            refresh();
            return true;
        }
        int fullRefreshInterval = getParameterInt(PARAM_FULL_REFRESH_INTERVAL, false, DEFAULT_FULL_REFRESH_INTERVAL);
        boolean fullRefreshDue = fullRefreshInterval > 0
                && System.currentTimeMillis() - lastFullLoadMillis >= fullRefreshInterval * 1000L;
        if (!fullRefreshDue) {
            String version = readVersion(versionQuery);
            if (version != null && version.equals(loadedVersion)) {
                return false;
            }
            logger.info("Partnership version changed from " + loadedVersion + " to " + version + ", reloading partnerships");
        }
        refreshCertificatesFirst();
        refresh();
        return true;
    }

    /**
     * A partner and its certificate are usually written in one transaction. Checking the certificate store before
     * applying new partnerships makes the certificates available no later than the partnerships that use them.
     */
    private void refreshCertificatesFirst() {
        Session session = getSession();
        if (session == null) {
            return;
        }
        try {
            CertificateFactory cf = session.getCertificateFactory(CertificateFactory.COMPID_AS2_CERTIFICATE_FACTORY);
            if (cf instanceof DbCertificateFactory) {
                ((DbCertificateFactory) cf).refreshIfChanged();
            }
        } catch (Exception e) {
            logger.warn("Certificate refresh before applying partnership changes failed, continuing with the current keystore: " + e.getMessage());
        }
    }

    // Keyed by partner ID so partnerships can resolve their sender/receiver rows
    private Map<Long, Map<String, String>> loadPartners(Connection conn) throws Exception {
        Map<Long, Map<String, String>> partnersById = new HashMap<Long, Map<String, String>>();
        try (PreparedStatement s = conn.prepareStatement("SELECT ID, NAME FROM partner");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                Map<String, String> partner = new HashMap<String, String>();
                partner.put(Partnership.PID_NAME, rs.getString(2));
                partnersById.put(rs.getLong(1), partner);
            }
        }
        try (PreparedStatement s = conn.prepareStatement("SELECT PARTNER_ID, ATTRIBUTE_NAME, ATTRIBUTE_VALUE FROM partner_attribute");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                Map<String, String> partner = partnersById.get(rs.getLong(1));
                if (partner == null) {
                    throw new OpenAS2Exception("Partner attribute references unknown partner ID: " + rs.getLong(1));
                }
                partner.put(rs.getString(2), rs.getString(3));
            }
        }
        return partnersById;
    }

    private List<Partnership> loadPartnerships(Connection conn, Map<Long, Map<String, String>> partnersById,
                                               List<Map.Entry<Partnership, Map<String, String>>> pollerConfigs) throws Exception {
        List<Partnership> newPartnerships = new ArrayList<Partnership>();
        Map<Long, Partnership> partnershipsById = new HashMap<Long, Partnership>();
        try (PreparedStatement s = conn.prepareStatement("SELECT ID, NAME, SENDER_PARTNER_ID, RECEIVER_PARTNER_ID FROM partnership");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                long id = rs.getLong(1);
                String name = rs.getString(2);
                if (getPartnership(newPartnerships, name) != null) {
                    throw new OpenAS2Exception("Partnership is defined more than once: " + name);
                }
                Partnership partnership = new Partnership();
                partnership.setName(name);
                Map<String, String> sender = partnersById.get(rs.getLong(3));
                if (sender == null) {
                    throw new OpenAS2Exception("Partnership \"" + name + "\" references an unknown sender partner ID: " + rs.getLong(3));
                }
                partnership.getSenderIDs().putAll(sender);
                Map<String, String> receiver = partnersById.get(rs.getLong(4));
                if (receiver == null) {
                    throw new OpenAS2Exception("Partnership \"" + name + "\" references an unknown receiver partner ID: " + rs.getLong(4));
                }
                partnership.getReceiverIDs().putAll(receiver);
                partnershipsById.put(id, partnership);
                newPartnerships.add(partnership);
            }
        }
        // Partnership attributes: plain attributes, per-partnership sender/receiver overrides
        // and partnership poller configurations
        Map<Long, Map<String, String>> attributesById = new HashMap<Long, Map<String, String>>();
        Map<Long, Map<String, String>> pollerConfigsById = new HashMap<Long, Map<String, String>>();
        try (PreparedStatement s = conn.prepareStatement("SELECT PARTNERSHIP_ID, CATEGORY, ATTRIBUTE_NAME, ATTRIBUTE_VALUE FROM partnership_attribute");
             ResultSet rs = s.executeQuery()) {
            while (rs.next()) {
                long partnershipId = rs.getLong(1);
                Partnership partnership = partnershipsById.get(partnershipId);
                if (partnership == null) {
                    throw new OpenAS2Exception("Partnership attribute references unknown partnership ID: " + partnershipId);
                }
                String category = rs.getString(2);
                String attrName = rs.getString(3);
                String attrValue = rs.getString(4);
                if (CATEGORY_SENDER.equalsIgnoreCase(category)) {
                    partnership.getSenderIDs().put(attrName, attrValue);
                } else if (CATEGORY_RECEIVER.equalsIgnoreCase(category)) {
                    partnership.getReceiverIDs().put(attrName, attrValue);
                } else if (CATEGORY_ATTRIBUTE.equalsIgnoreCase(category)) {
                    Map<String, String> attributes = attributesById.get(partnershipId);
                    if (attributes == null) {
                        attributes = new HashMap<String, String>();
                        attributesById.put(partnershipId, attributes);
                    }
                    attributes.put(attrName, attrValue);
                } else if (CATEGORY_POLLER_CONFIG.equalsIgnoreCase(category)) {
                    Map<String, String> pollerConfig = pollerConfigsById.get(partnershipId);
                    if (pollerConfig == null) {
                        pollerConfig = new HashMap<String, String>();
                        pollerConfigsById.put(partnershipId, pollerConfig);
                    }
                    pollerConfig.put(attrName, attrValue);
                } else {
                    throw new OpenAS2Exception("Unsupported partnership attribute category \"" + category + "\" for partnership: " + partnership.getName());
                }
            }
        }
        for (Map.Entry<Long, Map<String, String>> entry : attributesById.entrySet()) {
            Partnership partnership = partnershipsById.get(entry.getKey());
            Map<String, String> attributes = entry.getValue();
            AS2Util.attributeEnhancer(attributes);
            partnership.getAttributes().putAll(attributes);
        }
        for (Partnership partnership : newPartnerships) {
            if ("true".equalsIgnoreCase(partnership.getAttributeOrProperty(Partnership.PA_USE_DYNAMIC_CONTENT_TYPE_MAPPING, "false"))) {
                try {
                    partnership.setUseDynamicContentTypeLookup(true);
                } catch (IOException e) {
                    logger.error("Error setting up dynamic Content-Type lookup: " + e.getMessage(), e);
                    throw new OpenAS2Exception("Partnership failed to be set up correctly for dynamic Content-Type lookup: " + partnership.getName());
                }
            }
        }
        // Pollers are only collected here and launched once the whole state has been read
        for (Map.Entry<Long, Map<String, String>> entry : pollerConfigsById.entrySet()) {
            Partnership partnership = partnershipsById.get(entry.getKey());
            pollerConfigs.add(new AbstractMap.SimpleImmutableEntry<Partnership, Map<String, String>>(partnership, entry.getValue()));
            // Surface the poller config in the view/list command output the same way the
            // view command renders the pollerConfig element of the XML partnerships file
            entry.getValue().forEach((key, value) -> partnership.getAttributes().put("pollerConfig." + key, value));
        }
        return newPartnerships;
    }

    /**
     * Renders the directory poller configuration for a partnership by merging its "pollerConfig" category
     * attributes over the base poller config from config.xml, mirroring the pollerConfig
     * element support of the XML partnerships file.
     */
    private Element renderPoller(Partnership partnership, Map<String, String> pollerConfig) throws OpenAS2Exception {
        if (!"true".equalsIgnoreCase(pollerConfig.get("enabled"))) {
            return null;
        }
        if (isPartnershipPollingDisabled()) {
            /*
             * Partnership polling is globally disabled so no poller is created for this partnership.
             * The partnership itself remains fully usable for receiving messages.
             */
            if (logger.isDebugEnabled()) {
                logger.debug("Partnership polling is globally disabled so no poller was started for partnership: " + partnership.getName());
            }
            return null;
        }
        Session session = getSession();
        if (!(session instanceof XMLSession)) {
            if (session != null) {
                logger.warn("Cannot launch a partnership poller without an XML session for partnership: " + partnership.getName());
            }
            return null;
        }
        if (logger.isTraceEnabled()) {
            logger.trace("Found partnership poller for partnership: " + partnership.getName());
        }
        Node basePollerConfigNode = ((XMLSession) session).getBasePartnershipPollerConfig();
        if (basePollerConfigNode == null) {
            throw new OpenAS2Exception("Missing base poller config node in config.xml to configure partnership poller.");
        }
        Document pollerDoc;
        try {
            pollerDoc = XMLUtil.createDoc(basePollerConfigNode);
        } catch (Exception e) {
            throw new OpenAS2Exception("Failed to create a poller document: " + e.getMessage(), e);
        }
        Element pollerConfigElem = pollerDoc.getDocumentElement();
        // Merge the attributes from the base config with the partnership specific ones
        Map<String, String> attributes = XMLUtil.mapAttributes(pollerConfigElem);
        attributes.putAll(pollerConfig);
        // Enhance the attribute values in case they are using dynamic variables
        AS2Util.attributeEnhancer(attributes);
        attributes.forEach((key, value) -> {
            pollerConfigElem.setAttribute(key, value);
        });
        // replace the $partnership.* placeholders
        replacePartnershipPlaceHolders(pollerDoc, partnership);
        return pollerConfigElem;
    }

    /**
     * Adds a partner to the database and reloads the in-memory partnership state.
     *
     * @param attributes - partner attributes including the mandatory "name" attribute
     * @throws OpenAS2Exception if the partner has no name or already exists
     */
    public synchronized void addPartner(Map<String, String> attributes) throws OpenAS2Exception {
        String name = attributes.get(Partnership.PID_NAME);
        if (name == null || name.trim().length() == 0) {
            throw new OpenAS2Exception("Partner must have a name");
        }
        try (Connection conn = dbHandler.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (findId(conn, "SELECT ID FROM partner WHERE NAME = ?", name) != null) {
                    throw new OpenAS2Exception("Partner is defined more than once: " + name);
                }
                try (PreparedStatement s = conn.prepareStatement("INSERT INTO partner (NAME) VALUES (?)")) {
                    s.setString(1, name);
                    s.executeUpdate();
                }
                long partnerId = findId(conn, "SELECT ID FROM partner WHERE NAME = ?", name);
                try (PreparedStatement s = conn.prepareStatement("INSERT INTO partner_attribute (PARTNER_ID, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (?, ?, ?)")) {
                    for (Map.Entry<String, String> attribute : attributes.entrySet()) {
                        if (Partnership.PID_NAME.equals(attribute.getKey())) {
                            continue;
                        }
                        s.setLong(1, partnerId);
                        s.setString(2, attribute.getKey());
                        s.setString(3, attribute.getValue());
                        s.executeUpdate();
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (OpenAS2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new WrappedException(e);
        }
        refresh();
    }

    /**
     * Deletes a partner from the database and reloads the in-memory partnership state.
     *
     * @param name - the name of the partner to delete
     * @throws OpenAS2Exception if the partner does not exist or is referenced by a partnership
     */
    /**
     * Merges attributes into an existing partner. Attributes that are not supplied are left as they
     * are, so this is a partial update rather than a replacement.
     *
     * @param name - the name of the partner to update. Partners are not renamed by this method
     * @param attributes - the attributes to set, replacing the value of any that already exist
     * @throws OpenAS2Exception if the partner does not exist
     */
    public synchronized void updatePartner(String name, Map<String, String> attributes) throws OpenAS2Exception {
        try (Connection conn = dbHandler.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Long id = findId(conn, "SELECT ID FROM partner WHERE NAME = ?", name);
                if (id == null) {
                    throw new OpenAS2Exception("Unknown partner name: " + name);
                }
                for (Map.Entry<String, String> attribute : attributes.entrySet()) {
                    upsertPartnerAttribute(conn, id, attribute.getKey(), attribute.getValue());
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (OpenAS2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new WrappedException(e);
        }
        refresh();
    }

    /**
     * Merges attributes, poller configuration and partner references into an existing partnership.
     * Anything not supplied is left as it is, so this is a partial update rather than a replacement.
     *
     * @param name - the name of the partnership to update. Partnerships are not renamed by this method
     * @param attributes - partnership attributes to set, or null to leave them alone
     * @param pollerConfig - poller configuration attributes to set, or null to leave them alone
     * @param senderName - the partner to point the sender at, or null to leave it alone
     * @param receiverName - the partner to point the receiver at, or null to leave it alone
     * @throws OpenAS2Exception if the partnership, or a partner it is being pointed at, does not exist
     */
    public synchronized void updatePartnership(String name, Map<String, String> attributes, Map<String, String> pollerConfig, String senderName, String receiverName) throws OpenAS2Exception {
        try (Connection conn = dbHandler.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Long id = findId(conn, "SELECT ID FROM partnership WHERE NAME = ?", name);
                if (id == null) {
                    throw new OpenAS2Exception("Partnership not found: " + name);
                }
                if (senderName != null) {
                    repointPartnership(conn, id, name, "SENDER_PARTNER_ID", Partnership.PTYPE_SENDER, senderName);
                }
                if (receiverName != null) {
                    repointPartnership(conn, id, name, "RECEIVER_PARTNER_ID", Partnership.PTYPE_RECEIVER, receiverName);
                }
                if (attributes != null) {
                    for (Map.Entry<String, String> attribute : attributes.entrySet()) {
                        upsertPartnershipAttribute(conn, id, CATEGORY_ATTRIBUTE, attribute.getKey(), attribute.getValue());
                    }
                }
                if (pollerConfig != null) {
                    for (Map.Entry<String, String> attribute : pollerConfig.entrySet()) {
                        upsertPartnershipAttribute(conn, id, CATEGORY_POLLER_CONFIG, attribute.getKey(), attribute.getValue());
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (OpenAS2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new WrappedException(e);
        }
        refresh();
    }

    private void repointPartnership(Connection conn, long partnershipId, String partnershipName, String column, String partnerType, String partnerName) throws Exception {
        Long partnerId = findId(conn, "SELECT ID FROM partner WHERE NAME = ?", partnerName);
        if (partnerId == null) {
            throw new OpenAS2Exception("Partnership " + partnershipName + " has an undefined " + partnerType + ": " + partnerName);
        }
        try (PreparedStatement s = conn.prepareStatement("UPDATE partnership SET " + column + " = ? WHERE ID = ?")) {
            s.setLong(1, partnerId);
            s.setLong(2, partnershipId);
            s.executeUpdate();
        }
    }

    private void upsertPartnerAttribute(Connection conn, long partnerId, String attrName, String attrValue) throws Exception {
        try (PreparedStatement s = conn.prepareStatement(
                "UPDATE partner_attribute SET ATTRIBUTE_VALUE = ? WHERE PARTNER_ID = ? AND ATTRIBUTE_NAME = ?")) {
            s.setString(1, attrValue);
            s.setLong(2, partnerId);
            s.setString(3, attrName);
            if (s.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement s = conn.prepareStatement(
                "INSERT INTO partner_attribute (PARTNER_ID, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (?, ?, ?)")) {
            s.setLong(1, partnerId);
            s.setString(2, attrName);
            s.setString(3, attrValue);
            s.executeUpdate();
        }
    }

    private void upsertPartnershipAttribute(Connection conn, long partnershipId, String category, String attrName, String attrValue) throws Exception {
        try (PreparedStatement s = conn.prepareStatement(
                "UPDATE partnership_attribute SET ATTRIBUTE_VALUE = ? WHERE PARTNERSHIP_ID = ? AND CATEGORY = ? AND ATTRIBUTE_NAME = ?")) {
            s.setString(1, attrValue);
            s.setLong(2, partnershipId);
            s.setString(3, category);
            s.setString(4, attrName);
            if (s.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement s = conn.prepareStatement(
                "INSERT INTO partnership_attribute (PARTNERSHIP_ID, CATEGORY, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (?, ?, ?, ?)")) {
            s.setLong(1, partnershipId);
            s.setString(2, category);
            s.setString(3, attrName);
            s.setString(4, attrValue);
            s.executeUpdate();
        }
    }

    public synchronized void deletePartner(String name) throws OpenAS2Exception {
        try (Connection conn = dbHandler.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Long id = findId(conn, "SELECT ID FROM partner WHERE NAME = ?", name);
                if (id == null) {
                    throw new OpenAS2Exception("Unknown partner name: " + name);
                }
                try (PreparedStatement s = conn.prepareStatement("SELECT COUNT(*) FROM partnership WHERE SENDER_PARTNER_ID = ? OR RECEIVER_PARTNER_ID = ?")) {
                    s.setLong(1, id);
                    s.setLong(2, id);
                    try (ResultSet rs = s.executeQuery()) {
                        rs.next();
                        if (rs.getLong(1) > 0) {
                            throw new OpenAS2Exception("Cannot delete partner; it is tied to some partnerships");
                        }
                    }
                }
                executeUpdate(conn, "DELETE FROM partner_attribute WHERE PARTNER_ID = ?", id);
                executeUpdate(conn, "DELETE FROM partner WHERE ID = ?", id);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (OpenAS2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new WrappedException(e);
        }
        refresh();
    }

    /**
     * Adds a partnership between two existing partners to the database and reloads the
     * in-memory partnership state.
     *
     * @param name         - unique name of the partnership
     * @param senderName   - name of an existing partner acting as the sender
     * @param receiverName - name of an existing partner acting as the receiver
     * @param attributes   - partnership attributes (stored with the "attribute" category)
     * @throws OpenAS2Exception if the partnership already exists or either partner is unknown
     */
    public synchronized void addPartnership(String name, String senderName, String receiverName, Map<String, String> attributes) throws OpenAS2Exception {
        addPartnership(name, senderName, receiverName, attributes, null);
    }

    /**
     * Adds a partnership between two existing partners to the database and reloads the
     * in-memory partnership state.
     *
     * @param name         - unique name of the partnership
     * @param senderName   - name of an existing partner acting as the sender
     * @param receiverName - name of an existing partner acting as the receiver
     * @param attributes   - partnership attributes (stored with the "attribute" category)
     * @param pollerConfig - directory poller attributes for this partnership (stored with the
     *                       "pollerConfig" category), or null if the partnership has no poller
     * @throws OpenAS2Exception if the partnership already exists or either partner is unknown
     */
    public synchronized void addPartnership(String name, String senderName, String receiverName, Map<String, String> attributes, Map<String, String> pollerConfig) throws OpenAS2Exception {
        try (Connection conn = dbHandler.getConnection()) {
            conn.setAutoCommit(false);
            try {
                if (findId(conn, "SELECT ID FROM partnership WHERE NAME = ?", name) != null) {
                    throw new OpenAS2Exception("Partnership is defined more than once: " + name);
                }
                Long senderId = findId(conn, "SELECT ID FROM partner WHERE NAME = ?", senderName);
                if (senderId == null) {
                    throw new OpenAS2Exception("Partnership " + name + " has an undefined " + Partnership.PTYPE_SENDER + ": " + senderName);
                }
                Long receiverId = findId(conn, "SELECT ID FROM partner WHERE NAME = ?", receiverName);
                if (receiverId == null) {
                    throw new OpenAS2Exception("Partnership " + name + " has an undefined " + Partnership.PTYPE_RECEIVER + ": " + receiverName);
                }
                try (PreparedStatement s = conn.prepareStatement("INSERT INTO partnership (NAME, SENDER_PARTNER_ID, RECEIVER_PARTNER_ID) VALUES (?, ?, ?)")) {
                    s.setString(1, name);
                    s.setLong(2, senderId);
                    s.setLong(3, receiverId);
                    s.executeUpdate();
                }
                long partnershipId = findId(conn, "SELECT ID FROM partnership WHERE NAME = ?", name);
                try (PreparedStatement s = conn.prepareStatement("INSERT INTO partnership_attribute (PARTNERSHIP_ID, CATEGORY, ATTRIBUTE_NAME, ATTRIBUTE_VALUE) VALUES (?, ?, ?, ?)")) {
                    for (Map.Entry<String, String> attribute : attributes.entrySet()) {
                        s.setLong(1, partnershipId);
                        s.setString(2, CATEGORY_ATTRIBUTE);
                        s.setString(3, attribute.getKey());
                        s.setString(4, attribute.getValue());
                        s.executeUpdate();
                    }
                    if (pollerConfig != null) {
                        for (Map.Entry<String, String> attribute : pollerConfig.entrySet()) {
                            s.setLong(1, partnershipId);
                            s.setString(2, CATEGORY_POLLER_CONFIG);
                            s.setString(3, attribute.getKey());
                            s.setString(4, attribute.getValue());
                            s.executeUpdate();
                        }
                    }
                }
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (OpenAS2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new WrappedException(e);
        }
        refresh();
    }

    /**
     * Deletes a partnership from the database and reloads the in-memory partnership state.
     *
     * @param name - the name of the partnership to delete
     * @throws OpenAS2Exception if the partnership does not exist
     */
    public synchronized void deletePartnership(String name) throws OpenAS2Exception {
        try (Connection conn = dbHandler.getConnection()) {
            conn.setAutoCommit(false);
            try {
                Long id = findId(conn, "SELECT ID FROM partnership WHERE NAME = ?", name);
                if (id == null) {
                    throw new OpenAS2Exception("Partnership not found: " + name);
                }
                executeUpdate(conn, "DELETE FROM partnership_attribute WHERE PARTNERSHIP_ID = ?", id);
                executeUpdate(conn, "DELETE FROM partnership WHERE ID = ?", id);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            }
        } catch (OpenAS2Exception e) {
            throw e;
        } catch (Exception e) {
            throw new WrappedException(e);
        }
        refresh();
    }

    private Long findId(Connection conn, String sql, String name) throws Exception {
        try (PreparedStatement s = conn.prepareStatement(sql)) {
            s.setString(1, name);
            try (ResultSet rs = s.executeQuery()) {
                return rs.next() ? rs.getLong(1) : null;
            }
        }
    }

    private void executeUpdate(Connection conn, String sql, long id) throws Exception {
        try (PreparedStatement s = conn.prepareStatement(sql)) {
            s.setLong(1, id);
            s.executeUpdate();
        }
    }

    @Override
    public void schedule(ScheduledExecutorService executor) throws OpenAS2Exception {
        int interval = getRefreshInterval();
        if (interval <= 0) {
            return;
        }
        executor.scheduleWithFixedDelay(new Runnable() {
            @Override
            public void run() {
                try {
                    refreshIfChanged();
                    if (consecutiveFailures > 0) {
                        logger.info("Partnership refresh from database recovered after " + consecutiveFailures + " failed attempt(s)");
                        consecutiveFailures = 0;
                    }
                } catch (Exception e) {
                    consecutiveFailures++;
                    // Only the first failure is an ERROR (which may trigger alerting); repeats while the
                    // database stays unavailable are logged as warnings. The loaded state stays active.
                    if (consecutiveFailures == 1) {
                        logger.error("Failed to refresh partnerships from database, keeping the current partnerships: " + e.getMessage(), e);
                    } else {
                        logger.warn("Partnership refresh from database still failing (attempt " + consecutiveFailures + "): " + e.getMessage());
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
    }
}

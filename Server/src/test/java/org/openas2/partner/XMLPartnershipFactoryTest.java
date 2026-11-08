package org.openas2.partner;

import org.junit.jupiter.api.Test;
import org.openas2.OpenAS2Exception;
import org.w3c.dom.Document;
import org.w3c.dom.Element;

import javax.xml.parsers.DocumentBuilderFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Verifies how duplicate definitions in the partnerships file are handled. A duplicate must not
 * abort loading the file since that leaves the system unable to send anything, but the commands
 * that add a partner or partnership must still reject one that already exists.
 */
public class XMLPartnershipFactoryTest {

    private final Document doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().newDocument();

    public XMLPartnershipFactoryTest() throws Exception {
    }

    private Element partnerNode(String name, String as2Id) {
        Element partner = doc.createElement("partner");
        partner.setAttribute(Partnership.PID_NAME, name);
        partner.setAttribute(Partnership.PID_AS2, as2Id);
        return partner;
    }

    private Element partnershipNode(String name, String senderName, String receiverName, String url) {
        Element partnership = doc.createElement("partnership");
        partnership.setAttribute("name", name);
        Element sender = doc.createElement(Partnership.PTYPE_SENDER);
        sender.setAttribute(Partnership.PID_NAME, senderName);
        partnership.appendChild(sender);
        Element receiver = doc.createElement(Partnership.PTYPE_RECEIVER);
        receiver.setAttribute(Partnership.PID_NAME, receiverName);
        partnership.appendChild(receiver);
        Element attribute = doc.createElement("attribute");
        attribute.setAttribute("name", Partnership.PA_AS2_URL);
        attribute.setAttribute("value", url);
        partnership.appendChild(attribute);
        return partnership;
    }

    private Map<String, Object> partners() throws OpenAS2Exception {
        XMLPartnershipFactory factory = new XMLPartnershipFactory();
        Map<String, Object> partners = new HashMap<String, Object>();
        factory.loadPartner(partners, partnerNode("MyCompany", "MyCompany_OID"), false);
        factory.loadPartner(partners, partnerNode("PartnerA", "PartnerA_OID"), false);
        return partners;
    }

    @Test
    public void duplicatePartnershipIsIgnoredAndTheFirstOneIsUsed() throws Exception {
        XMLPartnershipFactory factory = new XMLPartnershipFactory();
        Map<String, Object> partners = partners();
        List<Partnership> partnerships = new ArrayList<Partnership>();

        assertNotNull(factory.loadPartnership(partners, partnerships,
                partnershipNode("MyCompany-to-PartnerA", "MyCompany", "PartnerA", "http://first:10080"), false));
        assertNull(factory.loadPartnership(partners, partnerships,
                partnershipNode("MyCompany-to-PartnerA", "MyCompany", "PartnerA", "http://second:10080"), false),
                "the duplicate partnership must be reported as skipped");

        assertEquals(1, partnerships.size(), "the duplicate must not be added to the partnership list");
        assertEquals("http://first:10080", partnerships.get(0).getAttribute(Partnership.PA_AS2_URL),
                "the first definition found must remain in effect");
    }

    @Test
    public void duplicatePartnerIsIgnoredAndTheFirstOneIsUsed() throws Exception {
        XMLPartnershipFactory factory = new XMLPartnershipFactory();
        Map<String, Object> partners = new HashMap<String, Object>();

        factory.loadPartner(partners, partnerNode("PartnerA", "PartnerA_OID"), false);
        factory.loadPartner(partners, partnerNode("PartnerA", "SomeOtherOID"), false);

        assertEquals(1, partners.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> partner = (Map<String, Object>) partners.get("PartnerA");
        assertEquals("PartnerA_OID", partner.get(Partnership.PID_AS2),
                "the first definition found must remain in effect");
    }

    @Test
    public void addingAnAlreadyDefinedPartnershipStillFails() throws Exception {
        XMLPartnershipFactory factory = new XMLPartnershipFactory();
        Map<String, Object> partners = partners();
        List<Partnership> partnerships = new ArrayList<Partnership>();

        factory.loadPartnership(partners, partnerships,
                partnershipNode("MyCompany-to-PartnerA", "MyCompany", "PartnerA", "http://first:10080"));

        assertThrows(OpenAS2Exception.class, () -> factory.loadPartnership(partners, partnerships,
                partnershipNode("MyCompany-to-PartnerA", "MyCompany", "PartnerA", "http://second:10080")));
    }

    @Test
    public void addingAnAlreadyDefinedPartnerStillFails() throws Exception {
        XMLPartnershipFactory factory = new XMLPartnershipFactory();
        Map<String, Object> partners = new HashMap<String, Object>();

        factory.loadPartner(partners, partnerNode("PartnerA", "PartnerA_OID"));

        assertThrows(OpenAS2Exception.class,
                () -> factory.loadPartner(partners, partnerNode("PartnerA", "SomeOtherOID")));
    }
}

package org.openas2.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.openas2.message.AS2Message;
import org.openas2.message.FileAttribute;

import java.io.File;
import java.io.FileOutputStream;
import java.io.ObjectOutputStream;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The pending MDN information file: eight entries written by AS2SenderModule.storePendingInfo, plus (EDIPLUG,
 * since v2.2) the Message-ID as a ninth. Files written by older versions must still be readable.
 */
public class AS2UtilPendingInfoTest {

    private static final String MSG_ID = "<msg.2026-11-20-154803.e461593d.EDIPLUG_VM_3.to.EDIPLUG_LOCAL>";

    @TempDir
    Path dir;

    private File write(String name, boolean withMessageId) throws Exception {
        File f = dir.resolve(name).toFile();
        Map<String, String> attributes = new HashMap<String, String>();
        attributes.put("custom", "value");
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
            oos.writeObject("mic-value, sha-256");
            oos.writeObject("3");
            oos.writeObject("invoice.edi");
            oos.writeObject("invoice.edi");
            oos.writeObject("/data/pendingMDN3/" + name);
            oos.writeObject("/data/outbox/error");
            oos.writeObject("");
            oos.writeObject(attributes);
            if (withMessageId) {
                oos.writeObject(MSG_ID);
            }
        }
        return f;
    }

    @Test
    public void aFileWrittenByAnOlderVersionIsReadWithoutAMessageId() throws Exception {
        AS2Message msg = new AS2Message();
        AS2Util.getMetaData(msg, write("old-format", false));

        assertNull(msg.getMessageID());
        assertEquals("mic-value, sha-256", msg.getCalculatedMIC());
        assertEquals("/data/pendingMDN3/old-format", msg.getAttribute(FileAttribute.MA_PENDINGFILE));
        assertEquals("value", msg.getAttribute("custom"));
    }

    @Test
    public void theMessageIdIsRestoredFromTheNinthEntry() throws Exception {
        AS2Message msg = new AS2Message();
        AS2Util.getMetaData(msg, write("new-format", true));

        assertEquals(MSG_ID, msg.getMessageID());
        assertEquals("/data/pendingMDN3/new-format", msg.getAttribute(FileAttribute.MA_PENDINGFILE));
    }

    @Test
    public void anIdAlreadySetFromTheMdnIsKept() throws Exception {
        AS2Message msg = new AS2Message();
        msg.setMessageID("<id-from-the-mdn>");
        AS2Util.getMetaData(msg, write("new-format-mdn", true));

        assertEquals("<id-from-the-mdn>", msg.getMessageID());
    }
}

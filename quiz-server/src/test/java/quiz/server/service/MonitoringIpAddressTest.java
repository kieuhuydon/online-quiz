package quiz.server.service;

import org.junit.jupiter.api.Test;
import quiz.server.exception.ValidationException;
import static org.junit.jupiter.api.Assertions.*;

class MonitoringIpAddressTest {
    @Test void acceptsLiteralsAndTrimsBlankInput() {
        assertEquals("192.168.1.23",MonitoringIpAddress.normalize(" 192.168.1.23 "));
        assertEquals("127.0.0.1",MonitoringIpAddress.normalize("127.0.0.1"));
        assertNotNull(MonitoringIpAddress.normalize("::1"));
        assertNull(MonitoringIpAddress.normalize(null));
        assertNull(MonitoringIpAddress.normalize(" \t "));
    }
    @Test void rejectsMalformedAddressesHostnamesAndNonUnicastAddresses() {
        for(String input:new String[]{"localhost","example.com","999.1.1.1","1.2.3","a.b.c.d","....",
            "1".repeat(50),"0.0.0.0","224.0.0.1","::","1.2.3.4 OR TRUE"})
            assertThrows(ValidationException.class,()->MonitoringIpAddress.normalize(input),input);
    }
}

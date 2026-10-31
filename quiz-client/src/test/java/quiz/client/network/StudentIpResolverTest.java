package quiz.client.network;

import org.junit.jupiter.api.Test;
import java.net.InetAddress;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StudentIpResolverTest {
    private StudentIpResolver.Candidate nic(String name,boolean active,String address) throws Exception {
        return new StudentIpResolver.Candidate(name,active,InetAddress.getByName(address));
    }

    @Test void localServerStillReportsPhysicalLanIpv4() throws Exception {
        var candidates=List.of(nic("loopback",true,"127.0.0.1"),nic("Ethernet",false,"10.0.0.5"),
            nic("vEthernet (WSL)",true,"172.24.0.1"),nic("Wi-Fi",true,"192.168.1.23"),
            nic("Wi-Fi",true,"fe80::1"),nic("Ethernet 2",true,"169.254.1.1"));
        assertEquals("192.168.1.23",StudentIpResolver.select(candidates,InetAddress.getByName("127.0.0.1")));
    }
    @Test void prefersInterfaceUsedForServerConnectionAndDetectsNetworkChange() throws Exception {
        var candidates=List.of(nic("Ethernet",true,"10.0.0.5"),nic("Wi-Fi",true,"192.168.1.23"));
        assertEquals("192.168.1.23",StudentIpResolver.select(candidates,InetAddress.getByName("192.168.1.23")));
        assertEquals("10.0.0.5",StudentIpResolver.select(candidates,InetAddress.getByName("10.0.0.5")));
    }
    @Test void excludesVirtualAdaptersEvenWhenOsDoesNotMarkThemVirtual() throws Exception {
        var candidates=List.of(nic("VMware Network Adapter",true,"192.168.20.1"),
            nic("WireGuard Tunnel",true,"10.8.0.1"),nic("Ethernet",true,"192.168.1.23"));
        assertEquals("192.168.1.23",StudentIpResolver.select(candidates,InetAddress.getByName("10.8.0.1")));
    }
    @Test void withoutLanKeepsTruthfulSocketAddressInsteadOfFabricatingOne() throws Exception {
        assertEquals("127.0.0.1",StudentIpResolver.select(List.of(),InetAddress.getByName("127.0.0.1")));
        assertNull(StudentIpResolver.select(List.of(nic("VirtualBox",true,"192.168.56.1")),null));
        assertNull(StudentIpResolver.select(List.of(),InetAddress.getByName("0.0.0.0")));
    }
}

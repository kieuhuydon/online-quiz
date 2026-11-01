package quiz.client.network;

import java.net.*;
import java.util.*;

/** Resolve on a background thread; hostname lookup is deliberately avoided. */
public final class StudentIpResolver {
    private StudentIpResolver() { }

    public static String resolve(InetAddress socketAddress) {
        List<Candidate> candidates=new ArrayList<>();
        try {
            var interfaces=NetworkInterface.getNetworkInterfaces();
            if(interfaces!=null) for(NetworkInterface nic:Collections.list(interfaces)) {
                try {
                    boolean usable=nic.isUp() && !nic.isLoopback() && !nic.isVirtual() && !nic.isPointToPoint();
                    String name=nic.getName()+" "+Objects.toString(nic.getDisplayName(),"");
                    for(InetAddress address:Collections.list(nic.getInetAddresses()))
                        candidates.add(new Candidate(name,usable,address));
                } catch(SocketException ignored) {
                    // Interfaces can disappear during a Wi-Fi/reconnect transition.
                }
            }
        } catch(SocketException | SecurityException ignored) {
            // An established socket still provides a truthful fallback address.
        }
        return select(candidates,socketAddress);
    }

    record Candidate(String name,boolean usable,InetAddress address) { }

    static String select(List<Candidate> candidates,InetAddress socketAddress) {
        Optional<Candidate> selected=candidates.stream()
            .filter(c->c.usable() && !virtualAdapter(c.name()) && usableIpv4(c.address()))
            .min(Comparator.<Candidate>comparingInt(c->c.address().equals(socketAddress)?0:c.address().isSiteLocalAddress()?1:2)
                .thenComparing(Candidate::name).thenComparing(c->c.address().getHostAddress()));
        if(selected.isPresent()) return selected.get().address().getHostAddress();
        // With no physical IPv4 (e.g. offline localhost/IPv6-only/VM), report the real socket address.
        // Do not invent a LAN address or fall back to arbitrary virtual adapters.
        if(socketAddress!=null && !socketAddress.isAnyLocalAddress() && !socketAddress.isMulticastAddress())
            return socketAddress.getHostAddress().split("%",2)[0];
        return null;
    }

    private static boolean usableIpv4(InetAddress address) {
        return address instanceof Inet4Address && !address.isAnyLocalAddress() && !address.isLoopbackAddress()
            && !address.isLinkLocalAddress() && !address.isMulticastAddress();
    }

    private static boolean virtualAdapter(String name) {
        String lower=name.toLowerCase(Locale.ROOT);
        return List.of("virtual","vethernet","vmware","vbox","hyper-v","docker","wsl","vpn","tap",
            "tun","wireguard","tailscale","zerotier").stream().anyMatch(lower::contains);
    }
}

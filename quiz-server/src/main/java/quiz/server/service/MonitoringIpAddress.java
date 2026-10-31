package quiz.server.service;

import quiz.server.exception.ValidationException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/** Accept literals only: never resolve a client-supplied hostname using DNS. */
final class MonitoringIpAddress {
    private MonitoringIpAddress() { }

    static String normalize(String value) {
        if(value==null || value.isBlank()) return null;
        String ip=value.strip();
        if(ip.length()>45) throw invalid();
        if(ip.matches("[0-9]+(?:\\.[0-9]+){3}")) {
            for(String part:ip.split("\\.")) {
                if(part.length()>3 || Integer.parseInt(part)>255) throw invalid();
            }
        } else if(!(ip.contains(":") && ip.matches("[0-9a-fA-F:.]+"))) {
            throw invalid();
        }
        try {
            InetAddress address=InetAddress.getByName(ip);
            if(address.isAnyLocalAddress() || address.isMulticastAddress()) throw invalid();
            return address.getHostAddress();
        } catch(UnknownHostException e) {
            throw invalid();
        }
    }

    private static ValidationException invalid() { return new ValidationException("Địa chỉ IP không hợp lệ"); }
}

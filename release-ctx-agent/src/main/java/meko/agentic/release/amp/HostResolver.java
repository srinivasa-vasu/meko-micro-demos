package meko.agentic.release.amp;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where to connect for an AMP endpoint's host name, without asking the operating system too early.
 * <p>
 * A new endpoint's DNS name is published a few seconds after AMP returns its address. A lookup through the operating
 * system before then is answered "not found", and macOS's resolver keeps that answer for minutes, so every later
 * connection fails although the name exists. Observed while building this demo. So the name is first looked up with
 * direct DNS queries (which no system cache sees); only once they find it does the operating system resolve it, and if
 * its cache still says "not found", the address the direct query returned is used. AMP routes endpoints by port and
 * this application's TLS mode does not check the host name, so the address alone reaches the endpoint.
 */
public final class HostResolver {
    private HostResolver() {}

    /** Direct DNS: the system's own name servers first, then public ones (a VPN's resolver can refuse some queries). */
    private static final List<String> SERVERS = List.of("dns:", "dns://1.1.1.1", "dns://8.8.8.8");
    /** Hosts whose names direct DNS has found, with the first address it returned. */
    private static final Map<String, String> found = new ConcurrentHashMap<>();

    /** The host's address per direct DNS, or null while its name is not published yet. */
    static String lookup(String host) {
        if (isAddress(host)) return host;
        String known = found.get(host);
        if (known != null) return known;
        for (String server : SERVERS) {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", server);
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            try {
                // One record type per query: some resolvers refuse a query for several.
                Attributes records = new InitialDirContext(env).getAttributes(host, new String[]{"A"});
                Attribute a = records.get("A");
                if (a != null && a.size() > 0) {
                    String address = String.valueOf(a.get(0));
                    found.put(host, address);
                    return address;
                }
            } catch (Exception e) {
                // Not published yet by this server's account, or it could not answer: ask the next.
            }
        }
        return null;
    }

    /**
     * What to give the driver for {@code host}: the host name once the operating system resolves it, or the address
     * direct DNS found. Throws while the name is not published yet: no system lookup is made before it is.
     */
    public static String connectAddress(String host) throws UnknownHostException {
        if (isAddress(host)) return host;
        String direct = lookup(host);
        if (direct == null) throw new UnknownHostException(host + " is not published in DNS yet");
        try {
            InetAddress.getByName(host);
            return host;
        } catch (UnknownHostException e) {
            // The system's cache still holds an earlier "not found".
            return direct;
        }
    }

    private static boolean isAddress(String host) {
        return host.matches("[0-9.]+") || host.contains(":");
    }
}

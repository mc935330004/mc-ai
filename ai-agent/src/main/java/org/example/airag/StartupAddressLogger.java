package org.example.airag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;

@Component
class StartupAddressLogger implements ApplicationListener<ApplicationReadyEvent> {

    private static final Logger log = LoggerFactory.getLogger(StartupAddressLogger.class);

    private final Environment environment;

    StartupAddressLogger(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        log.info(buildStartupMessage(findLocalIpAddress()));
    }

    String buildStartupMessage(String localIp) {
        String applicationName = environment.getProperty("spring.application.name", "application");
        String port = environment.getProperty("local.server.port",
                environment.getProperty("server.port", "8080"));
        String contextPath = normalizeContextPath(
                environment.getProperty("server.servlet.context-path", ""));

        return System.lineSeparator()
                + "----------------------------------------------------------" + System.lineSeparator()
                + "Application '" + applicationName + "' is running" + System.lineSeparator()
                + "Local IP: " + localIp + System.lineSeparator()
                + "Local:   http://localhost:" + port + contextPath + System.lineSeparator()
                + "Network: http://" + localIp + ":" + port + contextPath + System.lineSeparator()
                + "----------------------------------------------------------";
    }

    private static String normalizeContextPath(String contextPath) {
        if (contextPath == null || contextPath.isBlank() || "/".equals(contextPath)) {
            return "";
        }
        return contextPath.startsWith("/") ? contextPath : "/" + contextPath;
    }

    private static String findLocalIpAddress() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!networkInterface.isUp() || networkInterface.isLoopback() || networkInterface.isVirtual()) {
                    continue;
                }
                for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (address instanceof Inet4Address
                            && !address.isLoopbackAddress()
                            && !address.isLinkLocalAddress()) {
                        return address.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostAddress();
        } catch (SocketException | UnknownHostException ex) {
            return "127.0.0.1";
        }
    }
}

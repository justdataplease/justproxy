package com.justproxy.app.proxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

final class HttpProxyHandler {
    private static final String HEADER_END = "\r\n\r\n";

    private HttpProxyHandler() {}

    static void handle(
            ProxyServerConfig config,
            ProxySession session,
            InputStream clientInput,
            OutputStream clientOutput)
            throws IOException {
        HttpRequest request;
        try {
            request = readRequest(clientInput, config.getMaxHttpHeaderBytes());
        } catch (HeaderTooLargeException tooLarge) {
            throw sendFailure(
                    clientOutput,
                    "431 Request Header Fields Too Large",
                    SessionCloseReason.PROTOCOL_ERROR,
                    "HTTP request headers exceed configured limit");
        } catch (ProxyFailure protocolFailure) {
            sendResponse(clientOutput, "400 Bad Request", null);
            throw protocolFailure;
        }

        if (!isAuthenticated(request, config)) {
            sendResponse(
                    clientOutput,
                    "407 Proxy Authentication Required",
                    "Proxy-Authenticate: Basic realm=\"JustProxy\"\r\n");
            throw new ProxyFailure(
                    SessionCloseReason.AUTHENTICATION_FAILED,
                    "HTTP proxy authentication failed");
        }
        session.setAuthenticated();

        if ("CONNECT".equalsIgnoreCase(request.method)) {
            handleConnect(config, session, clientInput, clientOutput, request);
        } else {
            handleAbsoluteRequest(config, session, clientInput, clientOutput, request);
        }
    }

    private static void handleConnect(
            ProxyServerConfig config,
            ProxySession session,
            InputStream clientInput,
            OutputStream clientOutput,
            HttpRequest request)
            throws IOException {
        HostPort destination;
        try {
            destination = parseAuthority(request.target, 443);
        } catch (IllegalArgumentException invalid) {
            throw sendFailure(
                    clientOutput,
                    "400 Bad Request",
                    SessionCloseReason.PROTOCOL_ERROR,
                    "invalid CONNECT authority");
        }
        session.setTarget(destination.host, destination.port);

        Socket upstream = connectOrRespond(
                config, session, destination.host, null, destination.port, clientOutput);
        clientOutput.write(
                "HTTP/1.1 200 Connection Established\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
        clientOutput.flush();
        ProxyIo.relayBidirectional(
                session,
                clientInput,
                clientOutput,
                upstream,
                config.getIdleTimeoutMillis());
    }

    private static void handleAbsoluteRequest(
            ProxyServerConfig config,
            ProxySession session,
            InputStream clientInput,
            OutputStream clientOutput,
            HttpRequest request)
            throws IOException {
        URI uri;
        try {
            uri = new URI(request.target);
        } catch (URISyntaxException invalid) {
            throw sendFailure(
                    clientOutput,
                    "400 Bad Request",
                    SessionCloseReason.PROTOCOL_ERROR,
                    "invalid absolute HTTP request target");
        }
        if (!uri.isAbsolute()
                || !"http".equalsIgnoreCase(uri.getScheme())
                || uri.getHost() == null
                || uri.getRawUserInfo() != null) {
            throw sendFailure(
                    clientOutput,
                    "400 Bad Request",
                    SessionCloseReason.PROTOCOL_ERROR,
                    "only absolute-form http URLs are supported");
        }

        String host = uri.getHost();
        int port = uri.getPort() < 0 ? 80 : uri.getPort();
        session.setTarget(host, port);
        Socket upstream = connectOrRespond(config, session, host, null, port, clientOutput);

        String rawPath = uri.getRawPath();
        String originForm = rawPath == null || rawPath.isEmpty() ? "/" : rawPath;
        if (uri.getRawQuery() != null) {
            originForm += "?" + uri.getRawQuery();
        }
        byte[] forwardedHead = buildForwardedHead(request, originForm, host, port);
        OutputStream upstreamOutput = upstream.getOutputStream();
        upstreamOutput.write(forwardedHead);
        upstreamOutput.flush();
        session.addUploaded(forwardedHead.length);

        ProxyIo.relayBidirectional(
                session,
                clientInput,
                clientOutput,
                upstream,
                config.getIdleTimeoutMillis());
    }

    private static Socket connectOrRespond(
            ProxyServerConfig config,
            ProxySession session,
            String host,
            InetAddress literalAddress,
            int port,
            OutputStream clientOutput)
            throws IOException {
        try {
            return ProxyIo.connect(config, session, host, literalAddress, port);
        } catch (ProxyFailure denied) {
            if (denied.getCloseReason() == SessionCloseReason.DESTINATION_DENIED) {
                sendResponse(clientOutput, "403 Forbidden", null);
            } else {
                sendResponse(clientOutput, "400 Bad Request", null);
            }
            throw denied;
        } catch (IOException connectFailure) {
            sendResponse(clientOutput, "502 Bad Gateway", null);
            throw new ProxyFailure(
                    SessionCloseReason.CONNECT_FAILED,
                    "could not connect to HTTP target",
                    connectFailure);
        }
    }

    private static boolean isAuthenticated(HttpRequest request, ProxyServerConfig config) {
        String value = request.firstHeader("Proxy-Authorization");
        if (value == null) {
            return false;
        }
        int space = value.indexOf(' ');
        if (space <= 0 || !"basic".equalsIgnoreCase(value.substring(0, space).trim())) {
            return false;
        }
        byte[] supplied;
        try {
            supplied = Base64.getDecoder().decode(value.substring(space + 1).trim());
        } catch (IllegalArgumentException invalidBase64) {
            return false;
        }
        return MessageDigest.isEqual(config.getBasicCredentials(), supplied);
    }

    private static HttpRequest readRequest(InputStream input, int maximumBytes) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maximumBytes, 4096));
        int matched = 0;
        while (bytes.size() < maximumBytes) {
            int next = input.read();
            if (next < 0) {
                throw new ProxyFailure(
                        SessionCloseReason.CLIENT_CLOSED,
                        "client closed before completing HTTP headers");
            }
            bytes.write(next);
            if (next == HEADER_END.charAt(matched)) {
                matched++;
                if (matched == HEADER_END.length()) {
                    return parseRequest(new String(
                            bytes.toByteArray(), StandardCharsets.ISO_8859_1));
                }
            } else {
                matched = next == '\r' ? 1 : 0;
            }
        }
        throw new HeaderTooLargeException();
    }

    private static HttpRequest parseRequest(String raw) throws ProxyFailure {
        String[] lines = raw.split("\\r\\n", -1);
        if (lines.length < 2) {
            throw protocolFailure("missing HTTP request line");
        }
        String requestLine = lines[0];
        int firstSpace = requestLine.indexOf(' ');
        int secondSpace = firstSpace < 0 ? -1 : requestLine.indexOf(' ', firstSpace + 1);
        if (firstSpace <= 0 || secondSpace <= firstSpace + 1) {
            throw protocolFailure("invalid HTTP request line");
        }
        String method = requestLine.substring(0, firstSpace);
        String target = requestLine.substring(firstSpace + 1, secondSpace);
        String version = requestLine.substring(secondSpace + 1).trim();
        if (!version.startsWith("HTTP/1.")) {
            throw protocolFailure("unsupported HTTP version");
        }

        List<Header> headers = new ArrayList<Header>();
        for (int i = 1; i < lines.length && !lines[i].isEmpty(); i++) {
            String line = lines[i];
            if (line.charAt(0) == ' ' || line.charAt(0) == '\t') {
                throw protocolFailure("obsolete folded HTTP header");
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                throw protocolFailure("invalid HTTP header");
            }
            String name = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (!isHeaderName(name)) {
                throw protocolFailure("invalid HTTP header name");
            }
            headers.add(new Header(name, value));
        }
        return new HttpRequest(method, target, version, headers);
    }

    private static boolean isHeaderName(String name) {
        if (name.isEmpty()) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean token = (c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0;
            if (!token) {
                return false;
            }
        }
        return true;
    }

    private static byte[] buildForwardedHead(
            HttpRequest request, String originForm, String host, int port) {
        StringBuilder result = new StringBuilder();
        result.append(request.method)
                .append(' ')
                .append(originForm)
                .append(' ')
                .append(request.version)
                .append("\r\n");

        Set<String> connectionTokens = new HashSet<String>();
        for (Header header : request.headers) {
            if ("connection".equalsIgnoreCase(header.name)) {
                String[] tokens = header.value.split(",");
                for (String token : tokens) {
                    connectionTokens.add(token.trim().toLowerCase(Locale.US));
                }
            }
        }

        boolean hasHost = false;
        for (Header header : request.headers) {
            String lowerName = header.name.toLowerCase(Locale.US);
            if ("host".equals(lowerName)) {
                hasHost = true;
            }
            if ("proxy-authorization".equals(lowerName)
                    || "proxy-connection".equals(lowerName)
                    || "connection".equals(lowerName)
                    || "keep-alive".equals(lowerName)
                    || connectionTokens.contains(lowerName)) {
                continue;
            }
            result.append(header.name).append(": ").append(header.value).append("\r\n");
        }
        if (!hasHost) {
            result.append("Host: ").append(formatAuthority(host, port, 80)).append("\r\n");
        }
        result.append("Connection: close\r\n\r\n");
        return result.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static HostPort parseAuthority(String authority, int defaultPort) {
        if (authority == null || authority.isEmpty() || authority.indexOf('@') >= 0) {
            throw new IllegalArgumentException("invalid authority");
        }
        String host;
        String portText = null;
        if (authority.charAt(0) == '[') {
            int close = authority.indexOf(']');
            if (close <= 1) {
                throw new IllegalArgumentException("invalid IPv6 authority");
            }
            host = authority.substring(1, close);
            if (close + 1 < authority.length()) {
                if (authority.charAt(close + 1) != ':') {
                    throw new IllegalArgumentException("invalid IPv6 authority");
                }
                portText = authority.substring(close + 2);
            }
        } else {
            int colon = authority.lastIndexOf(':');
            if (colon >= 0) {
                if (authority.indexOf(':') != colon) {
                    throw new IllegalArgumentException("IPv6 addresses must use brackets");
                }
                host = authority.substring(0, colon);
                portText = authority.substring(colon + 1);
            } else {
                host = authority;
            }
        }
        if (host.isEmpty()) {
            throw new IllegalArgumentException("empty host");
        }
        int port = defaultPort;
        if (portText != null) {
            if (portText.isEmpty()) {
                throw new IllegalArgumentException("empty port");
            }
            try {
                port = Integer.parseInt(portText);
            } catch (NumberFormatException invalid) {
                throw new IllegalArgumentException("invalid port", invalid);
            }
        }
        if (port < 1 || port > 65_535) {
            throw new IllegalArgumentException("invalid port");
        }
        return new HostPort(host, port);
    }

    private static String formatAuthority(String host, int port, int defaultPort) {
        String formattedHost = host.indexOf(':') >= 0 ? "[" + host + "]" : host;
        return port == defaultPort ? formattedHost : formattedHost + ":" + port;
    }

    private static ProxyFailure sendFailure(
            OutputStream output,
            String status,
            SessionCloseReason reason,
            String message)
            throws IOException {
        sendResponse(output, status, null);
        return new ProxyFailure(reason, message);
    }

    private static void sendResponse(OutputStream output, String status, String extraHeaders)
            throws IOException {
        String response = "HTTP/1.1 " + status + "\r\n"
                + (extraHeaders == null ? "" : extraHeaders)
                + "Content-Length: 0\r\nConnection: close\r\n\r\n";
        output.write(response.getBytes(StandardCharsets.ISO_8859_1));
        output.flush();
    }

    private static ProxyFailure protocolFailure(String message) {
        return new ProxyFailure(SessionCloseReason.PROTOCOL_ERROR, message);
    }

    private static final class HttpRequest {
        private final String method;
        private final String target;
        private final String version;
        private final List<Header> headers;

        private HttpRequest(String method, String target, String version, List<Header> headers) {
            this.method = method;
            this.target = target;
            this.version = version;
            this.headers = headers;
        }

        private String firstHeader(String name) {
            for (Header header : headers) {
                if (name.equalsIgnoreCase(header.name)) {
                    return header.value;
                }
            }
            return null;
        }
    }

    private static final class Header {
        private final String name;
        private final String value;

        private Header(String name, String value) {
            this.name = name;
            this.value = value;
        }
    }

    private static final class HostPort {
        private final String host;
        private final int port;

        private HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static final class HeaderTooLargeException extends IOException {}
}

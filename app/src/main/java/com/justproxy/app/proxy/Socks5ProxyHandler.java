package com.justproxy.app.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** TCP CONNECT subset of SOCKS5. BIND and UDP ASSOCIATE are deliberately unsupported. */
final class Socks5ProxyHandler {
    private static final int VERSION = 0x05;
    private static final int USERNAME_PASSWORD_METHOD = 0x02;

    private Socks5ProxyHandler() {}

    static void handle(
            ProxyServerConfig config,
            ProxySession session,
            InputStream clientInput,
            OutputStream clientOutput)
            throws IOException {
        negotiateAuthentication(config, session, clientInput, clientOutput);

        int version = ProxyIo.readUnsignedByte(clientInput);
        int command = ProxyIo.readUnsignedByte(clientInput);
        int reserved = ProxyIo.readUnsignedByte(clientInput);
        int addressType = ProxyIo.readUnsignedByte(clientInput);
        if (version != VERSION || reserved != 0) {
            sendReply(clientOutput, 0x01, null);
            throw new ProxyFailure(
                    SessionCloseReason.PROTOCOL_ERROR, "invalid SOCKS5 request header");
        }
        if (command != 0x01) {
            sendReply(clientOutput, 0x07, null);
            throw new ProxyFailure(
                    SessionCloseReason.PROTOCOL_ERROR,
                    "only SOCKS5 TCP CONNECT is supported; BIND and UDP are disabled");
        }

        Target target;
        try {
            target = readTarget(clientInput, addressType);
        } catch (ProxyFailure unsupportedAddress) {
            sendReply(clientOutput, 0x08, null);
            throw unsupportedAddress;
        }
        int port = (ProxyIo.readUnsignedByte(clientInput) << 8)
                | ProxyIo.readUnsignedByte(clientInput);
        session.setTarget(target.host, port);

        Socket upstream;
        try {
            upstream = ProxyIo.connect(config, session, target.host, target.literalAddress, port);
        } catch (ProxyFailure denied) {
            sendReply(
                    clientOutput,
                    denied.getCloseReason() == SessionCloseReason.DESTINATION_DENIED
                            ? 0x02
                            : 0x01,
                    null);
            throw denied;
        } catch (IOException connectFailure) {
            sendReply(clientOutput, replyCode(connectFailure), null);
            throw new ProxyFailure(
                    SessionCloseReason.CONNECT_FAILED,
                    "could not connect to SOCKS5 target",
                    connectFailure);
        }

        sendReply(clientOutput, 0x00, upstream);
        ProxyIo.relayBidirectional(
                session,
                clientInput,
                clientOutput,
                upstream,
                config.getIdleTimeoutMillis());
    }

    private static void negotiateAuthentication(
            ProxyServerConfig config,
            ProxySession session,
            InputStream input,
            OutputStream output)
            throws IOException {
        int version = ProxyIo.readUnsignedByte(input);
        int methodCount = ProxyIo.readUnsignedByte(input);
        if (version != VERSION || methodCount == 0) {
            throw new ProxyFailure(
                    SessionCloseReason.PROTOCOL_ERROR, "invalid SOCKS5 greeting");
        }
        boolean supportsUsernamePassword = false;
        for (int i = 0; i < methodCount; i++) {
            if (ProxyIo.readUnsignedByte(input) == USERNAME_PASSWORD_METHOD) {
                supportsUsernamePassword = true;
            }
        }
        if (!supportsUsernamePassword) {
            output.write(new byte[] {(byte) VERSION, (byte) 0xff});
            output.flush();
            throw new ProxyFailure(
                    SessionCloseReason.AUTHENTICATION_FAILED,
                    "SOCKS5 client did not offer username/password authentication");
        }
        output.write(new byte[] {(byte) VERSION, (byte) USERNAME_PASSWORD_METHOD});
        output.flush();

        int authVersion = ProxyIo.readUnsignedByte(input);
        int usernameLength = ProxyIo.readUnsignedByte(input);
        byte[] username = ProxyIo.readFully(input, usernameLength);
        int passwordLength = ProxyIo.readUnsignedByte(input);
        byte[] password = ProxyIo.readFully(input, passwordLength);
        boolean usernameMatches = MessageDigest.isEqual(config.getUsername(), username);
        boolean passwordMatches = MessageDigest.isEqual(config.getPassword(), password);
        boolean valid = authVersion == 0x01 & usernameMatches & passwordMatches;
        output.write(new byte[] {0x01, (byte) (valid ? 0x00 : 0x01)});
        output.flush();
        if (!valid) {
            throw new ProxyFailure(
                    SessionCloseReason.AUTHENTICATION_FAILED,
                    "SOCKS5 username/password authentication failed");
        }
        session.setAuthenticated();
    }

    private static Target readTarget(InputStream input, int addressType) throws IOException {
        if (addressType == 0x01) {
            InetAddress address = InetAddress.getByAddress(ProxyIo.readFully(input, 4));
            return new Target(address.getHostAddress(), address);
        }
        if (addressType == 0x03) {
            int length = ProxyIo.readUnsignedByte(input);
            if (length == 0) {
                throw new ProxyFailure(
                        SessionCloseReason.PROTOCOL_ERROR, "empty SOCKS5 domain name");
            }
            String host = new String(
                    ProxyIo.readFully(input, length), StandardCharsets.US_ASCII);
            return new Target(host, null);
        }
        if (addressType == 0x04) {
            InetAddress address = InetAddress.getByAddress(ProxyIo.readFully(input, 16));
            return new Target(address.getHostAddress(), address);
        }
        throw new ProxyFailure(
                SessionCloseReason.PROTOCOL_ERROR, "unsupported SOCKS5 address type");
    }

    private static int replyCode(IOException failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof ConnectException) {
                return 0x05;
            }
            if (current instanceof NoRouteToHostException) {
                return 0x03;
            }
            if (current instanceof UnknownHostException) {
                return 0x04;
            }
            current = current.getCause();
        }
        return 0x01;
    }

    private static void sendReply(OutputStream output, int reply, Socket boundSocket)
            throws IOException {
        InetAddress boundAddress = boundSocket == null ? null : boundSocket.getLocalAddress();
        int boundPort = boundSocket == null ? 0 : boundSocket.getLocalPort();
        byte[] address = boundAddress == null ? new byte[] {0, 0, 0, 0}
                : boundAddress.getAddress();
        int type;
        if (boundAddress instanceof Inet6Address) {
            type = 0x04;
        } else {
            type = 0x01;
            if (!(boundAddress instanceof Inet4Address)) {
                address = new byte[] {0, 0, 0, 0};
            }
        }
        output.write(VERSION);
        output.write(reply);
        output.write(0x00);
        output.write(type);
        output.write(address);
        output.write((boundPort >>> 8) & 0xff);
        output.write(boundPort & 0xff);
        output.flush();
    }

    private static final class Target {
        private final String host;
        private final InetAddress literalAddress;

        private Target(String host, InetAddress literalAddress) {
            this.host = host;
            this.literalAddress = literalAddress;
        }
    }
}

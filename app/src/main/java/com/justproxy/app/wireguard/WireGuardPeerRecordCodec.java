package com.justproxy.app.wireguard;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/** Versioned binary codec used inside the authenticated encrypted peer record. */
public final class WireGuardPeerRecordCodec {
    private static final int MAGIC = 0x4a505747; // JPWG
    private static final int VERSION = 1;
    private static final int MAX_RECORD_BYTES = 4096;

    private WireGuardPeerRecordCodec() {
    }

    public static byte[] encode(WireGuardPeerRecord record) {
        if (record == null) {
            throw new IllegalArgumentException("record must not be null");
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(320);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeInt(MAGIC);
            output.writeByte(VERSION);
            output.writeUTF(record.getPeerName().getValue());
            output.writeLong(record.getCreatedAtMillis());
            output.writeUTF(record.getServerPrivateKey().getEncoded());
            output.writeUTF(record.getServerPublicKey().getEncoded());
            output.writeUTF(record.getClientPrivateKey().getEncoded());
            output.writeUTF(record.getClientPublicKey().getEncoded());
            output.flush();
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to encode in-memory WireGuard record", impossible);
        }
    }

    public static WireGuardPeerRecord decode(byte[] encoded) {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_RECORD_BYTES) {
            throw new IllegalArgumentException("encrypted WireGuard record has an invalid size");
        }
        try {
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded));
            if (input.readInt() != MAGIC || input.readUnsignedByte() != VERSION) {
                throw new IllegalArgumentException("unsupported WireGuard record format");
            }
            WireGuardProfileName name = WireGuardProfileName.of(input.readUTF());
            long createdAtMillis = input.readLong();
            WireGuardKey serverPrivateKey = WireGuardKey.parse(input.readUTF());
            WireGuardKey serverPublicKey = WireGuardKey.parse(input.readUTF());
            WireGuardKey clientPrivateKey = WireGuardKey.parse(input.readUTF());
            WireGuardKey clientPublicKey = WireGuardKey.parse(input.readUTF());
            if (input.read() != -1) {
                throw new IllegalArgumentException("WireGuard record contains trailing data");
            }
            return new WireGuardPeerRecord(name, createdAtMillis, serverPrivateKey,
                    serverPublicKey, clientPrivateKey, clientPublicKey);
        } catch (EOFException exception) {
            throw new IllegalArgumentException("WireGuard record is truncated", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("WireGuard record cannot be decoded", exception);
        }
    }
}

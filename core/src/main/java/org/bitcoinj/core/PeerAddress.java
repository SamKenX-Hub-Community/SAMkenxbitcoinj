/*
 * Copyright 2011 Google Inc.
 * Copyright 2015 Andreas Schildbach
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.bitcoinj.core;

import com.google.common.io.BaseEncoding;
import org.bitcoinj.base.VarInt;
import org.bitcoinj.base.internal.Buffers;
import org.bitcoinj.base.internal.TimeUtils;
import org.bitcoinj.crypto.internal.CryptoUtils;
import org.bitcoinj.base.internal.ByteUtils;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * <p>A PeerAddress holds an IP address and port number representing the network location of
 * a peer in the Bitcoin P2P network. It exists primarily for serialization purposes.</p>
 *
 * <p>This class abuses the protocol version contained in its serializer. It can only contain 0 (format within
 * {@link VersionMessage}), 1 ({@link AddressV1Message}) or 2 ({@link AddressV2Message}).</p>
 * 
 * <p>Instances of this class are not safe for use by multiple threads.</p>
 */
public class PeerAddress extends Message {
    private InetAddress addr;   // Used for IPV4, IPV6, null otherwise or if not-yet-parsed
    private String hostname;    // Used for (.onion addresses) TORV2, TORV3, null otherwise or if not-yet-parsed
    private int port;
    private Services services;
    private Optional<Instant> time;

    private static final BaseEncoding BASE32 = BaseEncoding.base32().omitPadding().lowerCase();
    private static final byte[] ONIONCAT_PREFIX = ByteUtils.parseHex("fd87d87eeb43");

    // BIP-155 reserved network IDs, see: https://github.com/bitcoin/bips/blob/master/bip-0155.mediawiki
    private enum NetworkId {
        IPV4(1),
        IPV6(2),
        TORV2(3),
        TORV3(4),
        I2P(5),
        CJDNS(6);

        final int value;

        NetworkId(int value) {
            this.value = value;
        }

        static Optional<NetworkId> of(int value) {
            return Stream.of(values())
                .filter(id -> id.value == value)
                .findFirst();
        }
    }

    /**
     * Construct a peer address from a serialized payload.
     * @param payload Bitcoin protocol formatted byte array containing message content.
     * @param serializer the serializer to use for this message.
     * @throws ProtocolException
     */
    public PeerAddress(ByteBuffer payload, MessageSerializer serializer) throws ProtocolException {
        super(payload, serializer);
    }

    /**
     * Construct a peer address from a memorized or hardcoded address.
     */
    public PeerAddress(InetAddress addr, int port, Services services, MessageSerializer serializer) {
        super(serializer);
        this.addr = Objects.requireNonNull(addr);
        this.port = port;
        this.services = services;
        this.time = Optional.of(TimeUtils.currentTime().truncatedTo(ChronoUnit.SECONDS));
    }

    /**
     * Constructs a peer address from the given IP address, port and services. Version number is default for the given parameters.
     */
    public PeerAddress(InetAddress addr, int port, Services services) {
        this(addr, port, services, new DummySerializer(0));
    }

    /**
     * Constructs a peer address from the given IP address and port. Version number is default for the given parameters.
     */
    public PeerAddress(InetAddress addr, int port) {
        this(addr, port, Services.none());
    }

    /**
     * Constructs a peer address from an {@link InetSocketAddress}. An InetSocketAddress can take in as parameters an
     * InetAddress or a String hostname. If you want to connect to a .onion, set the hostname to the .onion address.
     */
    public PeerAddress(InetSocketAddress addr) {
        this(addr.getAddress(), addr.getPort());
    }

    /**
     * Constructs a peer address from a stringified hostname+port. Use this if you want to connect to a Tor .onion address.
     */
    public PeerAddress(String hostname, int port) {
        super();
        this.hostname = hostname;
        this.port = port;
        this.services = Services.none();
        this.time = Optional.of(TimeUtils.currentTime().truncatedTo(ChronoUnit.SECONDS));
    }

    public static PeerAddress localhost(NetworkParameters params) {
        return new PeerAddress(InetAddress.getLoopbackAddress(), params.getPort());
    }

    @Override
    protected void bitcoinSerializeToStream(OutputStream stream) throws IOException {
        int protocolVersion = serializer.getProtocolVersion();
        if (protocolVersion < 0 || protocolVersion > 2)
            throw new IllegalStateException("invalid protocolVersion: " + protocolVersion);

        if (protocolVersion >= 1) {
            ByteUtils.writeInt32LE(time.get().getEpochSecond(), stream);
        }
        if (protocolVersion == 2) {
            stream.write(VarInt.of(services.bits()).serialize());
            if (addr != null) {
                if (addr instanceof Inet4Address) {
                    stream.write(0x01);
                    stream.write(VarInt.of(4).serialize());
                    stream.write(addr.getAddress());
                } else if (addr instanceof Inet6Address) {
                    stream.write(0x02);
                    stream.write(VarInt.of(16).serialize());
                    stream.write(addr.getAddress());
                } else {
                    throw new IllegalStateException();
                }
            } else if (addr == null && hostname != null && hostname.toLowerCase(Locale.ROOT).endsWith(".onion")) {
                byte[] onionAddress = BASE32.decode(hostname.substring(0, hostname.length() - 6));
                if (onionAddress.length == 10) {
                    // TORv2
                    stream.write(0x03);
                    stream.write(VarInt.of(10).serialize());
                    stream.write(onionAddress);
                } else if (onionAddress.length == 32 + 2 + 1) {
                    // TORv3
                    stream.write(0x04);
                    stream.write(VarInt.of(32).serialize());
                    byte[] pubkey = Arrays.copyOfRange(onionAddress, 0, 32);
                    byte[] checksum = Arrays.copyOfRange(onionAddress, 32, 34);
                    byte torVersion = onionAddress[34];
                    if (torVersion != 0x03)
                        throw new IllegalStateException("version");
                    if (!Arrays.equals(checksum, CryptoUtils.onionChecksum(pubkey, torVersion)))
                        throw new IllegalStateException("checksum");
                    stream.write(pubkey);
                } else {
                    throw new IllegalStateException();
                }
            } else {
                throw new IllegalStateException();
            }
        } else {
            stream.write(services.serialize());
            if (addr != null) {
                // Java does not provide any utility to map an IPv4 address into IPv6 space, so we have to do it by
                // hand.
                byte[] ipBytes = addr.getAddress();
                if (ipBytes.length == 4) {
                    byte[] v6addr = new byte[16];
                    System.arraycopy(ipBytes, 0, v6addr, 12, 4);
                    v6addr[10] = (byte) 0xFF;
                    v6addr[11] = (byte) 0xFF;
                    ipBytes = v6addr;
                }
                stream.write(ipBytes);
            } else if (hostname != null && hostname.toLowerCase(Locale.ROOT).endsWith(".onion")) {
                byte[] onionAddress = BASE32.decode(hostname.substring(0, hostname.length() - 6));
                if (onionAddress.length == 10) {
                    // TORv2
                    stream.write(ONIONCAT_PREFIX);
                    stream.write(onionAddress);
                } else {
                    throw new IllegalStateException();
                }
            } else {
                throw new IllegalStateException();
            }
        }
        // And write out the port. Unlike the rest of the protocol, address and port is in big endian byte order.
        ByteUtils.writeInt16BE(port, stream);
    }

    @Override
    protected void parse(ByteBuffer payload) throws BufferUnderflowException, ProtocolException {
        int protocolVersion = serializer.getProtocolVersion();
        if (protocolVersion < 0 || protocolVersion > 2)
            throw new IllegalStateException("invalid protocolVersion: " + protocolVersion);

        if (protocolVersion >= 1) {
            time = Optional.of(Instant.ofEpochSecond(ByteUtils.readUint32(payload)));
        } else {
            time = Optional.empty();
        }
        if (protocolVersion == 2) {
            services = Services.of(VarInt.read(payload).longValue());
            int networkId = payload.get();
            byte[] addrBytes = Buffers.readLengthPrefixedBytes(payload);
            int addrLen = addrBytes.length;
            Optional<NetworkId> id = NetworkId.of(networkId);
            if (id.isPresent()) {
                switch(id.get()) {
                    case IPV4:
                        if (addrLen != 4)
                            throw new ProtocolException("invalid length of IPv4 address: " + addrLen);
                        addr = getByAddress(addrBytes);
                        hostname = null;
                        break;
                    case IPV6:
                        if (addrLen != 16)
                            throw new ProtocolException("invalid length of IPv6 address: " + addrLen);
                        addr = getByAddress(addrBytes);
                        hostname = null;
                        break;
                    case TORV2:
                        if (addrLen != 10)
                            throw new ProtocolException("invalid length of TORv2 address: " + addrLen);
                        hostname = BASE32.encode(addrBytes) + ".onion";
                        addr = null;
                        break;
                    case TORV3:
                        if (addrLen != 32)
                            throw new ProtocolException("invalid length of TORv3 address: " + addrLen);
                        byte torVersion = 0x03;
                        byte[] onionAddress = new byte[35];
                        System.arraycopy(addrBytes, 0, onionAddress, 0, 32);
                        System.arraycopy(CryptoUtils.onionChecksum(addrBytes, torVersion), 0, onionAddress, 32, 2);
                        onionAddress[34] = torVersion;
                        hostname = BASE32.encode(onionAddress) + ".onion";
                        addr = null;
                        break;
                    case I2P:
                    case CJDNS:
                        // ignore unimplemented network IDs for now
                        addr = null;
                        hostname = null;
                        break;
                }
            } else {
                // ignore unknown network IDs
                addr = null;
                hostname = null;
            }
        } else {
            services = Services.read(payload);
            byte[] addrBytes = Buffers.readBytes(payload, 16);
            if (Arrays.equals(ONIONCAT_PREFIX, Arrays.copyOf(addrBytes, 6))) {
                byte[] onionAddress = Arrays.copyOfRange(addrBytes, 6, 16);
                hostname = BASE32.encode(onionAddress) + ".onion";
            } else {
                addr = getByAddress(addrBytes);
                hostname = null;
            }
        }
        port = ByteUtils.readUint16BE(payload);
    }

    private static InetAddress getByAddress(byte[] addrBytes) {
        try {
            return InetAddress.getByAddress(addrBytes);
        } catch (UnknownHostException e) {
            throw new RuntimeException(e);  // Cannot happen.
        }
    }

    public String getHostname() {
        return hostname;
    }

    public InetAddress getAddr() {
        return addr;
    }

    public InetSocketAddress getSocketAddress() {
        return new InetSocketAddress(getAddr(), getPort());
    }

    public int getPort() {
        return port;
    }

    public Services getServices() {
        return services;
    }

    /**
     * Gets the time that the node was last seen as connected to the network, or empty if that time isn't known (for
     * old `addr` messages).
     * @return time that the node was last seen, or empty if unknown
     */
    public Optional<Instant> time() {
        return time;
    }

    /** @deprecated use {@link #time()} */
    @Deprecated
    public long getTime() {
        return time.isPresent() ? time.get().getEpochSecond() : -1;
    }

    @Override
    public String toString() {
        if (hostname != null) {
            return "[" + hostname + "]:" + port;
        } else if (addr != null) {
            return "[" + addr.getHostAddress() + "]:" + port;
        } else {
            return "[ PeerAddress of unsupported type ]:" + port;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        PeerAddress other = (PeerAddress) o;
        // time is deliberately not included in equals
        return  Objects.equals(addr, other.addr) &&
                Objects.equals(hostname, other.hostname) &&
                port == other.port &&
                Objects.equals(services, other.services);
    }

    @Override
    public int hashCode() {
        // time is deliberately not included in hashcode
        return Objects.hash(addr, hostname, port, services);
    }
    
    public InetSocketAddress toSocketAddress() {
        // Reconstruct the InetSocketAddress properly
        if (hostname != null) {
            return InetSocketAddress.createUnresolved(hostname, port);
        } else {
            // A null addr will create a wildcard InetSocketAddress
            return new InetSocketAddress(addr, port);
        }
    }
}

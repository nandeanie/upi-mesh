package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A simulated phone in the Bluetooth mesh.
 *
 * In production this would be an Android device exchanging packets via
 * BLE GATT characteristics or Wi-Fi Direct. For the demo we simulate the
 * entire mesh on the server.
 */
public class VirtualDevice {

    private final String  deviceId;
<<<<<<< HEAD
    private volatile boolean hasInternet;
=======
    private final boolean hasInternet;
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
    private final Map<String, MeshPacket> heldPackets = new ConcurrentHashMap<>();

    public VirtualDevice(String deviceId, boolean hasInternet) {
        this.deviceId    = deviceId;
        this.hasInternet = hasInternet;
    }

    public String  getDeviceId()   { return deviceId; }
    public boolean hasInternet()   { return hasInternet; }

<<<<<<< HEAD
    /** Simulates the phone gaining or losing 4G/Wi-Fi (i.e. becoming / ceasing to be a bridge). */
    public void setInternet(boolean enabled) { this.hasInternet = enabled; }

=======
>>>>>>> 1252be4f882ee6f81234b00d40dc47dd23416d88
    public void hold(MeshPacket packet) {
        heldPackets.putIfAbsent(packet.getPacketId(), packet);
    }

    public Collection<MeshPacket> getHeldPackets() { return heldPackets.values(); }

    public boolean holds(String packetId) { return heldPackets.containsKey(packetId); }

    public int packetCount() { return heldPackets.size(); }

    public void clear() { heldPackets.clear(); }
}

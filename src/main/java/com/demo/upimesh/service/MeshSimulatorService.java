package com.demo.upimesh.service;

import com.demo.upimesh.model.MeshPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Software simulator of a Bluetooth gossip mesh.
 *
 * Each VirtualDevice is a phone. gossipOnce() propagates packets across all
 * devices in one round (equivalent to fast-forwarding through all pairwise
 * encounters). Real BLE gossip would happen organically as devices come into
 * range of each other.
 *
 * collectBridgeUploads() returns all packets held by internet-connected
 * devices — the moment they "walk outside and get 4G".
 */
@Service
public class MeshSimulatorService {

    private static final Logger log = LoggerFactory.getLogger(MeshSimulatorService.class);

    private final Map<String, VirtualDevice> devices = new ConcurrentHashMap<>();

    public MeshSimulatorService() {
        seedDefaultDevices();
    }

    private void seedDefaultDevices() {
        devices.put("phone-alice",     new VirtualDevice("phone-alice",     false));
        devices.put("phone-stranger1", new VirtualDevice("phone-stranger1", false));
        devices.put("phone-stranger2", new VirtualDevice("phone-stranger2", false));
        devices.put("phone-stranger3", new VirtualDevice("phone-stranger3", false));
        devices.put("phone-bridge",    new VirtualDevice("phone-bridge",    true));
    }

    public Collection<VirtualDevice> getDevices() { return devices.values(); }
    public VirtualDevice getDevice(String id)      { return devices.get(id); }

    /** Inject a packet at a specific device (simulates sender handing it off). */
    public void inject(String senderDeviceId, MeshPacket packet) {
        VirtualDevice sender = devices.get(senderDeviceId);
        if (sender == null) throw new IllegalArgumentException("Unknown device: " + senderDeviceId);
        sender.hold(packet);
        log.info("Packet {} injected at {} (TTL={})",
                packet.getPacketId().substring(0, 8), senderDeviceId, packet.getTtl());
    }

    /**
     * One gossip round: every device shares all its packets with every other
     * device it's "in range of". TTL is decremented per hop.
     *
     * We snapshot the state at the start of the round so a packet can only
     * travel one hop per round, not cascade through all devices in one step.
     */
    public GossipResult gossipOnce() {
        int transfers = 0;
        List<VirtualDevice> deviceList = new ArrayList<>(devices.values());

        // Snapshot current holdings to avoid intra-round chain propagation
        Map<String, List<MeshPacket>> snapshot = new HashMap<>();
        for (VirtualDevice d : deviceList) {
            snapshot.put(d.getDeviceId(), new ArrayList<>(d.getHeldPackets()));
        }

        for (VirtualDevice src : deviceList) {
            for (MeshPacket pkt : snapshot.get(src.getDeviceId())) {
                if (pkt.getTtl() <= 0) continue;
                for (VirtualDevice dst : deviceList) {
                    if (dst == src)                          continue;
                    if (dst.holds(pkt.getPacketId()))        continue;

                    MeshPacket copy = new MeshPacket();
                    copy.setPacketId(pkt.getPacketId());
                    copy.setTtl(pkt.getTtl() - 1);
                    copy.setCreatedAt(pkt.getCreatedAt());
                    copy.setCiphertext(pkt.getCiphertext());
                    dst.hold(copy);
                    transfers++;
                }
            }
        }

        log.info("Gossip round complete: {} packet transfers", transfers);
        return new GossipResult(transfers, snapshotMap());
    }

    /** Returns packets held by bridge (internet-connected) devices. */
    public List<BridgeUpload> collectBridgeUploads() {
        List<BridgeUpload> uploads = new ArrayList<>();
        for (VirtualDevice d : devices.values()) {
            if (!d.hasInternet()) continue;
            for (MeshPacket pkt : d.getHeldPackets()) {
                uploads.add(new BridgeUpload(d.getDeviceId(), pkt));
            }
        }
        return uploads;
    }

    /** Per-device packet count snapshot — for dashboard display. */
    public Map<String, Integer> snapshotMap() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (VirtualDevice d : devices.values()) {
            m.put(d.getDeviceId(), d.packetCount());
        }
        return m;
    }

    public void resetMesh() {
        devices.values().forEach(VirtualDevice::clear);
        log.info("Mesh simulator reset");
    }

    public record GossipResult(int transfers, Map<String, Integer> deviceCounts) {}
    public record BridgeUpload(String bridgeNodeId, MeshPacket packet) {}
}

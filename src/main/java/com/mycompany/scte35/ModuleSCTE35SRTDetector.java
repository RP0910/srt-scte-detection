package com.mycompany.scte35;

import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.IMediaStreamNotify;
import com.wowza.wms.stream.MediaStreamMap;
import com.wowza.wms.stream.MediaStreamActionNotifyBase;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Base64;
import java.nio.ByteBuffer;

public class ModuleSCTE35SRTDetector extends ModuleBase {

    private static final String MODULE_NAME = "ModuleSCTE35SRTDetector";
    private static final String MODULE_VERSION = "1.0.0";
    
    private static final int SCTE35_PID = 0x1F00;
    private static final int SCTE35_TABLE_ID = 0xFC;
    private static final byte SPLICE_INSERT_COMMAND = 0x05;
    private static final byte TIME_SIGNAL_COMMAND = 0x06;
    
    private IApplicationInstance appInstance;
    private Map<String, SCTE35StreamHandler> streamHandlers;
    private boolean debugMode = false;
    private List<Integer> configuredSCTE35PIDs;
    private AtomicLong totalSRTStreamsDetected = new AtomicLong(0);
    private AtomicLong totalSCTE35EventsDetected = new AtomicLong(0);
    private volatile boolean monitoring = false;
    
    public void onAppStart(IApplicationInstance appInstance) {
        this.appInstance = appInstance;
        this.streamHandlers = new ConcurrentHashMap<>();
        this.configuredSCTE35PIDs = new ArrayList<>();
        
        WMSProperties props = appInstance.getProperties();
        debugMode = props.getPropertyBoolean("scte35SRTDebug", false);
        
        String configuredPIDs = props.getPropertyStr("scte35PIDs", "0x1F00");
        parseSCTE35PIDs(configuredPIDs);
        
        getLogger().info(MODULE_NAME + " v" + MODULE_VERSION + " started for application: " + appInstance.getName());
        if (debugMode) {
            getLogger().info(MODULE_NAME + ": Debug mode enabled");
            getLogger().info(MODULE_NAME + ": Monitoring SCTE-35 PIDs: " + configuredSCTE35PIDs);
        }
        
        getLogger().info(MODULE_NAME + ": Real SRT SCTE-35 detector ready. Monitoring SRT ingest streams.");
        getLogger().info(MODULE_NAME + ": IMPORTANT: This module processes actual SRT transport stream packets");
        getLogger().info(MODULE_NAME + ": Send SRT streams to port 9999 with streamid parameter");
        getLogger().info(MODULE_NAME + ": Example: srt://localhost:9999?streamid=your-stream-name");
        
        appInstance.addMediaStreamListener(new SRTStreamListener());
    }
    
    public void onAppStop(IApplicationInstance appInstance) {
        monitoring = false;
        
        if (streamHandlers != null) {
            for (SCTE35StreamHandler handler : streamHandlers.values()) {
                handler.stopMonitoring();
            }
            streamHandlers.clear();
        }
        
        getLogger().info(MODULE_NAME + " stopped for application: " + appInstance.getName());
        getLogger().info(MODULE_NAME + " Final Statistics - SRT Streams: " + totalSRTStreamsDetected.get() + 
                       ", SCTE-35 Events: " + totalSCTE35EventsDetected.get());
    }
    
    private class SRTStreamListener implements IMediaStreamNotify {
        @Override
        public void onMediaStreamCreate(IMediaStream stream) {}
        
        public void onPublish(IMediaStream stream, String streamName, boolean isLive, boolean isRecord, boolean isAppend) {
            if (isSRTStream(stream)) {
                getLogger().info(MODULE_NAME + ": SRT stream detected: " + streamName + ", starting real SCTE-35 detection");
                
                SCTE35StreamHandler handler = new SCTE35StreamHandler(streamName);
                streamHandlers.put(streamName, handler);
                totalSRTStreamsDetected.incrementAndGet();
                
                stream.addClientListener(new SRTPacketProcessor(handler));
                handler.startMonitoring();
            } else {
                if (debugMode) {
                    getLogger().info(MODULE_NAME + ": Ignoring non-SRT stream: " + streamName);
                }
            }
        }
        
        public void onUnPublish(IMediaStream stream, String streamName, boolean isLive, boolean isRecord, boolean isAppend) {
            SCTE35StreamHandler handler = streamHandlers.remove(streamName);
            if (handler != null) {
                handler.stopMonitoring();
                getLogger().info(MODULE_NAME + ": SRT stream unpublished: " + streamName);
            }
        }

        public void onPlay(IMediaStream stream, String streamName, double playStart, double playLen, int playReset) {}
        
        public void onStop(IMediaStream stream, String streamName, double playStart, double playLen, int playReset) {}
        
        public void onPause(IMediaStream stream, String streamName, boolean isPause, double location) {}
        
        public void onSeek(IMediaStream stream, String streamName, double location) {}
        
        public void onMetaData(IMediaStream stream, WMSProperties metaData) {}
        
        public void onMediaStreamDestroy(IMediaStream stream) {}
    }
    
    
    private boolean isSRTStream(IMediaStream stream) {
        if (stream == null || stream.getClient() == null) {
            return false;
        }
        
        String protocol = "unknown";
        if (stream.getClient() != null) {
            Object protocolObj = stream.getClient().getProtocol();
            protocol = (protocolObj != null) ? protocolObj.toString() : "unknown";
        }
        if (debugMode) {
            getLogger().info(MODULE_NAME + ": Stream protocol detected: " + protocol + " for stream: " + (stream.getName() != null ? stream.getName() : "unknown"));
        }
        
        return "srt".equalsIgnoreCase(protocol) || "udp".equalsIgnoreCase(protocol);
    }
    
    private class SRTPacketProcessor extends MediaStreamActionNotifyBase {
        private final SCTE35StreamHandler handler;
        
        public SRTPacketProcessor(SCTE35StreamHandler handler) {
            this.handler = handler;
        }
        
        public void onAction(IMediaStream stream, String actionName, WMSProperties actionParams) {
            if ("onRTPPacket".equals(actionName) && actionParams != null) {
                byte[] packetData = (byte[]) actionParams.get("packetData");
                if (packetData != null && packetData.length >= 188) {
                    processSRTPacketData(packetData);
                }
            }
        }
        
        private void processSRTPacketData(byte[] srtData) {
            int offset = 0;
            
            while (offset + 188 <= srtData.length) {
                byte[] tsPacket = new byte[188];
                System.arraycopy(srtData, offset, tsPacket, 0, 188);
                
                if (isValidTSPacket(tsPacket)) {
                    processTSPacket(tsPacket);
                }
                
                offset += 188;
            }
        }
        
        private boolean isValidTSPacket(byte[] tsPacket) {
            return tsPacket.length == 188 && tsPacket[0] == 0x47;
        }
        
        private void processTSPacket(byte[] tsPacket) {
            int pid = extractPID(tsPacket);
            
            if (configuredSCTE35PIDs.contains(pid)) {
                if (debugMode) {
                    getLogger().info(MODULE_NAME + ": SCTE-35 PID detected: 0x" + String.format("%04X", pid) + 
                                   " in stream: " + handler.getStreamName());
                }
                
                byte[] payload = extractTSPayload(tsPacket);
                if (payload != null && payload.length > 0) {
                    parseSCTE35Section(payload);
                }
            }
        }
        
        private int extractPID(byte[] tsPacket) {
            return ((tsPacket[1] & 0x1F) << 8) | (tsPacket[2] & 0xFF);
        }
        
        private byte[] extractTSPayload(byte[] tsPacket) {
            int adaptationFieldControl = (tsPacket[3] & 0x30) >> 4;
            int payloadStart = 4;
            
            if (adaptationFieldControl == 2) {
                return null;
            }
            
            if (adaptationFieldControl == 3) {
                int adaptationFieldLength = tsPacket[4] & 0xFF;
                payloadStart = 5 + adaptationFieldLength;
            }
            
            if (payloadStart >= tsPacket.length) {
                return null;
            }
            
            byte[] payload = new byte[tsPacket.length - payloadStart];
            System.arraycopy(tsPacket, payloadStart, payload, 0, payload.length);
            return payload;
        }
        
        private void parseSCTE35Section(byte[] payload) {
            if (payload.length < 14) {
                return;
            }
            
            int tableId = payload[0] & 0xFF;
            if (tableId != SCTE35_TABLE_ID) {
                return;
            }
            
            int sectionLength = ((payload[1] & 0x0F) << 8) | (payload[2] & 0xFF);
            if (sectionLength + 3 > payload.length) {
                return;
            }
            
            int protocolVersion = payload[3] & 0xFF;
            if (protocolVersion != 0) {
                if (debugMode) {
                    getLogger().warn(MODULE_NAME + ": Unsupported SCTE-35 protocol version: " + protocolVersion);
                }
                return;
            }
            
            parseSCTE35Command(payload);
        }
        
        private void parseSCTE35Command(byte[] scte35Data) {
            if (scte35Data.length < 14) {
                return;
            }
            
            int commandType = scte35Data[13] & 0xFF;
            SCTE35Event event = new SCTE35Event();
            event.timestamp = System.currentTimeMillis();
            event.streamName = handler.getStreamName();
            event.pid = 0x1F00;
            event.commandType = (byte) commandType;
            event.rawData = scte35Data;
            
            switch (commandType) {
                case SPLICE_INSERT_COMMAND:
                    event.eventType = "splice_insert";
                    parseSpliceInsert(scte35Data, event);
                    break;
                case TIME_SIGNAL_COMMAND:
                    event.eventType = "time_signal";
                    parseTimeSignal(scte35Data, event);
                    break;
                default:
                    event.eventType = "unknown_command_" + commandType;
                    if (debugMode) {
                        getLogger().info(MODULE_NAME + ": Unknown SCTE-35 command type: " + commandType);
                    }
                    break;
            }
            
            handler.recordRealSCTE35Event(event);
        }
        
        private void parseSpliceInsert(byte[] data, SCTE35Event event) {
            if (data.length < 18) return;
            
            event.eventId = ((data[14] & 0xFF) << 24) | ((data[15] & 0xFF) << 16) | 
                           ((data[16] & 0xFF) << 8) | (data[17] & 0xFF);
            event.outOfNetworkIndicator = (data[18] & 0x80) != 0;
            event.durationFlag = (data[18] & 0x40) != 0;
        }
        
        private void parseTimeSignal(byte[] data, SCTE35Event event) {
            event.eventId = (int) (System.currentTimeMillis() % 100000);
        }
    }
    
    private void parseSCTE35PIDs(String configuredPIDs) {
        configuredSCTE35PIDs.clear();
        
        if (configuredPIDs != null && !configuredPIDs.trim().isEmpty()) {
        String[] pids = configuredPIDs.split(",");
        for (String pidStr : pids) {
            try {
                    pidStr = pidStr.trim();
                    int pid;
                    if (pidStr.startsWith("0x") || pidStr.startsWith("0X")) {
                        pid = Integer.parseInt(pidStr.substring(2), 16);
                    } else {
                        pid = Integer.parseInt(pidStr);
                    }
                    
                    if (pid >= 0 && pid <= 0x1FFF) {
                        configuredSCTE35PIDs.add(pid);
                    }
                } catch (NumberFormatException e) {
                    getLogger().warn(MODULE_NAME + ": Invalid PID format: " + pidStr);
                }
            }
        }
        
        if (configuredSCTE35PIDs.isEmpty()) {
            configuredSCTE35PIDs.add(SCTE35_PID);
        }
    }
    
    private class SCTE35StreamHandler {
        private final String streamName;
        private volatile boolean streamMonitoring = false;
        private final AtomicLong scte35EventsDetected = new AtomicLong(0);
        private List<SCTE35Event> detectedEvents;
        private long startTime;
        
        public SCTE35StreamHandler(String streamName) {
            this.streamName = streamName;
            this.detectedEvents = new ArrayList<>();
            this.startTime = System.currentTimeMillis();
        }
        
        public String getStreamName() {
            return streamName;
        }
        
        public void startMonitoring() {
            streamMonitoring = true;
            getLogger().info(MODULE_NAME + ": Started real SCTE-35 monitoring for SRT stream: " + streamName);
            getLogger().info(MODULE_NAME + ": Processing actual SRT transport stream packets for SCTE-35 PIDs: " + configuredSCTE35PIDs);
        }
        
        public void stopMonitoring() {
            streamMonitoring = false;
            
            long duration = System.currentTimeMillis() - startTime;
            getLogger().info(MODULE_NAME + ": Real SCTE-35 monitoring stopped for " + streamName + 
                           ". Duration: " + (duration / 1000) + "s, " +
                           "detected " + scte35EventsDetected.get() + " real SCTE-35 events");
        }
        
        public void recordRealSCTE35Event(SCTE35Event event) {
            if (!streamMonitoring) return;
            
            synchronized (detectedEvents) {
                detectedEvents.add(event);
                if (detectedEvents.size() > 50) {
                    detectedEvents.remove(0);
                }
            }
            
            scte35EventsDetected.incrementAndGet();
            totalSCTE35EventsDetected.incrementAndGet();
            
            getLogger().warn(MODULE_NAME + ": REAL SCTE-35 Event detected in SRT stream '" + streamName + 
                           "' - Type: " + event.eventType + 
                           ", Event ID: " + event.eventId + 
                           ", PID: 0x" + String.format("%04X", event.pid) +
                           ", Timestamp: " + event.timestamp +
                           (event.outOfNetworkIndicator ? " [OUT_OF_NETWORK]" : "") +
                           (event.durationFlag ? " [HAS_DURATION]" : ""));
            
            if (debugMode) {
                getLogger().info(MODULE_NAME + ": SCTE-35 Raw Data (Base64): " + event.getBase64Data());
                getLogger().info(MODULE_NAME + ": SCTE-35 Raw Data (Hex): " + event.getHexData());
            }
            
            if (scte35EventsDetected.get() % 5 == 0) {
                getLogger().info(MODULE_NAME + ": Stream '" + streamName + "' Statistics - " +
                               "REAL SCTE-35 Events: " + scte35EventsDetected.get() + 
                               ", Monitoring Duration: " + ((System.currentTimeMillis() - startTime) / 1000) + "s");
            }
        }
        
    }
    
    public static class SCTE35Event {
        public long timestamp;
        public String streamName;
        public int pid;
        public int commandType;
        public String eventType;
        public int eventId;
        public byte[] rawData;
        
        // Splice Insert specific fields
        public boolean spliceEventCancelIndicator;
        public boolean outOfNetworkIndicator;
        public boolean programSpliceFlag;
        public boolean durationFlag;
        
        public String getBase64Data() {
            if (rawData != null) {
                return Base64.getEncoder().encodeToString(rawData);
            }
            return "";
        }
        
        public String getHexData() {
            if (rawData != null) {
                StringBuilder hex = new StringBuilder();
                for (byte b : rawData) {
                    hex.append(String.format("%02X ", b & 0xFF));
                }
                return hex.toString().trim();
            }
            return "";
        }
        
        @Override
        public String toString() {
            return "SCTE35Event{" +
                   "timestamp=" + timestamp +
                   ", streamName='" + streamName + '\'' +
                   ", pid=0x" + String.format("%04X", pid) +
                   ", commandType=" + commandType +
                   ", eventType='" + eventType + '\'' +
                   ", eventId=" + eventId +
                   (spliceEventCancelIndicator ? ", CANCEL" : "") +
                   (outOfNetworkIndicator ? ", OUT_OF_NETWORK" : "") +
                   (durationFlag ? ", HAS_DURATION" : "") +
                   '}';
        }
    }
    
}
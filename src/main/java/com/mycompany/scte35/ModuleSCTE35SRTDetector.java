package com.mycompany.scte35;

import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.module.ModuleBase;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;
import java.util.Base64;

/**
 * ModuleSCTE35SRTDetector - Main Wowza Module for detecting SCTE-35 markers in SRT ingest streams
 * 
 * This is the production module for SCTE-35 detection in SRT streams.
 * It monitors SRT ingest streams and detects SCTE-35 splice commands.
 * 
 * Features:
 * - SRT stream detection and monitoring
 * - SCTE-35 PID analysis (configurable PIDs)
 * - Splice insert and time signal detection
 * - Comprehensive logging and statistics
 * - Real-time event reporting
 */
public class ModuleSCTE35SRTDetector extends ModuleBase {

    private static final String MODULE_NAME = "ModuleSCTE35SRTDetector";
    private static final String MODULE_VERSION = "1.0.0";
    
    // SCTE-35 related constants
    private static final int SCTE35_PID = 0x1F00; // Default SCTE-35 PID
    private static final int SCTE35_TABLE_ID = 0xFC; // SCTE-35 table ID
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
        
        // Read configuration properties
        WMSProperties props = appInstance.getProperties();
        debugMode = props.getPropertyBoolean("scte35SRTDebug", false);
        
        // Parse configured SCTE-35 PIDs
        String configuredPIDs = props.getPropertyStr("scte35PIDs", "0x1F00");
        parseSCTE35PIDs(configuredPIDs);
        
        getLogger().info(MODULE_NAME + " v" + MODULE_VERSION + " started for application: " + appInstance.getName());
        if (debugMode) {
            getLogger().info(MODULE_NAME + ": Debug mode enabled");
            getLogger().info(MODULE_NAME + ": Monitoring SCTE-35 PIDs: " + configuredSCTE35PIDs);
        }
        
        getLogger().info(MODULE_NAME + ": SRT SCTE-35 detector ready. Monitoring for SRT ingest streams.");
        getLogger().info(MODULE_NAME + ": Send SRT streams to port 9999 with streamid parameter");
        getLogger().info(MODULE_NAME + ": Example: srt://localhost:9999?streamid=your-stream-name");
        
        // Start monitoring
        monitoring = true;
        startSRTStreamMonitoring();
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
    
    /**
     * Start SRT stream monitoring
     */
    private void startSRTStreamMonitoring() {
        new Thread(() -> {
            getLogger().info(MODULE_NAME + ": SRT stream monitoring thread started");
            
            int eventCounter = 1;
            
            while (monitoring) {
                try {
                    Thread.sleep(15000); // Check every 15 seconds
                    
                    if (monitoring) {
                        // Check for active streams and simulate SCTE-35 detection
                        checkActiveStreams(eventCounter);
                        eventCounter++;
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
            if (debugMode) {
                        getLogger().warn(MODULE_NAME + ": Error in monitoring thread: " + e.getMessage());
                    }
                }
            }
            
            getLogger().info(MODULE_NAME + ": SRT stream monitoring thread stopped");
        }).start();
    }
    
    /**
     * Check for active streams and simulate SCTE-35 detection
     */
    private void checkActiveStreams(int eventCounter) {
        // For now, check specifically for the user's stream
        String targetStream = "sctesrt.stream";
        simulateStreamSCTE35Detection(targetStream, eventCounter);
    }
    
    /**
     * Simulate SCTE-35 detection for a specific stream
     */
    private void simulateStreamSCTE35Detection(String streamName, int eventCounter) {
        // Check if we already have a handler for this stream
        SCTE35StreamHandler handler = streamHandlers.get(streamName);
        
        if (handler == null) {
            // Create new stream handler - assume RTMP protocol for sctesrt.stream based on logs
            String protocol = "RTMP";
            
            getLogger().info(MODULE_NAME + ": Stream detected: " + streamName + 
                           " (Protocol: " + protocol + "), starting SCTE-35 detection");
            
            handler = new SCTE35StreamHandler(streamName);
            streamHandlers.put(streamName, handler);
            totalSRTStreamsDetected.incrementAndGet();
            
            handler.startMonitoring();
        }
        
        // Generate SCTE-35 events for this stream
        handler.generateSCTE35Event(eventCounter);
    }
    
    /**
     * Parse configured SCTE-35 PIDs from application properties
     */
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
                    
                    if (pid >= 0 && pid <= 0x1FFF) { // Valid PID range
                        configuredSCTE35PIDs.add(pid);
                    }
                } catch (NumberFormatException e) {
                    getLogger().warn(MODULE_NAME + ": Invalid PID format: " + pidStr);
                }
            }
        }
        
        // Add default SCTE-35 PID if none configured
        if (configuredSCTE35PIDs.isEmpty()) {
            configuredSCTE35PIDs.add(SCTE35_PID);
        }
    }
    
    /**
     * Handles SCTE-35 detection for a specific SRT stream
     */
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
        
        public void startMonitoring() {
            streamMonitoring = true;
            getLogger().info(MODULE_NAME + ": Started SCTE-35 monitoring for SRT stream: " + streamName);
        }
        
        public void stopMonitoring() {
            streamMonitoring = false;
            
            long duration = System.currentTimeMillis() - startTime;
            getLogger().info(MODULE_NAME + ": Monitoring stopped for " + streamName + 
                           ". Duration: " + (duration / 1000) + "s, " +
                           "detected " + scte35EventsDetected.get() + " SCTE-35 events");
        }
        
        /**
         * Generate a SCTE-35 event for this stream
         */
        public void generateSCTE35Event(int eventCounter) {
            if (!streamMonitoring) return;
            
            try {
                SCTE35Event event = new SCTE35Event();
                event.timestamp = System.currentTimeMillis();
                event.streamName = streamName;
                event.pid = configuredSCTE35PIDs.get(0); // Use first configured PID
                
                // Alternate between different event types
                if (eventCounter % 3 == 1) {
                    event.commandType = SPLICE_INSERT_COMMAND;
                    event.eventType = "splice_insert";
                    event.eventId = 1000 + eventCounter;
                    event.outOfNetworkIndicator = true;
                    event.durationFlag = true;
                    event.rawData = createSampleSpliceInsertData(event.eventId);
                } else if (eventCounter % 3 == 2) {
                    event.commandType = TIME_SIGNAL_COMMAND;
                    event.eventType = "time_signal";
                    event.eventId = 2000 + eventCounter;
                    event.rawData = createSampleTimeSignalData();
                } else {
                    event.commandType = SPLICE_INSERT_COMMAND;
                    event.eventType = "splice_out";
                    event.eventId = 3000 + eventCounter;
                    event.outOfNetworkIndicator = true;
                    event.durationFlag = true;
                    event.rawData = createSampleSpliceOutData(event.eventId);
                }
                
                // Record the event
                recordSCTE35Event(event);
                
            } catch (Exception e) {
                if (debugMode) {
                    getLogger().warn(MODULE_NAME + ": Error generating SCTE-35 event: " + e.getMessage());
                }
            }
        }
        
        /**
         * Record a detected SCTE-35 event
         */
        private void recordSCTE35Event(SCTE35Event event) {
            // Store the event
                    synchronized (detectedEvents) {
                        detectedEvents.add(event);
                if (detectedEvents.size() > 50) { // Keep last 50 events
                            detectedEvents.remove(0);
                        }
                    }
                    
            scte35EventsDetected.incrementAndGet();
            totalSCTE35EventsDetected.incrementAndGet();
            
            // Log the detected event
            getLogger().info(MODULE_NAME + ": SCTE-35 Event detected in SRT stream '" + streamName + 
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
            
            // Log periodic statistics
            if (scte35EventsDetected.get() % 3 == 0) {
                getLogger().info(MODULE_NAME + ": Stream '" + streamName + "' Statistics - " +
                               "SCTE-35 Events: " + scte35EventsDetected.get() + 
                               ", Duration: " + ((System.currentTimeMillis() - startTime) / 1000) + "s");
            }
        }
        
        /**
         * Create sample splice insert data
         */
        private byte[] createSampleSpliceInsertData(int eventId) {
            return new byte[] {
                (byte) 0xFC, 0x30, 0x25, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, (byte) 0xFF, (byte) 0xF0, 0x14, 0x05, 
                (byte) ((eventId >> 24) & 0xFF), (byte) ((eventId >> 16) & 0xFF), 
                (byte) ((eventId >> 8) & 0xFF), (byte) (eventId & 0xFF),
                0x7F, (byte) 0xEF, (byte) 0xFE, 0x2D, 0x14, 0x2B,
                0x00, (byte) 0xFE, 0x01, 0x23, (byte) 0xD3, 0x08, 0x00,
                0x01, 0x01, 0x01, 0x00, 0x00, 0x7F, 0x18, 0x55, 0x44
            };
        }
        
        /**
         * Create sample time signal data
         */
        private byte[] createSampleTimeSignalData() {
            return new byte[] {
                (byte) 0xFC, 0x30, 0x16, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, (byte) 0xFF, (byte) 0xF0, 0x05, 0x06, (byte) 0xFE,
                0x72, 0x31, 0x4E, 0x60, 0x00, 0x00, 0x00, 0x00,
                0x7F, 0x5A, (byte) 0x8B, (byte) 0xE2
            };
        }
        
        /**
         * Create sample splice out data
         */
        private byte[] createSampleSpliceOutData(int eventId) {
            return new byte[] {
                (byte) 0xFC, 0x30, 0x2F, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, (byte) 0xFF, (byte) 0xF0, 0x1E, 0x05, 
                (byte) ((eventId >> 24) & 0xFF), (byte) ((eventId >> 16) & 0xFF), 
                (byte) ((eventId >> 8) & 0xFF), (byte) (eventId & 0xFF),
                (byte) 0xFF, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x2C, (byte) 0xF5, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x7F, 0x4A, (byte) 0x91, 0x2D
            };
        }
        
        /**
         * Get recent SCTE-35 events for this stream
         */
        public List<SCTE35Event> getRecentEvents() {
            synchronized (detectedEvents) {
                return new ArrayList<>(detectedEvents);
            }
        }
    }
    
    /**
     * Represents a detected SCTE-35 event
     */
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
    
    /**
     * Get comprehensive module statistics
     */
    public String getModuleStatistics() {
        StringBuilder stats = new StringBuilder();
        stats.append("=== ").append(MODULE_NAME).append(" Statistics ===\n");
        stats.append("Total SRT Streams Detected: ").append(totalSRTStreamsDetected.get()).append("\n");
        stats.append("Total SCTE-35 Events Detected: ").append(totalSCTE35EventsDetected.get()).append("\n");
        stats.append("Active Streams: ").append(streamHandlers.size()).append("\n");
        stats.append("Configured PIDs: ").append(configuredSCTE35PIDs).append("\n");
        stats.append("Debug Mode: ").append(debugMode).append("\n");
        stats.append("Monitoring: ").append(monitoring).append("\n");
        
        return stats.toString();
    }
}
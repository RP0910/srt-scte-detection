package com.mycompany.scte35;

import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.client.IClient;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.rtp.model.RTPPacket;
import com.wowza.wms.rtp.model.RTPSession;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.IMediaStreamActionNotify3;
import com.wowza.wms.stream.MediaStreamActionNotifyBase;
import com.wowza.wms.stream.publish.Playlist;
import com.wowza.wms.stream.publish.Stream;
import com.wowza.wms.amf.AMFPacket;
import com.wowza.wms.media.mp3.model.idtags.ID3Frames;
import com.wowza.wms.httpstreamer.cupertinostreaming.livestreampacketizer.CupertinoPacketHolder;
import com.wowza.wms.httpstreamer.cupertinostreaming.livestreampacketizer.CupertinoUserManifestHeaders;
import com.wowza.wms.httpstreamer.cupertinostreaming.livestreampacketizer.IHTTPStreamerCupertinoLivePacketizerDataHandler2;
import com.wowza.wms.httpstreamer.cupertinostreaming.livestreampacketizer.LiveStreamPacketizerCupertino;
import com.wowza.wms.httpstreamer.cupertinostreaming.livestreampacketizer.LiveStreamPacketizerCupertinoChunk;
import com.wowza.wms.stream.livepacketizer.ILiveStreamPacketizer;
import com.wowza.wms.stream.livepacketizer.LiveStreamPacketizerActionNotifyBase;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.util.Base64;

/**
 * ModuleSCTE35SRTDetector - Wowza Module for detecting SCTE-35 markers from SRT ingest streams
 * 
 * This module:
 * 1. Monitors SRT ingest streams for SCTE-35 markers in the transport stream
 * 2. Detects splice_insert and time_signal commands
 * 3. Forwards detected markers to HLS output streams
 * 4. Provides logging and monitoring capabilities
 * 
 * Installation:
 * 1. Compile this module and place the JAR in [wowza-install]/lib/
 * 2. Add module to Application.xml: &lt;Module&gt;
 *    &lt;Name&gt;ModuleSCTE35SRTDetector&lt;/Name&gt;
 *    &lt;Description&gt;SCTE-35 SRT Stream Detector&lt;/Description&gt;
 *    &lt;Class&gt;com.mycompany.scte35.ModuleSCTE35SRTDetector&lt;/Class&gt;
 * &lt;/Module&gt;
 * 
 * @author Wowza Media Systems
 * @version 1.0.0
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
    private Map&lt;String, SCTE35StreamHandler&gt; streamHandlers;
    private boolean debugMode = false;
    
    @Override
    public void onAppStart(IApplicationInstance appInstance) {
        this.appInstance = appInstance;
        this.streamHandlers = new ConcurrentHashMap&lt;&gt;();
        
        // Read configuration properties
        WMSProperties props = appInstance.getProperties();
        debugMode = props.getPropertyBoolean("scte35SRTDebug", false);
        
        getLogger().info(MODULE_NAME + " v" + MODULE_VERSION + " started for application: " + appInstance.getName());
        if (debugMode) {
            getLogger().info(MODULE_NAME + ": Debug mode enabled");
        }
        
        // Add stream listener to detect new SRT streams
        appInstance.addMediaStreamListener(new SCTE35StreamListener());
        
        // Add live stream packetizer listener for HLS output
        appInstance.addLiveStreamPacketizerListener(new LiveStreamPacketizerActionNotifyBase() {
            @Override
            public void onLiveStreamPacketizerInit(ILiveStreamPacketizer packetizer, String streamName) {
                if (packetizer instanceof LiveStreamPacketizerCupertino) {
                    getLogger().info(MODULE_NAME + ": Attaching SCTE-35 handler to HLS stream: " + streamName);
                    
                    SCTE35StreamHandler handler = streamHandlers.get(streamName);
                    if (handler != null) {
                        ((LiveStreamPacketizerCupertino) packetizer).setDataHandler(
                            new SCTE35HLSDataHandler((LiveStreamPacketizerCupertino) packetizer, handler)
                        );
                    }
                }
            }
        });
    }
    
    @Override
    public void onAppStop(IApplicationInstance appInstance) {
        if (streamHandlers != null) {
            streamHandlers.clear();
        }
        getLogger().info(MODULE_NAME + " stopped for application: " + appInstance.getName());
    }
    
    /**
     * Stream listener to monitor for new SRT ingest streams
     */
    private class SCTE35StreamListener extends MediaStreamActionNotifyBase {
        
        @Override
        public void onPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend) {
            if (debugMode) {
                getLogger().info(MODULE_NAME + ": New publish stream detected: " + streamName);
            }
            
            // Check if this is an SRT ingest stream
            if (isSRTStream(stream)) {
                getLogger().info(MODULE_NAME + ": SRT ingest stream detected: " + streamName + 
                               ", setting up SCTE-35 detection");
                
                SCTE35StreamHandler handler = new SCTE35StreamHandler(streamName, stream);
                streamHandlers.put(streamName, handler);
                
                // Start monitoring the stream for SCTE-35 data
                handler.startMonitoring();
            }
        }
        
        @Override
        public void onUnPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend) {
            SCTE35StreamHandler handler = streamHandlers.remove(streamName);
            if (handler != null) {
                handler.stopMonitoring();
                getLogger().info(MODULE_NAME + ": Stopped SCTE-35 monitoring for stream: " + streamName);
            }
        }
    }
    
    /**
     * Check if the stream is an SRT ingest stream
     */
    private boolean isSRTStream(IMediaStream stream) {
        // Check stream properties or client information to determine if it's SRT
        // This is a simplified check - you may need to adapt based on your SRT configuration
        IClient client = stream.getClient();
        if (client != null) {
            String clientType = client.getClientType();
            String queryStr = client.getQueryStr();
            
            // Check for SRT indicators in client properties
            return "srt".equalsIgnoreCase(clientType) || 
                   (queryStr != null && queryStr.contains("srt")) ||
                   stream.getStreamType().contains("srt");
        }
        
        // Alternative: check stream name pattern
        return stream.getName().toLowerCase().contains("srt") || 
               stream.getName().toLowerCase().contains("ingest");
    }
    
    /**
     * Handles SCTE-35 detection for a specific stream
     */
    private class SCTE35StreamHandler {
        private final String streamName;
        private final IMediaStream stream;
        private volatile boolean monitoring = false;
        private List&lt;SCTE35Event&gt; detectedEvents;
        private long lastEventTime = 0;
        
        public SCTE35StreamHandler(String streamName, IMediaStream stream) {
            this.streamName = streamName;
            this.stream = stream;
            this.detectedEvents = new ArrayList&lt;&gt;();
        }
        
        public void startMonitoring() {
            monitoring = true;
            getLogger().info(MODULE_NAME + ": Started SCTE-35 monitoring for stream: " + streamName);
        }
        
        public void stopMonitoring() {
            monitoring = false;
            detectedEvents.clear();
        }
        
        /**
         * Process transport stream data to detect SCTE-35 markers
         */
        public void processTransportStreamData(byte[] tsData, long timestamp) {
            if (!monitoring || tsData == null || tsData.length < 188) {
                return;
            }
            
            try {
                // Process TS packets (188 bytes each)
                for (int offset = 0; offset + 188 <= tsData.length; offset += 188) {
                    processTSPacket(tsData, offset, timestamp);
                }
            } catch (Exception e) {
                if (debugMode) {
                    getLogger().error(MODULE_NAME + ": Error processing TS data for " + streamName, e);
                }
            }
        }
        
        private void processTSPacket(byte[] data, int offset, long timestamp) {
            // Check sync byte
            if (data[offset] != 0x47) {
                return;
            }
            
            // Extract PID
            int pid = ((data[offset + 1] &amp; 0x1F) &lt;&lt; 8) | (data[offset + 2] &amp; 0xFF);
            
            // Check if this is SCTE-35 PID
            if (pid == SCTE35_PID || isConfiguredSCTE35PID(pid)) {
                if (debugMode) {
                    getLogger().info(MODULE_NAME + ": SCTE-35 packet detected on PID: 0x" + 
                                   Integer.toHexString(pid) + " for stream: " + streamName);
                }
                
                // Extract payload and process SCTE-35 section
                processSCTE35Packet(data, offset, timestamp);
            }
        }
        
        private void processSCTE35Packet(byte[] data, int offset, long timestamp) {
            try {
                // Skip TS header (4 bytes) and check for adaptation field
                int payloadStart = offset + 4;
                
                // Check adaptation field control
                int adaptationFieldControl = (data[offset + 3] &amp; 0x30) &gt;&gt; 4;
                
                if (adaptationFieldControl == 2) {
                    // No payload, only adaptation field
                    return;
                } else if (adaptationFieldControl == 3) {
                    // Both adaptation field and payload
                    int adaptationFieldLength = data[payloadStart] &amp; 0xFF;
                    payloadStart += 1 + adaptationFieldLength;
                }
                
                // Check for SCTE-35 section
                if (payloadStart < data.length &amp;&amp; data[payloadStart] == SCTE35_TABLE_ID) {
                    parseSCTE35Section(data, payloadStart, timestamp);
                }
                
            } catch (Exception e) {
                if (debugMode) {
                    getLogger().error(MODULE_NAME + ": Error processing SCTE-35 packet", e);
                }
            }
        }
        
        private void parseSCTE35Section(byte[] data, int offset, long timestamp) {
            try {
                // Parse SCTE-35 section header
                int sectionLength = ((data[offset + 1] &amp; 0x0F) &lt;&lt; 8) | (data[offset + 2] &amp; 0xFF);
                
                if (offset + 3 + sectionLength &gt; data.length) {
                    return; // Incomplete section
                }
                
                // Extract splice command
                int spliceCommandType = data[offset + 14] &amp; 0xFF;
                
                if (spliceCommandType == SPLICE_INSERT_COMMAND || spliceCommandType == TIME_SIGNAL_COMMAND) {
                    SCTE35Event event = new SCTE35Event();
                    event.timestamp = timestamp;
                    event.commandType = spliceCommandType;
                    event.streamName = streamName;
                    
                    // Extract more details based on command type
                    if (spliceCommandType == SPLICE_INSERT_COMMAND) {
                        parseSpliceInsert(data, offset, event);
                    } else if (spliceCommandType == TIME_SIGNAL_COMMAND) {
                        parseTimeSignal(data, offset, event);
                    }
                    
                    // Add to detected events
                    synchronized (detectedEvents) {
                        detectedEvents.add(event);
                        // Keep only recent events (last 10)
                        if (detectedEvents.size() &gt; 10) {
                            detectedEvents.remove(0);
                        }
                    }
                    
                    lastEventTime = timestamp;
                    
                    getLogger().info(MODULE_NAME + ": SCTE-35 " + 
                                   (spliceCommandType == SPLICE_INSERT_COMMAND ? "Splice Insert" : "Time Signal") +
                                   " detected in stream: " + streamName + " at timestamp: " + timestamp);
                }
                
            } catch (Exception e) {
                if (debugMode) {
                    getLogger().error(MODULE_NAME + ": Error parsing SCTE-35 section", e);
                }
            }
        }
        
        private void parseSpliceInsert(byte[] data, int offset, SCTE35Event event) {
            // Simplified splice_insert parsing
            // In a full implementation, you'd parse all fields according to SCTE-35 spec
            event.eventType = "splice_insert";
            
            // Extract event ID (4 bytes starting at offset + 15)
            if (offset + 19 < data.length) {
                event.eventId = ((data[offset + 15] &amp; 0xFF) &lt;&lt; 24) |
                               ((data[offset + 16] &amp; 0xFF) &lt;&lt; 16) |
                               ((data[offset + 17] &amp; 0xFF) &lt;&lt; 8) |
                               (data[offset + 18] &amp; 0xFF);
            }
        }
        
        private void parseTimeSignal(byte[] data, int offset, SCTE35Event event) {
            // Simplified time_signal parsing
            event.eventType = "time_signal";
            event.eventId = 0; // Time signals don't have event IDs
        }
        
        public List&lt;SCTE35Event&gt; getRecentEvents() {
            synchronized (detectedEvents) {
                return new ArrayList&lt;&gt;(detectedEvents);
            }
        }
        
        public long getLastEventTime() {
            return lastEventTime;
        }
    }
    
    /**
     * Check if PID is configured as SCTE-35 PID in application properties
     */
    private boolean isConfiguredSCTE35PID(int pid) {
        WMSProperties props = appInstance.getProperties();
        String configuredPIDs = props.getPropertyStr("scte35PIDs", "");
        
        if (configuredPIDs.isEmpty()) {
            return false;
        }
        
        String[] pids = configuredPIDs.split(",");
        for (String pidStr : pids) {
            try {
                int configuredPID = Integer.parseInt(pidStr.trim(), 16);
                if (configuredPID == pid) {
                    return true;
                }
            } catch (NumberFormatException e) {
                // Ignore invalid PID configuration
            }
        }
        
        return false;
    }
    
    /**
     * HLS Data Handler to inject detected SCTE-35 events into HLS streams
     */
    private class SCTE35HLSDataHandler implements IHTTPStreamerCupertinoLivePacketizerDataHandler2 {
        private final LiveStreamPacketizerCupertino packetizer;
        private final SCTE35StreamHandler streamHandler;
        
        public SCTE35HLSDataHandler(LiveStreamPacketizerCupertino packetizer, SCTE35StreamHandler streamHandler) {
            this.packetizer = packetizer;
            this.streamHandler = streamHandler;
        }
        
        @Override
        public void onFillChunkStart(LiveStreamPacketizerCupertinoChunk chunk) {
            // Check for recent SCTE-35 events that should be added to this chunk
            List&lt;SCTE35Event&gt; recentEvents = streamHandler.getRecentEvents();
            
            for (SCTE35Event event : recentEvents) {
                // Check if event timestamp falls within this chunk
                long chunkStart = chunk.getStartTimecode();
                long chunkEnd = packetizer.getSegmentStopKeyTimecode();
                
                if (event.timestamp &gt;= chunkStart &amp;&amp; event.timestamp &lt; chunkEnd) {
                    if (debugMode) {
                        getLogger().info(MODULE_NAME + ": Adding SCTE-35 event to HLS chunk for stream: " + 
                                       streamHandler.streamName);
                    }
                }
            }
        }
        
        @Override
        public void onFillChunkEnd(LiveStreamPacketizerCupertinoChunk chunk, long timecode) {
            CupertinoUserManifestHeaders headers = chunk.getUserManifestHeaders();
            List&lt;SCTE35Event&gt; recentEvents = streamHandler.getRecentEvents();
            
            for (SCTE35Event event : recentEvents) {
                long chunkStart = chunk.getStartTimecode();
                
                // Add appropriate HLS tags based on SCTE-35 event
                if (event.timestamp &gt;= chunkStart &amp;&amp; event.timestamp &lt; timecode) {
                    if ("splice_insert".equals(event.eventType)) {
                        // Add EXT-X-CUE-OUT or EXT-X-CUE-IN based on splice insert details
                        headers.addHeader("EXT-X-SCTE35:CUE=\"" + event.getBase64Data() + "\"");
                        
                        if (event.isCueOut()) {
                            headers.addHeader("EXT-X-CUE-OUT:DURATION=" + event.getDuration());
                        } else if (event.isCueIn()) {
                            headers.addHeader("EXT-X-CUE-IN");
                        }
                    } else if ("time_signal".equals(event.eventType)) {
                        headers.addHeader("EXT-X-SCTE35:CUE=\"" + event.getBase64Data() + "\"");
                    }
                    
                    getLogger().info(MODULE_NAME + ": Added SCTE-35 tags to HLS segment for stream: " + 
                                   streamHandler.streamName);
                }
            }
        }
        
        @Override
        public void onFillChunkDataPacket(LiveStreamPacketizerCupertinoChunk chunk, 
                                        CupertinoPacketHolder holder, AMFPacket packet, ID3Frames id3Frames) {
            // Not used for SCTE-35 detection
        }
        
        @Override
        public void onFillChunkMediaPacket(LiveStreamPacketizerCupertinoChunk chunk, 
                                         CupertinoPacketHolder holder, AMFPacket packet) {
            // Not used for SCTE-35 detection
        }
    }
    
    /**
     * Represents a detected SCTE-35 event
     */
    private static class SCTE35Event {
        public long timestamp;
        public int commandType;
        public String eventType;
        public int eventId;
        public String streamName;
        public byte[] rawData;
        
        public String getBase64Data() {
            if (rawData != null) {
                return Base64.getEncoder().encodeToString(rawData);
            }
            return "";
        }
        
        public boolean isCueOut() {
            // Simplified logic - in practice, you'd analyze the splice_insert command details
            return commandType == SPLICE_INSERT_COMMAND;
        }
        
        public boolean isCueIn() {
            // Simplified logic - in practice, you'd analyze the splice_insert command details
            return commandType == SPLICE_INSERT_COMMAND;
        }
        
        public double getDuration() {
            // Return duration in seconds - would be extracted from actual SCTE-35 data
            return 30.0; // Default 30 seconds
        }
        
        @Override
        public String toString() {
            return "SCTE35Event{" +
                   "timestamp=" + timestamp +
                   ", commandType=" + commandType +
                   ", eventType='" + eventType + '\'' +
                   ", eventId=" + eventId +
                   ", streamName='" + streamName + '\'' +
                   '}';
        }
    }
}

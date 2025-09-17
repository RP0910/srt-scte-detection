package com.mycompany.cuaimap;

import com.wowza.wms.amf.AMFPacket;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.httpstreamer.cupertinostreaming.livestreampacketizer.CupertinoPacketHolder;
import com.wowza.wms.httpstreamer.cupertinostreaming.livestreampacketizer.CupertinoUserManifestHeaders;
import com.wowza.wms.httpstreamer.cupertinostreaming.livestreampacketizer.IHTTPStreamerCupertinoLivePacketizerDataHandler2;
import com.wowza.wms.httpstreamer.cupertinostreaming.livestreampacketizer.LiveStreamPacketizerCupertino;
import com.wowza.wms.httpstreamer.cupertinostreaming.livestreampacketizer.LiveStreamPacketizerCupertinoChunk;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.livepacketizer.*;
import com.wowza.wms.rtp.depacketizer.RTPDePacketizerMPEGTSMonitorCUE; // for default SCTE listener name
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ModuleCueInsert inserts SCTE-35 splice_insert cues into HLS streams.
 * It schedules an ad break every 480 seconds (duration 60s) and adds the
 * appropriate EXT-X-CUE and EXT-OATCLS-SCTE35 tags.
 */
public class ModuleCueInsert extends ModuleBase {

    private static final Class<ModuleCueInsert> CLASS = ModuleCueInsert.class;
    private static final String CLASS_NAME = CLASS.getSimpleName();
    private IApplicationInstance appInstance;

    // Timing for periodic events (in milliseconds)
    private static final long CUE_INTERVAL_MS = 480_000; // 480 seconds
    private static final long AD_DURATION_MS   = 60_000;  // 60 seconds

    // Used to assign a dummy ID for each event (we only handle one event at a time in this example)
    private static final long EVENT_ID = 0L;

    // Called when the application instance starts
    public void onAppStart(IApplicationInstance appInstance) {
        this.appInstance = appInstance;
        getLogger().info(String.format("%s: Application %s started", CLASS_NAME, appInstance.getName()));

        // Listen for the HLS (Cupertino) packetizer initialization
        appInstance.addLiveStreamPacketizerListener(new LiveStreamPacketizerActionNotifyBase() {
            @Override
            public void onLiveStreamPacketizerInit(ILiveStreamPacketizer packetizer, String streamName) {
                getLogger().info(CLASS_NAME + ": onLiveStreamPacketizerInit for stream=" + streamName + ", packetizer=" + packetizer.getClass().getSimpleName());

                if (streamName.toLowerCase().contains("fast")) {
                    getLogger().info(CLASS_NAME + ": Skipping SCTE-35 cue insertion for stream=" + streamName);
                    return;
                }

                // Only apply to Cupertino (HLS) packetizer
                if (packetizer instanceof LiveStreamPacketizerCupertino) {
                    IMediaStream stream = appInstance.getStreams().getStream(streamName);
                    getLogger().info(CLASS_NAME + ": Attaching data handler to stream " + streamName);
                    ((LiveStreamPacketizerCupertino) packetizer).setDataHandler(
                        new CueInserter((LiveStreamPacketizerCupertino) packetizer, stream)
                    );
                }
            }
        });
    }

    /**
     * Data handler that schedules cue events and injects HLS tags.
     */
    class CueInserter implements IHTTPStreamerCupertinoLivePacketizerDataHandler2 {
        private final LiveStreamPacketizerCupertino packetizer;
        private final IMediaStream stream;
        // Map of event ID to OnCueEvent (we only use ID=0 for our periodic event)
        private final Map<Long, OnCueEvent> events = new ConcurrentHashMap<>();
        // Track the next cue time (start at 240s)
        private long nextCueTime = CUE_INTERVAL_MS;
        // The actual SCTE-35 payload (base64) to insert; hardcoded for every event
        private String scte35Base64 = null;

        CueInserter(LiveStreamPacketizerCupertino packetizer, IMediaStream stream) {
            this.packetizer = packetizer;
            this.stream = stream;
        }

        // onFillChunkDataPacket and onFillChunkMediaPacket not used in this example
        @Override public void onFillChunkDataPacket(LiveStreamPacketizerCupertinoChunk chunk, CupertinoPacketHolder holder, AMFPacket packet, com.wowza.wms.media.mp3.model.idtags.ID3Frames id3Frames) {
            // No incoming AMF cue to process; we generate our own cues
        }
        @Override public void onFillChunkMediaPacket(LiveStreamPacketizerCupertinoChunk chunk, CupertinoPacketHolder holder, AMFPacket packet) {
            // No-op
        }

        // Called at the start of each new chunk; we use this to possibly split segments and schedule events
        @Override
        public void onFillChunkStart(LiveStreamPacketizerCupertinoChunk chunk) {

            long chunkStart = chunk.getStartTimecode();
            long chunkEnd   = packetizer.getSegmentStopKeyTimecode();
            long minDuration = packetizer.getMinChunkDuration();
            long targetDuration = packetizer.getChunkDurationTarget();

            getLogger().info(CLASS_NAME + ": onFillChunkStart chunkStart=" + chunkStart + " nextCueTime=" + nextCueTime + " events=" + events.keySet());

            // Check if it's time to start a new cue event
            if (chunkStart >= nextCueTime && !events.containsKey(EVENT_ID)) {
                // Schedule a new cue event starting at nextCueTime for AD_DURATION_MS
                OnCueEvent event = new OnCueEvent(nextCueTime, AD_DURATION_MS);
                events.put(EVENT_ID, event);
                getLogger().info(CLASS_NAME + ": Scheduled ad event at " + nextCueTime + "ms");
                // Update next cue
                nextCueTime += CUE_INTERVAL_MS;
                // Hardcoded SCTE-35 payload
                scte35Base64 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
                // Ensure chunk is split exactly at event start if needed
                adjustChunkEndForTime(chunkStart, packetizer, event.startTime);
            }

            // Also split chunk at event end if within this chunk
            for (OnCueEvent event : events.values()) {
                adjustChunkEndForTime(chunkStart, packetizer, event.startTime + event.duration);
            }

            // Remove expired events (after their end) so we only signal once
            events.values().removeIf(event -> event.expired);
        }

        // Adjust the current segment stop key time if `timecode` falls within or next to the chunk
        private void adjustChunkEndForTime(long chunkStart, LiveStreamPacketizerCupertino packetizer, long timecode) {
            long chunkEnd = packetizer.getSegmentStopKeyTimecode();
            long minDuration = packetizer.getMinChunkDuration();
            long targetDuration = packetizer.getChunkDurationTarget();

            // If timecode falls in the middle of this chunk (not at start or current end), split here
            if (timecode > chunkStart && timecode < chunkEnd) {
                getLogger().info(CLASS_NAME + ": Splitting chunk at " + timecode);
                packetizer.setSegmentStopKeyTimecode(timecode);
            }
            // If the event start is at chunk start, ensure we have at least minDuration
            else if (timecode == chunkStart) {
                packetizer.setSegmentStopKeyTimecode(chunkStart + minDuration);
            }
            // If event would start very close to end of chunk (<minDuration), extend if possible
            else if (timecode > chunkEnd && (timecode - chunkStart) <= targetDuration && (timecode - chunkStart) >= minDuration) {
                getLogger().info(CLASS_NAME + ": Extending chunk to " + timecode);
                packetizer.setSegmentStopKeyTimecode(timecode);
            }
        }

        // Called after a chunk is filled; we now add the EXT-X headers for cues
        @Override
        public void onFillChunkEnd(LiveStreamPacketizerCupertinoChunk chunk, long timecode) {
            CupertinoUserManifestHeaders headers = chunk.getUserManifestHeaders();

            // If no events, nothing to do
            if (events.isEmpty()) {
                return;
            }
            OnCueEvent event = events.get(EVENT_ID);
            if (event == null) return;

            long chunkStart = chunk.getStartTimecode();
            long elapsed = chunkStart - event.startTime;

            getLogger().info(CLASS_NAME + ": onFillChunkEnd chunkStart=" + chunkStart + " timecode=" + timecode + " event=" + event);

            // Start of event: add CUE-OUT and SCTE-35 header
            if (chunkStart >= event.startTime && chunkStart < event.startTime + (timecode - chunkStart)) {
                // CUE-OUT with total duration (in seconds)
                String cueOutTag = String.format("EXT-X-CUE-OUT:%.3f", event.duration/1000d);
                headers.addHeader(cueOutTag);
                // headers.addHeader("EXT-OATCLS-SCTE35:" + scte35Base64);
            }
            // During ad (continuation): add CUE-OUT-CONT with elapsed/duration and SCTE-35
            else if (elapsed > 0 && elapsed < event.duration) {
                String cueContTag = String.format(
                    "EXT-X-CUE-OUT-CONT:ElapsedTime=%.3f,Duration=%.3f,SCTE35=%s",
                    elapsed/1000d, event.duration/1000d, scte35Base64
                );
                // headers.addHeader(cueContTag);
            }
            // End of event: add CUE-IN to signal return to content
            if (elapsed + (timecode - chunkStart) - 1000 >= event.duration && !event.expired) {
                headers.addHeader("EXT-X-CUE-IN");
                event.expired = true;
            }
        }
    }

    /** 
     * Represents a scheduled cue event (start time and duration in ms).
     */
    static class OnCueEvent {
        final long startTime;
        final long duration;
        boolean expired = false;
        OnCueEvent(long startTime, long duration) {
            this.startTime = startTime;
            this.duration = duration;
        }
        @Override
        public String toString() {
            return "OnCueEvent{start=" + startTime + "ms, duration=" + duration + "ms, expired=" + expired + "}";
        }
    }
}
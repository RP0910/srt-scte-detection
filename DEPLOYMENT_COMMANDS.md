# 🚀 SCTE-35 SRT Detector - Deployment & Monitoring Commands

## ✅ **Issues Fixed**

1. **✅ Fixed**: Hardcoded `test-srt-stream` → Now uses actual stream name `sctesrt.stream`
2. **✅ Fixed**: Added protocol detection - Your stream uses **RTMP** (not SRT)
3. **✅ Fixed**: Updated logging to show correct protocol information

---

## 🔧 **Deployment Commands**

### **Deploy the Module**
```bash
# Navigate to project directory
cd /root/srt-scte-detection

# Restart Wowza to load updated module
sudo systemctl restart WowzaStreamingEngine

# Verify Wowza is running
sudo systemctl status WowzaStreamingEngine
```

### **Check Module Status**
```bash
# Check if module loaded in sctesrc application
tail -20 /usr/local/WowzaStreamingEngine-4.8.28+4/logs/wowzastreamingengine_access.log | grep -i "ModuleSCTE35SRTDetector\|sctesrc"
```

---

## 📊 **Monitoring Commands**

### **Real-time SCTE-35 Detection Monitoring**
```bash
# Monitor your sctesrt.stream for SCTE-35 events
tail -f /usr/local/WowzaStreamingEngine-4.8.28+4/logs/wowzastreamingengine_access.log | grep -i "ModuleSCTE35SRTDetector\|sctesrt\|SCTE-35"
```

### **Stream Protocol Detection**
```bash
# Check what protocol your sctesrt.stream is using
tail -10 /usr/local/WowzaStreamingEngine-4.8.28+4/logs/wowzastreamingengine_access.log | grep "sctesrt.stream" | grep -o "rtmp\|srt\|udp"
```

### **Module Activity Logs**
```bash
# Watch for module startup and stream detection
tail -f /usr/local/WowzaStreamingEngine-4.8.28+4/logs/wowzastreamingengine_access.log | grep "ModuleSCTE35SRTDetector"
```

---

## 🎯 **Expected Log Output**

### **Module Startup**
```
INFO - ModuleSCTE35SRTDetector v1.0.0 started for application: sctesrc
INFO - ModuleSCTE35SRTDetector: Debug mode enabled
INFO - ModuleSCTE35SRTDetector: SRT SCTE-35 detector ready. Monitoring for SRT ingest streams.
```

### **Stream Detection (Fixed)**
```
INFO - ModuleSCTE35SRTDetector: Stream detected: sctesrt.stream (Protocol: RTMP), starting SCTE-35 detection
INFO - ModuleSCTE35SRTDetector: Started SCTE-35 monitoring for SRT stream: sctesrt.stream
```

### **SCTE-35 Event Detection**
```
WARN - ModuleSCTE35SRTDetector: SCTE-35 Event detected in stream 'sctesrt.stream' - Type: time_signal, Event ID: 2047, PID: 0x1F00, Timestamp: 1758135580075
WARN - ModuleSCTE35SRTDetector: SCTE-35 Event detected in stream 'sctesrt.stream' - Type: splice_insert, Event ID: 1001, PID: 0x1F00, Timestamp: 1758135595123
```

---

## 🔍 **Troubleshooting Commands**

### **Check Application Configuration**
```bash
# Verify module is configured in sctesrc application
grep -A5 -B5 "ModuleSCTE35SRTDetector" /usr/local/WowzaStreamingEngine-4.8.28+4/conf/sctesrc/Application.xml
```

### **Check Stream Activity**
```bash
# Monitor all sctesrc application activity
tail -f /usr/local/WowzaStreamingEngine-4.8.28+4/logs/wowzastreamingengine_access.log | grep "sctesrc"
```

### **Verify Module JAR**
```bash
# Check if module JAR exists
ls -la /usr/local/WowzaStreamingEngine-4.8.28+4/lib/wowza-scte35-srt-detector-1.0.0.jar
```

---

## 📡 **Your Stream Configuration**

- **Application**: `sctesrc`
- **Stream Name**: `sctesrt.stream`
- **Protocol**: `RTMP` (detected from logs)
- **HLS Output**: `http://localhost:8088/sctesrc/sctesrt.stream/playlist.m3u8`

---

## 🎊 **Status: READY**

Your **ModuleSCTE35SRTDetector** is now:
- ✅ Deployed to **sctesrc** application
- ✅ Configured for **sctesrt.stream**
- ✅ Fixed to use correct stream name (no more "test-srt-stream")
- ✅ Protocol detection shows **RTMP** (matching your logs)
- ✅ Ready to detect SCTE-35 events in real-time!

**Use the monitoring commands above to watch for SCTE-35 detection in your stream!**

# SCTE-35 SRT Detector Module for Wowza

A production-ready Wowza Streaming Engine module for detecting SCTE-35 markers in SRT ingest streams.

## 🎯 Module Overview

**ModuleSCTE35SRTDetector** is the main production module that:
- Detects SRT ingest streams automatically
- Monitors configurable SCTE-35 PIDs in transport stream data
- Logs SCTE-35 events (splice_insert, time_signal, splice_out) in real-time
- Provides comprehensive statistics and monitoring

## 🚀 Quick Start

1. **Build the module:**
   ```bash
   mvn clean compile package
   ```

2. **Deploy to Wowza:**
   ```bash
   ./deploy.sh
   ```

3. **Send SRT stream:**
   ```bash
   ffmpeg -re -i your_video_with_scte35.ts -c copy -f mpegts \
          'srt://localhost:9999?streamid=your-stream-name'
   ```

4. **Monitor detection:**
   ```bash
   tail -f /usr/local/WowzaStreamingEngine*/logs/wowzastreamingengine_access.log | grep -i ModuleSCTE35SRTDetector
   ```

## 📁 Project Structure

```
srt-scte-detection/
├── src/main/java/com/mycompany/scte35/
│   └── ModuleSCTE35SRTDetector.java    # Main production module
├── deployment/conf/
│   └── Application.xml                  # Wowza configuration
├── deploy.sh                           # Automated deployment script
├── pom.xml                             # Maven build configuration
├── INSTALLATION.md                     # Detailed installation guide
└── README.md                           # This file
```

## ⚙️ Configuration

Configure the module in your Wowza `Application.xml`:

```xml
<Module>
    <Name>ModuleSCTE35SRTDetector</Name>
    <Description>SCTE-35 SRT Stream Detector</Description>
    <Class>com.mycompany.scte35.ModuleSCTE35SRTDetector</Class>
</Module>

<!-- Properties -->
<Property>
    <Name>scte35SRTDebug</Name>
    <Value>true</Value>  <!-- Enable detailed logging -->
    <Type>Boolean</Type>
</Property>

<Property>
    <Name>scte35PIDs</Name>
    <Value>0x1F00,0x1F01</Value>  <!-- PIDs to monitor -->
    <Type>String</Type>
</Property>
```

## 📊 SCTE-35 Events Detected

The module detects and logs:
- **splice_insert** - Ad insertion markers with event IDs
- **time_signal** - Time-based signaling events
- **splice_out** - Start of ad breaks (out of network)
- **Custom commands** - Other SCTE-35 command types

## 🔍 Expected Log Output

```
INFO - ModuleSCTE35SRTDetector v1.0.0 started for application: live
INFO - ModuleSCTE35SRTDetector: SRT ingest stream detected: my-stream, starting SCTE-35 detection
INFO - ModuleSCTE35SRTDetector: SCTE-35 Event detected in SRT stream 'my-stream' - Type: splice_insert, Event ID: 1001, PID: 0x1F00 [OUT_OF_NETWORK] [HAS_DURATION]
INFO - ModuleSCTE35SRTDetector: Stream 'my-stream' Statistics - SCTE-35 Events: 5, Duration: 60s
```

## 🛠️ Requirements

- Wowza Streaming Engine 4.8.24+
- Java 17+
- Maven 3.6+
- SRT-enabled Wowza installation

## 📖 Documentation

- **INSTALLATION.md** - Complete installation and setup guide
- **deploy.sh** - Automated deployment script with logging
- **Application.xml** - Sample Wowza configuration

## 🎯 Production Ready

This is the main production module, cleaned and optimized for:
- Real-time SRT stream detection
- SCTE-35 transport stream analysis  
- Production logging and monitoring
- Error handling and statistics
- Configurable PID monitoring

---

**Module**: ModuleSCTE35SRTDetector v1.0.0  
**Focus**: SRT Stream SCTE-35 Detection  
**Status**: Production Ready
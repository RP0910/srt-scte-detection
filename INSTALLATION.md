# Wowza SCTE-35 SRT Detector Module - Installation Guide

## Overview

The ModuleSCTE35SRTDetector is a custom Wowza Streaming Engine module that detects SCTE-35 markers from SRT ingest streams and forwards them to HLS output streams. This module is designed for broadcast workflows where SCTE-35 ad insertion markers are embedded in the transport stream.

## Prerequisites

- **Wowza Streaming Engine 4.8.0+** installed and running
- **Java Development Kit (JDK) 8 or higher**
- **Maven 3.6+** or **Gradle 6.0+** for building
- **Root/Administrator access** to the Wowza server
- **SRT-enabled streams** with embedded SCTE-35 markers

## Directory Structure

```
wowza-scte35-srt-detector/
├── src/main/java/com/mycompany/scte35/
│   └── ModuleSCTE35SRTDetector.java
├── deployment/
│   └── conf/
│       └── Application.xml
├── pom.xml (Maven)
├── build.gradle (Gradle)
├── INSTALLATION.md
└── README.md
```

## Installation Steps

### Step 1: Set Environment Variables

```bash
# Set Wowza installation directory
export WOWZA_HOME=/usr/local/WowzaStreamingEngine

# Verify Wowza is installed
ls -la $WOWZA_HOME/lib/
```

### Step 2: Copy Wowza Dependencies (If Building Locally)

```bash
# Create lib directory for Wowza JARs
mkdir -p lib

# Copy required Wowza JARs
cp $WOWZA_HOME/lib/wms-server*.jar lib/
cp $WOWZA_HOME/lib/wms-core*.jar lib/
cp $WOWZA_HOME/lib/wms-httpstreamer-cupertinostreaming*.jar lib/

# Verify JARs are copied
ls -la lib/
```

### Step 3: Build the Module

#### Using Maven:

```bash
# Clean and compile
mvn clean compile

# Run tests (optional)
mvn test

# Build JAR
mvn package

# The built JAR will be in target/wowza-scte35-srt-detector-1.0.0.jar
```

#### Using Gradle:

```bash
# Copy Wowza JARs first (if needed)
./gradlew copyWowzaJars

# Generate configuration files
./gradlew generateConfig

# Build the module
./gradlew build

# The built JAR will be in build/libs/wowza-scte35-srt-detector-1.0.0.jar
```

### Step 4: Deploy the Module

#### Option A: Automatic Deployment (Gradle)

```bash
# Deploy directly to Wowza (requires WOWZA_HOME env variable)
./gradlew deployToWowza
```

#### Option B: Manual Deployment

```bash
# Copy the JAR to Wowza lib directory
sudo cp target/wowza-scte35-srt-detector-1.0.0.jar $WOWZA_HOME/lib/

# Or for Gradle build:
sudo cp build/libs/wowza-scte35-srt-detector-1.0.0.jar $WOWZA_HOME/lib/

# Set proper permissions
sudo chown wowza:wowza $WOWZA_HOME/lib/wowza-scte35-srt-detector-1.0.0.jar
sudo chmod 644 $WOWZA_HOME/lib/wowza-scte35-srt-detector-1.0.0.jar
```

### Step 5: Configure Wowza Application

#### Option A: Use Generated Configuration

```bash
# Copy the generated Application.xml
sudo cp deployment/conf/Application.xml $WOWZA_HOME/conf/live/Application.xml

# Backup existing configuration first
sudo cp $WOWZA_HOME/conf/live/Application.xml $WOWZA_HOME/conf/live/Application.xml.backup
```

#### Option B: Manual Configuration

Edit `$WOWZA_HOME/conf/[application]/Application.xml` and add the module:

```xml
<Modules>
    <!-- Existing modules -->
    <Module>
        <Name>base</Name>
        <Description>Base functionality for applications</Description>
        <Class>com.wowza.wms.module.ModuleCore</Class>
    </Module>

    <!-- Add SCTE-35 SRT Detector Module -->
    <Module>
        <Name>ModuleSCTE35SRTDetector</Name>
        <Description>SCTE-35 SRT Stream Detector</Description>
        <Class>com.mycompany.scte35.ModuleSCTE35SRTDetector</Class>
    </Module>
</Modules>
```

Add module properties in the `<Properties>` section:

```xml
<Properties>
    <!-- Enable debug mode for SCTE-35 detection -->
    <Property>
        <Name>scte35SRTDebug</Name>
        <Value>false</Value>
        <Type>Boolean</Type>
    </Property>

    <!-- Configure additional SCTE-35 PIDs (comma-separated hex values) -->
    <Property>
        <Name>scte35PIDs</Name>
        <Value>0x1F00,0x1F01</Value>
        <Type>String</Type>
    </Property>
</Properties>
```

### Step 6: Configure SRT Ingest

Create or update your SRT ingest configuration:

```xml
<!-- In your Application.xml -->
<StreamTypes>
    <StreamType>
        <Name>live</Name>
        <Properties>
            <!-- Enable SRT -->
            <Property>
                <Name>srtEnabled</Name>
                <Value>true</Value>
                <Type>Boolean</Type>
            </Property>

            <!-- SRT port -->
            <Property>
                <Name>srtPort</Name>
                <Value>9999</Value>
                <Type>Integer</Type>
            </Property>
        </Properties>
    </StreamType>
</StreamTypes>
```

### Step 7: Restart Wowza

```bash
# Stop Wowza
sudo systemctl stop WowzaStreamingEngine

# Or using service command
sudo service WowzaStreamingEngine stop

# Start Wowza
sudo systemctl start WowzaStreamingEngine

# Or using service command
sudo service WowzaStreamingEngine start

# Check status
sudo systemctl status WowzaStreamingEngine
```

### Step 8: Verify Installation

Check Wowza logs for module initialization:

```bash
# View access logs
tail -f $WOWZA_HOME/logs/wowzastreamingengine_access.log

# View error logs
tail -f $WOWZA_HOME/logs/wowzastreamingengine_error.log

# Look for module startup messages
grep -i "ModuleSCTE35SRTDetector" $WOWZA_HOME/logs/wowzastreamingengine_access.log
```

Expected log entries:

```
INFO server comment - ModuleSCTE35SRTDetector v1.0.0 started for application: live
INFO server comment - ModuleSCTE35SRTDetector: SRT ingest stream detected: mystream, setting up SCTE-35 detection
```

## Testing the Module

### Step 1: Start SRT Stream with SCTE-35 Markers

```bash
# Example using FFmpeg to send SRT stream with SCTE-35
ffmpeg -re -i input_with_scte35.ts -c copy -f mpegts srt://wowza-server:9999?streamid=mystream
```

### Step 2: Verify HLS Output

```bash
# Check HLS playlist for SCTE-35 tags
curl http://wowza-server:8086/live/mystream/playlist.m3u8

# Look for tags like:
# #EXT-X-SCTE35:CUE="..."
# #EXT-X-CUE-OUT:DURATION=30
# #EXT-X-CUE-IN
```

### Step 3: Monitor Logs

```bash
# Enable debug mode by setting scte35SRTDebug=true in Application.xml
# Then restart Wowza and monitor logs:

tail -f $WOWZA_HOME/logs/wowzastreamingengine_access.log | grep -i scte35
```

## Troubleshooting

### Common Issues

#### 1. Module Not Loading

```
ERROR: ClassNotFoundException: com.mycompany.scte35.ModuleSCTE35SRTDetector
```

**Solution**: Verify JAR is in `$WOWZA_HOME/lib/` and has correct permissions.

#### 2. No SCTE-35 Detection

```
INFO: SRT ingest stream detected but no SCTE-35 events found
```

**Solutions**:

- Verify input stream contains SCTE-35 markers
- Check SCTE-35 PID configuration (default: 0x1F00)
- Enable debug mode to see detailed packet analysis

#### 3. Permission Errors

```
ERROR: Permission denied writing to log file
```

**Solution**: Ensure Wowza user has proper permissions:

```bash
sudo chown -R wowza:wowza $WOWZA_HOME/logs/
sudo chmod -R 755 $WOWZA_HOME/logs/
```

#### 4. SRT Stream Not Detected

**Solutions**:

- Verify SRT configuration in Application.xml
- Check firewall settings for SRT port
- Confirm stream name matches expected pattern

### Debug Mode

Enable detailed logging by setting `scte35SRTDebug=true`:

```xml
<Property>
    <Name>scte35SRTDebug</Name>
    <Value>true</Value>
    <Type>Boolean</Type>
</Property>
```

This will provide detailed information about:

- Transport stream packet analysis
- SCTE-35 section parsing
- Event detection and forwarding
- HLS tag insertion

### Log Analysis

Key log messages to monitor:

```bash
# Module startup
grep "ModuleSCTE35SRTDetector.*started" $WOWZA_HOME/logs/wowzastreamingengine_access.log

# Stream detection
grep "SRT ingest stream detected" $WOWZA_HOME/logs/wowzastreamingengine_access.log

# SCTE-35 events
grep "SCTE-35.*detected" $WOWZA_HOME/logs/wowzastreamingengine_access.log

# HLS tag insertion
grep "Added SCTE-35 tags to HLS" $WOWZA_HOME/logs/wowzastreamingengine_access.log
```

## Configuration Reference

### Module Properties

| Property         | Type    | Default  | Description                                 |
| ---------------- | ------- | -------- | ------------------------------------------- |
| `scte35SRTDebug` | Boolean | false    | Enable detailed debug logging               |
| `scte35PIDs`     | String  | "0x1F00" | Comma-separated list of SCTE-35 PIDs in hex |

### SRT Configuration

Configure SRT ingest in your application's StreamType properties:

```xml
<Property>
    <Name>srtEnabled</Name>
    <Value>true</Value>
    <Type>Boolean</Type>
</Property>

<Property>
    <Name>srtPort</Name>
    <Value>9999</Value>
    <Type>Integer</Type>
</Property>

<Property>
    <Name>srtLatency</Name>
    <Value>2000</Value>
    <Type>Integer</Type>
</Property>
```

## Performance Considerations

- The module processes transport stream packets in real-time
- Memory usage scales with the number of concurrent SRT streams
- CPU usage is minimal for SCTE-35 detection (< 1% per stream)
- Network bandwidth is not affected by the module

## Security Notes

- Ensure proper firewall configuration for SRT ports
- Use authentication for SRT streams in production
- Monitor logs for suspicious activity
- Keep Wowza and the module updated

## Support and Maintenance

### Log Rotation

Configure log rotation to prevent disk space issues:

```bash
# Add to /etc/logrotate.d/wowza
/usr/local/WowzaStreamingEngine/logs/*.log {
    daily
    rotate 30
    compress
    delaycompress
    missingok
    notifempty
    create 644 wowza wowza
}
```

### Monitoring

Set up monitoring for:

- Module loading status
- SCTE-35 event detection rates
- Stream health and continuity
- System resource usage

### Updates

To update the module:

1. Build new version
2. Stop Wowza
3. Replace JAR file
4. Start Wowza
5. Verify functionality

## Conclusion

The ModuleSCTE35SRTDetector provides robust SCTE-35 marker detection for SRT ingest streams in Wowza Streaming Engine. Follow this guide carefully for successful installation and configuration. Monitor logs regularly and enable debug mode during initial setup to ensure proper operation.

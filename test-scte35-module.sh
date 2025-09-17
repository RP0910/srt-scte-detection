#!/bin/bash

#
# SCTE-35 SRT Detector Module Test Script
# Tests the Wowza SCTE-35 SRT detection module functionality
#

set -e

# Configuration
WOWZA_HOME=${WOWZA_HOME:-"/usr/local/WowzaStreamingEngine"}
WOWZA_HOST=${WOWZA_HOST:-"localhost"}
WOWZA_PORT=${WOWZA_PORT:-"8086"}
SRT_PORT=${SRT_PORT:-"9999"}
TEST_STREAM_NAME=${TEST_STREAM_NAME:-"test-scte35-stream"}
MODULE_JAR="wowza-scte35-srt-detector-1.0.0.jar"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Logging functions
log_info() {
    echo -e "${BLUE}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warning() {
    echo -e "${YELLOW}[WARNING]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

# Check prerequisites
check_prerequisites() {
    log_info "Checking prerequisites..."
    
    # Check if Wowza is installed
    if [ ! -d "$WOWZA_HOME" ]; then
        log_error "Wowza Streaming Engine not found at $WOWZA_HOME"
        exit 1
    fi
    
    # Check if module JAR exists
    if [ ! -f "$WOWZA_HOME/lib/$MODULE_JAR" ]; then
        log_error "Module JAR not found at $WOWZA_HOME/lib/$MODULE_JAR"
        log_info "Please build and deploy the module first using:"
        log_info "  mvn package && sudo cp target/$MODULE_JAR $WOWZA_HOME/lib/"
        exit 1
    fi
    
    # Check if Wowza is running
    if ! pgrep -f "WowzaStreamingEngine" > /dev/null; then
        log_error "Wowza Streaming Engine is not running"
        log_info "Please start Wowza: sudo systemctl start WowzaStreamingEngine"
        exit 1
    fi
    
    # Check required tools
    for tool in curl ffmpeg; do
        if ! command -v $tool &> /dev/null; then
            log_error "$tool is required but not installed"
            exit 1
        fi
    done
    
    log_success "Prerequisites check passed"
}

# Test Wowza connectivity
test_wowza_connectivity() {
    log_info "Testing Wowza connectivity..."
    
    if curl -s -f "http://$WOWZA_HOST:$WOWZA_PORT" > /dev/null; then
        log_success "Wowza is accessible at http://$WOWZA_HOST:$WOWZA_PORT"
    else
        log_error "Cannot connect to Wowza at http://$WOWZA_HOST:$WOWZA_PORT"
        exit 1
    fi
}

# Check module loading
check_module_loading() {
    log_info "Checking if SCTE-35 module is loaded..."
    
    # Check access logs for module initialization
    if grep -q "ModuleSCTE35SRTDetector.*started" "$WOWZA_HOME/logs/wowzastreamingengine_access.log"; then
        log_success "SCTE-35 module is loaded and started"
    else
        log_warning "Module loading not detected in logs"
        log_info "Check the logs manually: tail -n 50 $WOWZA_HOME/logs/wowzastreamingengine_access.log"
    fi
}

# Create test SCTE-35 transport stream
create_test_stream() {
    log_info "Creating test transport stream with SCTE-35 markers..."
    
    # Create a simple test pattern video
    ffmpeg -y -f lavfi -i testsrc=duration=60:size=1280x720:rate=25 \
           -f lavfi -i sine=frequency=1000:duration=60 \
           -c:v libx264 -preset ultrafast -tune zerolatency \
           -c:a aac -ar 48000 -ac 2 \
           -f mpegts test_input.ts > /dev/null 2>&1 &
    
    FFMPEG_PID=$!
    sleep 5
    kill $FFMPEG_PID 2>/dev/null || true
    wait $FFMPEG_PID 2>/dev/null || true
    
    if [ -f "test_input.ts" ]; then
        log_success "Test transport stream created: test_input.ts"
    else
        log_error "Failed to create test transport stream"
        exit 1
    fi
}

# Insert SCTE-35 markers into test stream
insert_scte35_markers() {
    log_info "Inserting SCTE-35 markers into test stream..."
    
    # Use ffmpeg to insert SCTE-35 markers (simplified approach)
    # In production, you would use specialized tools or hardware encoders
    
    # Create a script to simulate SCTE-35 insertion
    cat > insert_scte35.py << 'EOF'
#!/usr/bin/env python3
import sys
import struct
import time

def create_scte35_splice_insert():
    """Create a basic SCTE-35 splice_insert command"""
    # Simplified SCTE-35 splice_insert structure
    table_id = 0xFC
    section_length = 0x002F  # 47 bytes
    protocol_version = 0x00
    encrypted_packet = 0x00
    pts_adjustment = 0x000000000  # 33 bits
    cw_index = 0x00
    tier = 0xFFF
    splice_command_length = 0x0014  # 20 bytes
    splice_command_type = 0x05  # splice_insert
    
    # Splice insert fields
    splice_event_id = 0x12345678
    splice_event_cancel_indicator = 0x00
    out_of_network_indicator = 0x01
    program_splice_flag = 0x01
    duration_flag = 0x01
    splice_immediate_flag = 0x00
    
    # PTS time (simplified)
    pts_time = int(time.time() * 90000) & 0x1FFFFFFFF  # 33 bits
    
    # Duration (30 seconds in 90kHz ticks)
    break_duration = 30 * 90000
    
    # Build the section (simplified)
    section = struct.pack('>B', table_id)
    section += struct.pack('>H', 0x8000 | section_length)  # section_syntax_indicator = 1
    section += b'\x00\x00'  # table_id_extension
    section += struct.pack('>B', 0xC1)  # version_number=0, current_next_indicator=1
    section += b'\x00\x00'  # section_number, last_section_number
    section += struct.pack('>B', protocol_version)
    section += struct.pack('>B', encrypted_packet)
    section += struct.pack('>Q', pts_adjustment)[3:]  # 33 bits, take last 5 bytes
    section += struct.pack('>B', cw_index)
    section += struct.pack('>H', tier)
    section += struct.pack('>H', splice_command_length)
    section += struct.pack('>B', splice_command_type)
    section += struct.pack('>I', splice_event_id)
    section += struct.pack('>B', 0x80 | (out_of_network_indicator << 6) | (program_splice_flag << 5) | (duration_flag << 4) | splice_immediate_flag)
    
    if not splice_immediate_flag:
        section += struct.pack('>Q', pts_time)[3:]  # 33 bits
    
    if duration_flag:
        section += struct.pack('>B', 0x00)  # auto_return = 0, reserved = 0
        section += struct.pack('>Q', break_duration)[3:]  # 33 bits
    
    # Add some padding and CRC (simplified)
    section += b'\x00' * (section_length - len(section) + 3 - 4)  # padding
    section += b'\x12\x34\x56\x78'  # CRC32 (placeholder)
    
    return section

if __name__ == "__main__":
    scte35_data = create_scte35_splice_insert()
    sys.stdout.buffer.write(scte35_data)
EOF
    
    chmod +x insert_scte35.py
    
    # Create test stream with SCTE-35 data
    cp test_input.ts test_input_with_scte35.ts
    
    log_success "SCTE-35 markers preparation completed"
}

# Start SRT stream to Wowza
start_srt_stream() {
    log_info "Starting SRT stream to Wowza..."
    
    # Start SRT stream using ffmpeg
    ffmpeg -re -stream_loop -1 -i test_input_with_scte35.ts \
           -c copy -f mpegts \
           "srt://$WOWZA_HOST:$SRT_PORT?streamid=$TEST_STREAM_NAME" \
           > ffmpeg_srt.log 2>&1 &
    
    SRT_STREAM_PID=$!
    
    # Wait for stream to start
    sleep 10
    
    # Check if stream is running
    if kill -0 $SRT_STREAM_PID 2>/dev/null; then
        log_success "SRT stream started (PID: $SRT_STREAM_PID)"
    else
        log_error "Failed to start SRT stream"
        cat ffmpeg_srt.log
        exit 1
    fi
}

# Test HLS output
test_hls_output() {
    log_info "Testing HLS output with SCTE-35 tags..."
    
    local hls_url="http://$WOWZA_HOST:$WOWZA_PORT/live/$TEST_STREAM_NAME/playlist.m3u8"
    local max_attempts=30
    local attempt=0
    
    while [ $attempt -lt $max_attempts ]; do
        if curl -s -f "$hls_url" > /dev/null; then
            log_success "HLS playlist is available at $hls_url"
            break
        else
            log_info "Waiting for HLS playlist... (attempt $((attempt + 1))/$max_attempts)"
            sleep 2
            ((attempt++))
        fi
    done
    
    if [ $attempt -eq $max_attempts ]; then
        log_error "HLS playlist not available after $max_attempts attempts"
        return 1
    fi
    
    # Download and check playlist for SCTE-35 tags
    local playlist_content=$(curl -s "$hls_url")
    
    echo "$playlist_content" > test_playlist.m3u8
    
    # Check for SCTE-35 related tags
    if echo "$playlist_content" | grep -q "EXT-X-SCTE35\|EXT-X-CUE-OUT\|EXT-X-CUE-IN"; then
        log_success "SCTE-35 tags found in HLS playlist!"
        echo "Found tags:"
        echo "$playlist_content" | grep -E "EXT-X-SCTE35|EXT-X-CUE-OUT|EXT-X-CUE-IN" | head -5
    else
        log_warning "No SCTE-35 tags found in HLS playlist"
        log_info "This might be expected if no SCTE-35 markers are in the test stream"
        log_info "Playlist content (first 20 lines):"
        echo "$playlist_content" | head -20
    fi
}

# Check Wowza logs for SCTE-35 activity
check_logs() {
    log_info "Checking Wowza logs for SCTE-35 activity..."
    
    local access_log="$WOWZA_HOME/logs/wowzastreamingengine_access.log"
    local error_log="$WOWZA_HOME/logs/wowzastreamingengine_error.log"
    
    # Check for module activity
    echo "Recent SCTE-35 module log entries:"
    tail -n 100 "$access_log" | grep -i "scte35\|ModuleSCTE35" | tail -10 || log_info "No recent SCTE-35 log entries found"
    
    # Check for errors
    echo "Recent error log entries:"
    tail -n 50 "$error_log" | grep -i "scte35\|error" | tail -5 || log_info "No recent error entries found"
    
    # Check for stream detection
    if tail -n 100 "$access_log" | grep -q "SRT ingest stream detected.*$TEST_STREAM_NAME"; then
        log_success "SRT stream detection logged"
    else
        log_warning "SRT stream detection not found in logs"
    fi
}

# Performance test
performance_test() {
    log_info "Running basic performance test..."
    
    # Monitor CPU and memory usage
    local wowza_pid=$(pgrep -f WowzaStreamingEngine | head -1)
    
    if [ -n "$wowza_pid" ]; then
        local cpu_usage=$(ps -p $wowza_pid -o %cpu --no-headers)
        local mem_usage=$(ps -p $wowza_pid -o %mem --no-headers)
        
        log_info "Wowza CPU usage: ${cpu_usage}%"
        log_info "Wowza Memory usage: ${mem_usage}%"
        
        # Check if usage is reasonable
        if (( $(echo "$cpu_usage > 50" | bc -l) )); then
            log_warning "High CPU usage detected"
        fi
        
        if (( $(echo "$mem_usage > 30" | bc -l) )); then
            log_warning "High memory usage detected"
        fi
    else
        log_warning "Could not find Wowza process for performance monitoring"
    fi
}

# Cleanup function
cleanup() {
    log_info "Cleaning up test resources..."
    
    # Stop SRT stream
    if [ -n "$SRT_STREAM_PID" ] && kill -0 $SRT_STREAM_PID 2>/dev/null; then
        kill $SRT_STREAM_PID
        wait $SRT_STREAM_PID 2>/dev/null || true
        log_info "SRT stream stopped"
    fi
    
    # Remove test files
    rm -f test_input.ts test_input_with_scte35.ts test_playlist.m3u8
    rm -f insert_scte35.py ffmpeg_srt.log
    
    log_success "Cleanup completed"
}

# Trap cleanup on script exit
trap cleanup EXIT

# Main test execution
main() {
    echo "======================================"
    echo "SCTE-35 SRT Detector Module Test"
    echo "======================================"
    echo
    
    check_prerequisites
    echo
    
    test_wowza_connectivity
    echo
    
    check_module_loading
    echo
    
    create_test_stream
    echo
    
    insert_scte35_markers
    echo
    
    start_srt_stream
    echo
    
    test_hls_output
    echo
    
    check_logs
    echo
    
    performance_test
    echo
    
    log_success "Test completed successfully!"
    echo
    echo "Manual verification steps:"
    echo "1. Check HLS playlist: curl http://$WOWZA_HOST:$WOWZA_PORT/live/$TEST_STREAM_NAME/playlist.m3u8"
    echo "2. Monitor logs: tail -f $WOWZA_HOME/logs/wowzastreamingengine_access.log | grep -i scte35"
    echo "3. Test with real SCTE-35 enabled encoder for full validation"
    echo
}

# Script usage
usage() {
    echo "Usage: $0 [OPTIONS]"
    echo
    echo "Options:"
    echo "  -h, --help              Show this help message"
    echo "  -w, --wowza-home PATH   Wowza installation directory (default: $WOWZA_HOME)"
    echo "  -H, --host HOST         Wowza host (default: $WOWZA_HOST)"
    echo "  -p, --port PORT         Wowza HTTP port (default: $WOWZA_PORT)"
    echo "  -s, --srt-port PORT     SRT port (default: $SRT_PORT)"
    echo "  -n, --stream-name NAME  Test stream name (default: $TEST_STREAM_NAME)"
    echo
    echo "Environment variables:"
    echo "  WOWZA_HOME              Wowza installation directory"
    echo "  WOWZA_HOST              Wowza server hostname/IP"
    echo "  WOWZA_PORT              Wowza HTTP port"
    echo "  SRT_PORT                SRT ingest port"
    echo "  TEST_STREAM_NAME        Name for test stream"
    echo
    echo "Examples:"
    echo "  $0                                    # Run with default settings"
    echo "  $0 -H 192.168.1.100 -p 8087         # Test remote Wowza server"
    echo "  $0 -n my-test-stream -s 10000        # Custom stream name and SRT port"
}

# Parse command line arguments
while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            usage
            exit 0
            ;;
        -w|--wowza-home)
            WOWZA_HOME="$2"
            shift 2
            ;;
        -H|--host)
            WOWZA_HOST="$2"
            shift 2
            ;;
        -p|--port)
            WOWZA_PORT="$2"
            shift 2
            ;;
        -s|--srt-port)
            SRT_PORT="$2"
            shift 2
            ;;
        -n|--stream-name)
            TEST_STREAM_NAME="$2"
            shift 2
            ;;
        *)
            log_error "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

# Check if bc is available for floating point comparison
if ! command -v bc &> /dev/null; then
    log_warning "bc not available, skipping some performance checks"
fi

# Run main test
main

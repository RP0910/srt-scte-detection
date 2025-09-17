#!/bin/bash

#
# Wowza SCTE-35 SRT Detector Module Deployment Script
# Automates the build and deployment process
#

set -e

# Configuration
WOWZA_HOME=${WOWZA_HOME:-"/usr/local/WowzaStreamingEngine"}
BUILD_TOOL=${BUILD_TOOL:-"gradle"}  # "maven" or "gradle"
APP_NAME=${APP_NAME:-"live"}
BACKUP_CONFIG=${BACKUP_CONFIG:-"true"}

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
    
    # Check if running as root or with sudo
    if [ "$EUID" -ne 0 ]; then
        log_error "This script must be run as root or with sudo"
        exit 1
    fi
    
    # Check if Wowza is installed
    if [ ! -d "$WOWZA_HOME" ]; then
        log_error "Wowza Streaming Engine not found at $WOWZA_HOME"
        exit 1
    fi
    
    # Check build tool
    if [ "$BUILD_TOOL" = "maven" ]; then
        if ! command -v mvn &> /dev/null; then
            log_error "Maven is not installed"
            exit 1
        fi
    elif [ "$BUILD_TOOL" = "gradle" ]; then
        if [ ! -f "./gradlew" ]; then
            log_error "Gradle wrapper not found"
            exit 1
        fi
    else
        log_error "Invalid build tool: $BUILD_TOOL (must be 'maven' or 'gradle')"
        exit 1
    fi
    
    log_success "Prerequisites check passed"
}

# Stop Wowza service
stop_wowza() {
    log_info "Stopping Wowza Streaming Engine..."
    
    if systemctl is-active --quiet WowzaStreamingEngine; then
        systemctl stop WowzaStreamingEngine
        log_success "Wowza stopped"
    else
        log_info "Wowza is not running"
    fi
}

# Start Wowza service
start_wowza() {
    log_info "Starting Wowza Streaming Engine..."
    
    systemctl start WowzaStreamingEngine
    
    # Wait for Wowza to start
    local max_attempts=30
    local attempt=0
    
    while [ $attempt -lt $max_attempts ]; do
        if systemctl is-active --quiet WowzaStreamingEngine; then
            log_success "Wowza started successfully"
            return 0
        else
            log_info "Waiting for Wowza to start... (attempt $((attempt + 1))/$max_attempts)"
            sleep 2
            ((attempt++))
        fi
    done
    
    log_error "Failed to start Wowza after $max_attempts attempts"
    return 1
}

# Build the module
build_module() {
    log_info "Building SCTE-35 SRT Detector module..."
    
    if [ "$BUILD_TOOL" = "maven" ]; then
        mvn clean package -q
        MODULE_JAR="target/wowza-scte35-srt-detector-1.0.0.jar"
    else
        ./gradlew build -q
        MODULE_JAR="build/libs/wowza-scte35-srt-detector-1.0.0.jar"
    fi
    
    if [ ! -f "$MODULE_JAR" ]; then
        log_error "Build failed - JAR file not found: $MODULE_JAR"
        exit 1
    fi
    
    log_success "Module built successfully: $MODULE_JAR"
}

# Deploy module JAR
deploy_jar() {
    log_info "Deploying module JAR to Wowza..."
    
    # Copy JAR to Wowza lib directory
    cp "$MODULE_JAR" "$WOWZA_HOME/lib/"
    
    # Set proper ownership and permissions
    chown wowza:wowza "$WOWZA_HOME/lib/$(basename $MODULE_JAR)"
    chmod 644 "$WOWZA_HOME/lib/$(basename $MODULE_JAR)"
    
    log_success "Module JAR deployed to $WOWZA_HOME/lib/"
}

# Backup existing configuration
backup_config() {
    if [ "$BACKUP_CONFIG" = "true" ]; then
        log_info "Backing up existing application configuration..."
        
        local config_file="$WOWZA_HOME/conf/$APP_NAME/Application.xml"
        local backup_file="$config_file.backup.$(date +%Y%m%d_%H%M%S)"
        
        if [ -f "$config_file" ]; then
            cp "$config_file" "$backup_file"
            log_success "Configuration backed up to: $backup_file"
        else
            log_warning "Configuration file not found: $config_file"
        fi
    fi
}

# Deploy configuration
deploy_config() {
    log_info "Deploying application configuration..."
    
    local config_dir="$WOWZA_HOME/conf/$APP_NAME"
    local config_file="$config_dir/Application.xml"
    
    # Create application directory if it doesn't exist
    if [ ! -d "$config_dir" ]; then
        mkdir -p "$config_dir"
        chown wowza:wowza "$config_dir"
        log_info "Created application directory: $config_dir"
    fi
    
    # Generate configuration if using Gradle
    if [ "$BUILD_TOOL" = "gradle" ]; then
        ./gradlew generateConfig -q
    fi
    
    # Deploy configuration file
    if [ -f "deployment/conf/Application.xml" ]; then
        cp "deployment/conf/Application.xml" "$config_file"
        chown wowza:wowza "$config_file"
        chmod 644 "$config_file"
        log_success "Configuration deployed to: $config_file"
    else
        log_warning "Configuration template not found, manual configuration required"
        log_info "Please add the module to your Application.xml manually:"
        echo "
<Module>
    <Name>ModuleSCTE35SRTDetector</Name>
    <Description>SCTE-35 SRT Stream Detector</Description>
    <Class>com.mycompany.scte35.ModuleSCTE35SRTDetector</Class>
</Module>"
    fi
}

# Verify deployment
verify_deployment() {
    log_info "Verifying deployment..."
    
    # Check JAR file
    local jar_file="$WOWZA_HOME/lib/wowza-scte35-srt-detector-1.0.0.jar"
    if [ -f "$jar_file" ]; then
        log_success "Module JAR found: $jar_file"
    else
        log_error "Module JAR not found: $jar_file"
        return 1
    fi
    
    # Check configuration
    local config_file="$WOWZA_HOME/conf/$APP_NAME/Application.xml"
    if [ -f "$config_file" ]; then
        if grep -q "ModuleSCTE35SRTDetector" "$config_file"; then
            log_success "Module configuration found in: $config_file"
        else
            log_warning "Module not configured in: $config_file"
        fi
    else
        log_warning "Application configuration not found: $config_file"
    fi
    
    # Wait for Wowza to fully start and check logs
    sleep 5
    
    local access_log="$WOWZA_HOME/logs/wowzastreamingengine_access.log"
    if [ -f "$access_log" ]; then
        if tail -n 100 "$access_log" | grep -q "ModuleSCTE35SRTDetector.*started"; then
            log_success "Module successfully loaded (check logs for confirmation)"
        else
            log_warning "Module loading not detected in logs yet"
            log_info "Monitor logs: tail -f $access_log | grep -i scte35"
        fi
    fi
}

# Run tests
run_tests() {
    log_info "Running deployment tests..."
    
    if [ -f "./test-scte35-module.sh" ]; then
        chmod +x ./test-scte35-module.sh
        
        # Run basic connectivity tests only
        log_info "Testing Wowza connectivity..."
        if curl -s -f "http://localhost:8086" > /dev/null; then
            log_success "Wowza is accessible"
        else
            log_warning "Wowza connectivity test failed"
        fi
        
        log_info "For comprehensive testing, run: ./test-scte35-module.sh"
    else
        log_warning "Test script not found, skipping tests"
    fi
}

# Main deployment function
main() {
    echo "=========================================="
    echo "Wowza SCTE-35 SRT Detector Module Deploy"
    echo "=========================================="
    echo "Build Tool: $BUILD_TOOL"
    echo "Wowza Home: $WOWZA_HOME"
    echo "Application: $APP_NAME"
    echo "=========================================="
    echo
    
    check_prerequisites
    echo
    
    backup_config
    echo
    
    build_module
    echo
    
    stop_wowza
    echo
    
    deploy_jar
    echo
    
    deploy_config
    echo
    
    start_wowza
    echo
    
    verify_deployment
    echo
    
    run_tests
    echo
    
    log_success "Deployment completed successfully!"
    echo
    echo "Next steps:"
    echo "1. Monitor logs: tail -f $WOWZA_HOME/logs/wowzastreamingengine_access.log | grep -i scte35"
    echo "2. Test SRT ingest: ffmpeg -re -i test.ts -c copy -f mpegts srt://localhost:9999?streamid=test"
    echo "3. Check HLS output: curl http://localhost:8086/live/test/playlist.m3u8"
    echo "4. Run full tests: ./test-scte35-module.sh"
    echo
}

# Script usage
usage() {
    echo "Usage: $0 [OPTIONS]"
    echo
    echo "Options:"
    echo "  -h, --help              Show this help message"
    echo "  -w, --wowza-home PATH   Wowza installation directory (default: $WOWZA_HOME)"
    echo "  -t, --build-tool TOOL   Build tool: maven|gradle (default: $BUILD_TOOL)"
    echo "  -a, --app-name NAME     Wowza application name (default: $APP_NAME)"
    echo "  -n, --no-backup         Skip configuration backup"
    echo "  -s, --skip-tests        Skip deployment tests"
    echo
    echo "Environment variables:"
    echo "  WOWZA_HOME              Wowza installation directory"
    echo "  BUILD_TOOL              Build tool preference"
    echo "  APP_NAME                Wowza application name"
    echo
    echo "Examples:"
    echo "  sudo $0                                    # Deploy with defaults"
    echo "  sudo $0 -t maven -a myapp                  # Use Maven and custom app name"
    echo "  sudo $0 -w /opt/wowza -n                   # Custom Wowza path, no backup"
}

# Parse command line arguments
SKIP_TESTS=false

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
        -t|--build-tool)
            BUILD_TOOL="$2"
            shift 2
            ;;
        -a|--app-name)
            APP_NAME="$2"
            shift 2
            ;;
        -n|--no-backup)
            BACKUP_CONFIG="false"
            shift
            ;;
        -s|--skip-tests)
            SKIP_TESTS=true
            shift
            ;;
        *)
            log_error "Unknown option: $1"
            usage
            exit 1
            ;;
    esac
done

# Validate build tool
if [ "$BUILD_TOOL" != "maven" ] && [ "$BUILD_TOOL" != "gradle" ]; then
    log_error "Invalid build tool: $BUILD_TOOL (must be 'maven' or 'gradle')"
    exit 1
fi

# Skip tests if requested
if [ "$SKIP_TESTS" = true ]; then
    run_tests() {
        log_info "Skipping tests as requested"
    }
fi

# Run main deployment
main

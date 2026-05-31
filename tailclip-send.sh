#!/bin/bash

# Script to send a file to connected devices via TailClip relay server
#
# Usage:
#   ./tailclip-send.sh <file_to_send> [--server HOST] [--to DEVICE_IDS]
#
# Examples:
#   ./tailclip-send.sh photo.jpg                           # send to all devices via localhost
#   ./tailclip-send.sh photo.jpg --server 100.64.0.1       # send via remote server
#   ./tailclip-send.sh photo.jpg --to "uuid1,uuid2"        # send to specific devices

FILE_PATH=""
SERVER="localhost"
PORT="8765"
TO_DEVICES="all"
FROM_DEVICE="cli-script"

# Parse arguments
while [[ $# -gt 0 ]]; do
    case "$1" in
        --server)
            SERVER="$2"
            shift 2
            ;;
        --port)
            PORT="$2"
            shift 2
            ;;
        --to)
            TO_DEVICES="$2"
            shift 2
            ;;
        --from)
            FROM_DEVICE="$2"
            shift 2
            ;;
        *)
            if [ -z "$FILE_PATH" ]; then
                FILE_PATH="$1"
            fi
            shift
            ;;
    esac
done

if [ -z "$FILE_PATH" ]; then
    echo "Usage: $0 <file_to_send> [--server HOST] [--port PORT] [--to DEVICE_IDS] [--from DEVICE_ID]"
    echo ""
    echo "Options:"
    echo "  --server HOST       Server address (default: localhost)"
    echo "  --port PORT         Server port (default: 8765)"
    echo "  --to DEVICE_IDS     Comma-separated device IDs, or 'all' (default: all)"
    echo "  --from DEVICE_ID    Sender device ID (default: cli-script)"
    exit 1
fi

if [ ! -f "$FILE_PATH" ]; then
    echo "Error: File '$FILE_PATH' does not exist."
    exit 1
fi

echo "Sending '$FILE_PATH' to TailClip server ($SERVER:$PORT) → targets: $TO_DEVICES"
curl -X POST \
    -F "file=@$FILE_PATH" \
    -F "from_device=$FROM_DEVICE" \
    -F "to_devices=$TO_DEVICES" \
    "http://$SERVER:$PORT/push-file"
echo ""

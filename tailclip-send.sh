#!/bin/bash

# Script to send a file to the connected Android device via TailClip backend

if [ $# -eq 0 ]; then
    echo "Usage: $0 <file_to_send>"
    exit 1
fi

FILE_PATH="$1"

if [ ! -f "$FILE_PATH" ]; then
    echo "Error: File '$FILE_PATH' does not exist."
    exit 1
fi

echo "Sending '$FILE_PATH' to TailClip..."
curl -X POST -F "file=@$FILE_PATH" http://localhost:8765/push-to-phone
echo ""

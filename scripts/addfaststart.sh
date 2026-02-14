#!/bin/bash

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

# Counter variables
total=0
already_faststart=0
converted=0
failed=0

# Options
RECURSIVE=false
DRY_RUN=false

# Usage function
usage() {
    echo "Usage: $0 [OPTIONS]"
    echo ""
    echo "Options:"
    echo "  -r    Recursive mode (search subdirectories)"
    echo "  -d    Dry run mode (show what would be done without making changes)"
    echo "  -h    Show this help message"
    echo ""
    exit 1
}

# Parse command line options
while getopts "rdh" opt; do
    case $opt in
        r)
            RECURSIVE=true
            ;;
        d)
            DRY_RUN=true
            ;;
        h)
            usage
            ;;
        \?)
            echo "Invalid option: -$OPTARG" >&2
            usage
            ;;
    esac
done

# Print header
if [ "$DRY_RUN" = true ]; then
    echo -e "${BLUE}DRY RUN MODE - No changes will be made${NC}"
fi

if [ "$RECURSIVE" = true ]; then
    echo "Checking MP4 files for faststart (recursive)..."
else
    echo "Checking MP4 files for faststart (current directory only)..."
fi
echo "=================================="
echo ""

# Function to check and convert a file
check_file() {
    local file="$1"

    # Skip hidden files (files starting with .)
    local basename=$(basename "$file")
    if [[ "$basename" == .* ]]; then
        return
    fi

    ((total++))

    echo -n "Checking: $file ... "

    # Check if faststart is enabled
    first_atom=$(ffmpeg -v trace -i "$file" 2>&1 | grep -E "type:'(mdat|moov)'" | head -1 || true)

    if echo "$first_atom" | grep -q "moov"; then
        echo -e "${GREEN}✓ Already has faststart${NC}"
        ((already_faststart++))
    else
        echo -e "${YELLOW}✗ Missing faststart${NC}"

        if [ "$DRY_RUN" = true ]; then
            echo -e "  ${BLUE}[DRY RUN] Would convert this file${NC}"
            echo -e "  ${BLUE}[DRY RUN] Would create backup: ${file}.backup${NC}"
            ((converted++))
        else
            echo -e "  ${YELLOW}→ Converting...${NC}"

            dir=$(dirname "$file")
            filename=$(basename "$file" .mp4)
            temp_output="${dir}/${filename}_faststart_temp.mp4"
            backup_name="${file}.backup"

            if ffmpeg -i "$file" -c copy -map 0 -movflags "+faststart" -y "$temp_output" -hide_banner -loglevel error; then
                mv "$file" "$backup_name"
                mv "$temp_output" "$file"
                echo -e "  ${GREEN}→ Converted successfully${NC}"
                echo -e "  ${YELLOW}→ Original backed up as: $backup_name${NC}"
                ((converted++))
            else
                echo -e "  ${RED}→ Failed to convert!${NC}"
                [ -f "$temp_output" ] && rm "$temp_output"
                ((failed++))
            fi
        fi
    fi
    echo ""
}

# Find and process files
if [ "$RECURSIVE" = true ]; then
    # Recursive: find all mp4 files, excluding hidden files and backups
    find . -type f -name "*.mp4" ! -name "*.backup" ! -path "*/.*" -print0 | while IFS= read -r -d '' file; do
        check_file "$file"
    done
else
    # Non-recursive: only current directory
    for file in *.mp4; do
        # Skip if no mp4 files found
        [ -e "$file" ] || continue

        # Skip backup files
        if [[ "$file" == *.backup ]]; then
            continue
        fi

        check_file "$file"
    done
fi

# Summary
echo "=================================="
echo "Summary:"
echo "  Total files checked: $total"
echo -e "  Already faststart: ${GREEN}$already_faststart${NC}"

if [ "$DRY_RUN" = true ]; then
    echo -e "  Would convert: ${BLUE}$converted${NC}"
else
    echo -e "  Converted: ${YELLOW}$converted${NC}"
fi

if [ $failed -gt 0 ]; then
    echo -e "  Failed: ${RED}$failed${NC}"
fi

if [ "$DRY_RUN" = false ] && [ $converted -gt 0 ]; then
    echo ""
    echo "To remove all backup files, run:"
    if [ "$RECURSIVE" = true ]; then
        echo "  find . -type f -name \"*.mp4.backup\" -delete"
    else
        echo "  rm *.mp4.backup"
    fi
fi

if [ "$DRY_RUN" = true ] && [ $converted -gt 0 ]; then
    echo ""
    echo -e "${BLUE}This was a dry run. Run without -d to actually convert files.${NC}"
fi
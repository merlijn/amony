#!/bin/bash
# Regenerates the video fixtures used by StreamabilitySpec. Requires ffmpeg on the PATH.
# The fixtures are committed to git, so this only needs to run when the fixtures must change.

set -euo pipefail

OUT="$(cd "$(dirname "$0")/.." && pwd)/backend/src/test/resources/video"
mkdir -p "$OUT"
cd "$OUT"

ffmpeg -v error -f lavfi -i testsrc=size=160x120:rate=15 -t 1 -c:v libx264 -pix_fmt yuv420p -y mp4_moov_at_end.mp4
ffmpeg -v error -i mp4_moov_at_end.mp4 -c copy -movflags +faststart -y mp4_streamable.mp4
ffmpeg -v error -f lavfi -i testsrc=size=160x120:rate=15 -t 1 -c:v libx264 -pix_fmt yuv420p -movflags frag_keyframe+empty_moov -y mp4_fragmented.mp4

ffmpeg -v error -f lavfi -i testsrc=size=160x120:rate=15 -t 1 -c:v libvpx-vp9 -b:v 200k -y webm_cues_at_end.webm
ffmpeg -v error -i webm_cues_at_end.webm -c copy -cues_to_front 1 -f webm -y webm_streamable.webm

ffmpeg -v error -f lavfi -i testsrc=size=160x120:rate=15 -t 1 -c:v libvpx-vp9 -b:v 200k -y mkv_cues_at_end.mkv
ffmpeg -v error -i mkv_cues_at_end.mkv -c copy -cues_to_front 1 -f matroska -y mkv_streamable.mkv

printf 'this is definitely not a video file, just some bytes to sniff\n' > not_a_video.bin

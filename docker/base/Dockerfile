FROM eclipse-temurin:25-jre-noble

# Ubuntu 24.04 ships ffmpeg 6.x, which bundles the encoders/muxers the thumbnail pipeline relies on:
# libsvtav1 (AVIF), libjxl (JPEG XL) and the AVIF muxer. Ubuntu 22.04's ffmpeg 4.4 has none of these.
RUN set -eux; \
	apt-get update; \
	apt-get install -y --no-install-recommends ffmpeg imagemagick; \
	rm -rf /var/lib/apt/lists/*
FROM eclipse-temurin:25-jre-noble

# Ubuntu 24.04 ships ffmpeg 6.x, which bundles the encoders/muxers the thumbnail pipeline relies on:
# libsvtav1 (AVIF), libjxl (JPEG XL) and the AVIF muxer. Ubuntu 22.04's ffmpeg 4.4 has none of these.
# mesa-va-drivers (AMD) and intel-media-va-driver (Intel, the iHD driver) let ffmpeg decode previews on
# the GPU's media block; vainfo is the diagnostic for checking that the render node works. The Intel
# driver is x86-only, so the arm64 image gets only the AMD one.
RUN set -eux; \
	apt-get update; \
	apt-get install -y --no-install-recommends ffmpeg imagemagick libva-drm2 mesa-va-drivers vainfo; \
	if [ "$(dpkg --print-architecture)" = amd64 ]; then \
		apt-get install -y --no-install-recommends intel-media-va-driver; \
	fi; \
	rm -rf /var/lib/apt/lists/*
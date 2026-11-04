# Runtime-only image - server.js/config.json/cache files live in the
# bind-mounted /home/pi tree (see docker-compose.yml), not COPYed in
# here. Only real addition over stock node:22-slim is ffmpeg/ffprobe,
# which this service shells out to directly on the host today (video
# fps normalization + webcam clip capture, see INSTALL.md).
FROM node:22-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
		ffmpeg \
		ca-certificates \
	&& rm -rf /var/lib/apt/lists/*

# Node runs the app; npm is not used at run time (npm ci runs on the host).
# Remove it: the npm in node:22-slim carries the vulnscan findings.
RUN rm -rf /usr/local/lib/node_modules/npm /usr/local/bin/npm /usr/local/bin/npx

WORKDIR /home/pi/pi-video-gate
CMD ["node", "server.js"]

# Runtime-only image - server.js/routes/lib/config live in the bind-mounted
# working directory (see docker-compose.yml), not COPYed in here.
FROM node:22-slim

# ffmpeg is not in node:22-slim's base Debian image - routes/photos.js
# shells out to it to normalize every uploaded video to 8-bit SDR H.264
# (see transcodeVideo() there for why - some phone HDR exports decode
# audio fine but never produce a visible video frame on this app's
# target hardware).
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
CMD ["node", "server.js"]

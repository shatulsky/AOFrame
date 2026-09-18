# Runtime-only image - server.js/routes/lib/config live in the bind-mounted
# working directory (see docker-compose.yml), not COPYed in here.
FROM node:22-slim

WORKDIR /app
CMD ["node", "server.js"]

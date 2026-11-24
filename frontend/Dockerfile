# syntax=docker/dockerfile:1

# ---- Build: type-check and build the Vue app into dist/ ----
FROM node:25-slim AS build
WORKDIR /build
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY index.html tsconfig.json vite.config.ts ./
COPY src src
RUN npm run build

# ---- Run: the Node server with production dependencies only, non-root ----
FROM node:25-slim
ENV NODE_ENV=production
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --omit=dev --no-audit --no-fund && npm cache clean --force
COPY server server
# The tests live next to the server but aren't needed at runtime.
RUN rm -f server/*.test.js
COPY --from=build /build/dist dist
USER node
# Settings come from environment variables: PORT (default 3000) and BACKEND_URL (where /api is sent).
ENV PORT=3000
EXPOSE 3000
CMD ["node", "server/index.js"]

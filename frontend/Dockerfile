# Build stage
# Node runs the build; bun is only the installer. Running `next build` under
# bun's runtime segfaults in containers (oven-sh/bun#37031, worker teardown).
FROM node:22-alpine AS builder

COPY --from=oven/bun:1-alpine /usr/local/bin/bun /usr/local/bin/

WORKDIR /app

COPY package.json bun.lock ./
RUN bun install --frozen-lockfile

COPY . .
RUN node_modules/.bin/next build

# Runtime stage
FROM node:22-alpine

RUN addgroup -S appgroup \
    && adduser -S appuser -G appgroup

WORKDIR /app

COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static

USER appuser

EXPOSE 3000

ENV NODE_ENV=production \
    HOSTNAME=0.0.0.0 \
    PORT=3000

HEALTHCHECK --interval=30s --timeout=5s --start-period=15s --retries=3 \
  CMD wget -qO- http://localhost:3000/api/health || exit 1

ENTRYPOINT ["node", "server.js"]

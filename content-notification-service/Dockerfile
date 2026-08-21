# Build stage — needs devDependencies to compile TypeScript
FROM node:24-alpine AS builder

WORKDIR /app

COPY package*.json ./
# Install ALL deps (including devDependencies) for TypeScript compilation
RUN npm ci

COPY tsconfig*.json ./
COPY src ./src

RUN npm run build

# Production stage — only compiled output and production deps
FROM node:24-alpine

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=builder /app/dist ./dist

# Non-root user
RUN addgroup -g 1001 -S appuser && \
    adduser -S -u 1001 -G appuser appuser && \
    chown -R appuser:appuser /app

USER appuser

EXPOSE 3003

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://localhost:3003/health/ready').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "dist/index.js"]

FROM node:22-alpine AS builder

WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY tsconfig*.json ./
COPY src ./src
COPY migrations ./migrations

RUN npm run build

FROM node:22-alpine AS runtime

ENV NODE_ENV=production

WORKDIR /app

COPY package*.json ./
RUN npm ci --omit=dev && npm cache clean --force

COPY --from=builder /app/dist ./dist
COPY migrations ./migrations

RUN addgroup -g 1001 -S appuser && \
    adduser -S -u 1001 -G appuser appuser && \
    chown -R appuser:appuser /app

USER appuser

EXPOSE 3003
STOPSIGNAL SIGTERM

HEALTHCHECK --interval=30s --timeout=3s --start-period=10s --retries=3 \
  CMD node -e "fetch('http://localhost:3003/health/ready').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))"

CMD ["node", "dist/main.js"]

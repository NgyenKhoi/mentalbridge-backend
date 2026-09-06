FROM node:24-alpine AS build

WORKDIR /app

COPY package.json package-lock.json ./
RUN npm ci

COPY nest-cli.json tsconfig.json tsconfig.build.json ./
COPY src ./src

RUN npm run build && npm prune --omit=dev

FROM node:24-alpine AS runtime

ENV NODE_ENV=production
ENV REALTIME_PORT=3004

WORKDIR /app

COPY --from=build --chown=node:node /app/package.json /app/package-lock.json ./
COPY --from=build --chown=node:node /app/node_modules ./node_modules
COPY --from=build --chown=node:node /app/dist ./dist
COPY --chown=node:node migrate-mongo-config.cjs ./migrate-mongo-config.cjs
COPY --chown=node:node migrations ./migrations

USER node

EXPOSE 3004
STOPSIGNAL SIGTERM

CMD ["node", "dist/main.js"]

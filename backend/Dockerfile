# syntax=docker/dockerfile:1.7
# One image for both roles (ROLE=api or ROLE=worker). Engines are installed from Debian packages.

FROM node:22-bookworm-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY tsconfig.json tsconfig.build.json ./
COPY src ./src
RUN npm run build && npm prune --omit=dev

FROM node:22-bookworm-slim AS runtime
ENV NODE_ENV=production \
    PYTHON_BIN=/opt/venv/bin/python \
    WORK_DIR=/tmp/omd-work
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      libreoffice-writer-nogui libreoffice-draw-nogui \
      tesseract-ocr tesseract-ocr-eng tesseract-ocr-hin \
      poppler-utils qpdf ghostscript \
      fonts-noto-core fonts-noto-extra fonts-dejavu-core \
      python3 python3-venv tini ca-certificates \
 && python3 -m venv /opt/venv \
 && /opt/venv/bin/pip install --no-cache-dir pdf2docx==0.5.13 python-docx==1.2.0 \
 && apt-get clean && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/dist ./dist
COPY migrations ./migrations
COPY scripts ./scripts
COPY package.json ./
# Unprivileged user; the app only writes to WORK_DIR and (local driver) STORAGE_LOCAL_DIR.
RUN useradd --system --uid 10001 --home-dir /tmp omd && mkdir -p /tmp/omd-work /data && chown omd /tmp/omd-work /data
USER omd
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s CMD node -e "fetch('http://127.0.0.1:'+(process.env.PORT||8080)+'/api/v1/health/live').then(r=>process.exit(r.ok?0:1)).catch(()=>process.exit(1))" || exit 1
ENTRYPOINT ["/usr/bin/tini", "--"]
CMD ["node", "dist/main.js"]

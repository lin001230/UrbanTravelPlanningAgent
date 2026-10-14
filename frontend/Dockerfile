# Build context must be frontend/ (not the repository root).
FROM node:22-alpine AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY index.html vite.config.js ./
COPY public ./public
COPY src ./src
# Keep browser requests same-origin; Nginx selects the backend at runtime.
ENV VITE_API_BASE_URL=/api
RUN npm run build

FROM nginx:1.28-alpine AS runtime
RUN apk add --no-cache ca-certificates
ENV NGINX_ENVSUBST_FILTER=^BACKEND_ORIGIN$
COPY nginx/default.conf.template /etc/nginx/templates/default.conf.template
COPY docker-entrypoint.d/15-check-backend.envsh /docker-entrypoint.d/15-check-backend.envsh
# Windows checkouts may use CRLF. The official entrypoint sources executable .envsh files.
RUN sed -i 's/\r$//' /docker-entrypoint.d/15-check-backend.envsh \
    && chmod +x /docker-entrypoint.d/15-check-backend.envsh
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]

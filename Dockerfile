# Self-hostable rendezvous server. Single static binary; see docs/HOSTING.md.
FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /latchway-rendezvous ./cmd/latchway-rendezvous

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /latchway-rendezvous /latchway-rendezvous
# Certificates for --domain mode are cached here; mount a volume to keep them.
VOLUME ["/certs"]
ENV LATCHWAY_CERT_DIR=/certs
EXPOSE 80 443 8080
USER nonroot
ENTRYPOINT ["/latchway-rendezvous"]

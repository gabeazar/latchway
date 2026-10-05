// latchway-rendezvous is the self-hostable rendezvous server: one static
// binary that relays encrypted WebRTC handshakes between Latchway peers and
// serves the public pages. It keeps no state and writes no logs that could
// identify a transfer.
//
// Minimal:   latchway-rendezvous --listen :8080          (behind a reverse proxy)
// Standalone: latchway-rendezvous --domain files.example.org   (Let's Encrypt on :443)
package main

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"flag"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"strings"
	"syscall"
	"time"

	"golang.org/x/crypto/acme/autocert"

	"github.com/gabeazar/latchway/internal/server"
	"github.com/gabeazar/latchway/internal/wire"
	"github.com/gabeazar/latchway/web"
)

func main() {
	listen := flag.String("listen", envOr("LATCHWAY_LISTEN", ":8080"), "address for plain HTTP (used when --domain is empty)")
	domain := flag.String("domain", os.Getenv("LATCHWAY_DOMAIN"), "public domain; enables automatic HTTPS on :443 with Let's Encrypt")
	certDir := flag.String("cert-dir", envOr("LATCHWAY_CERT_DIR", "./certs"), "where to cache certificates")
	acmeEmail := flag.String("acme-email", os.Getenv("LATCHWAY_ACME_EMAIL"), "contact email for Let's Encrypt (optional)")
	hostToken := flag.String("host-token", os.Getenv("LATCHWAY_HOST_TOKEN"), "if set, senders must present this token (private server)")
	turnKey := flag.String("turn-key-id", os.Getenv("LATCHWAY_TURN_KEY_ID"), "Cloudflare TURN key id (optional)")
	turnToken := flag.String("turn-api-token", os.Getenv("LATCHWAY_TURN_KEY_API_TOKEN"), "Cloudflare TURN key API token (optional)")
	iceJSON := flag.String("ice-servers", os.Getenv("LATCHWAY_ICE_SERVERS"), "JSON array of extra ICE servers, e.g. your own TURN")
	budgetGB := flag.Float64("turn-budget-gb", envFloat("LATCHWAY_TURN_BUDGET_GB"), "withhold Cloudflare TURN once this month's egress reaches this many GB (0: no cap)")
	cfAccount := flag.String("cf-account-id", os.Getenv("LATCHWAY_CF_ACCOUNT_ID"), "Cloudflare account id, for the TURN usage query")
	cfAnalytics := flag.String("cf-analytics-token", os.Getenv("LATCHWAY_CF_ANALYTICS_TOKEN"), "Cloudflare API token with Account Analytics: Read, for the TURN usage query")
	pkg := flag.String("android-package", envOr("LATCHWAY_ANDROID_PACKAGE", "app.latchway"), "Android application id for assetlinks.json")
	fps := flag.String("assetlinks", os.Getenv("LATCHWAY_ASSETLINKS_FINGERPRINTS"), "comma-separated SHA-256 signing fingerprints for App Links")
	flag.Parse()

	ice := &server.ICEProvider{TurnKeyID: *turnKey, TurnAPIToken: *turnToken}
	if *budgetGB > 0 {
		ice.Budget = &server.TURNBudget{
			BudgetBytes: int64(*budgetGB * server.GB),
			AccountID:   *cfAccount,
			Token:       *cfAnalytics,
			Logf:        log.Printf,
		}
		if *cfAccount == "" || *cfAnalytics == "" {
			log.Printf("warning: --turn-budget-gb is set without --cf-account-id and --cf-analytics-token; TURN will be withheld")
		}
	}
	if *iceJSON != "" {
		var extra []wire.ICEServer
		if err := json.Unmarshal([]byte(*iceJSON), &extra); err != nil {
			log.Fatalf("--ice-servers is not a JSON array of ICE servers: %v", err)
		}
		ice.Static = extra
	}

	var fingerprints []string
	for _, f := range strings.Split(*fps, ",") {
		if f = strings.ToUpper(strings.TrimSpace(f)); f != "" {
			fingerprints = append(fingerprints, f)
		}
	}

	srv := server.New(server.Options{
		HostToken:      *hostToken,
		ICE:            ice.Servers,
		Assets:         web.FS,
		AndroidPackage: *pkg,
		Fingerprints:   fingerprints,
		Logf:           log.Printf,
	})

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	if *domain == "" {
		h := &http.Server{Addr: *listen, Handler: srv, ReadHeaderTimeout: 10 * time.Second}
		go func() {
			<-ctx.Done()
			shutdown(h)
		}()
		log.Printf("latchway-rendezvous listening on %s (plain HTTP; put TLS in front of it)", *listen)
		if err := h.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
		return
	}

	m := &autocert.Manager{
		Prompt:     autocert.AcceptTOS,
		HostPolicy: autocert.HostWhitelist(*domain, "www."+*domain),
		Cache:      autocert.DirCache(*certDir),
		Email:      *acmeEmail,
	}
	httpsSrv := &http.Server{
		Addr:              ":443",
		Handler:           srv,
		ReadHeaderTimeout: 10 * time.Second,
		TLSConfig: &tls.Config{
			GetCertificate: m.GetCertificate,
			MinVersion:     tls.VersionTLS12,
			NextProtos:     []string{"h2", "http/1.1", "acme-tls/1"},
		},
	}
	httpSrv := &http.Server{
		Addr:              ":80",
		Handler:           m.HTTPHandler(nil), // ACME challenges + redirect to https
		ReadHeaderTimeout: 10 * time.Second,
	}
	go func() {
		<-ctx.Done()
		shutdown(httpsSrv)
		shutdown(httpSrv)
	}()
	go func() {
		if err := httpSrv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatal(err)
		}
	}()
	log.Printf("latchway-rendezvous serving https://%s", *domain)
	if err := httpsSrv.ListenAndServeTLS("", ""); err != nil && err != http.ErrServerClosed {
		log.Fatal(err)
	}
}

func shutdown(s *http.Server) {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	_ = s.Shutdown(ctx)
}

func envOr(key, def string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return def
}

func envFloat(key string) float64 {
	v, err := strconv.ParseFloat(strings.TrimSpace(os.Getenv(key)), 64)
	if err != nil {
		return 0
	}
	return v
}

func init() {
	flag.Usage = func() {
		fmt.Fprintf(os.Stderr, "latchway-rendezvous: self-hosted rendezvous server for Latchway\n\n")
		flag.PrintDefaults()
	}
}

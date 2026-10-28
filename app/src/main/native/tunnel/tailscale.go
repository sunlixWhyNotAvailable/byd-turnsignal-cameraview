package main

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/netip"
	"net/url"
	"strings"
	"sync"
	"time"

	"tailscale.com/ipn"
	"tailscale.com/ipn/ipnstate"
	"tailscale.com/tailcfg"
	"tailscale.com/tsnet"
)

const (
	tailscaleStatePrefix      = "tunnel.tailscale.state."
	tailscaleOperationTimeout = 30 * time.Second
	tailscaleStatusTimeout    = 2 * time.Second
	proxyCredentialHeader     = "X-Byd-Extend-Proxy-Token"
	tailnetUserHeader         = "X-Byd-Extend-Tailnet-User"
)

type tailscaleProvider struct {
	instanceID      string
	serving         *servingRun
	runtimeDir      string
	server          *tsnet.Server
	listener        net.Listener
	httpServer      *http.Server
	name            string
	address         string
	public          bool
	operationMu     sync.Mutex
	operationCancel context.CancelFunc
	cancelPending   bool
}

func newTailscaleProvider(runtimeDir string) *tailscaleProvider {
	return &tailscaleProvider{runtimeDir: runtimeDir}
}

func (p *tailscaleProvider) beginOperation() (context.Context, func()) {
	ctx, cancel := context.WithTimeout(context.Background(), tailscaleOperationTimeout)
	p.operationMu.Lock()
	cancelPending := p.cancelPending
	p.cancelPending = false
	p.operationCancel = cancel
	p.operationMu.Unlock()
	if cancelPending {
		cancel()
	}
	return ctx, func() {
		cancel()
		p.operationMu.Lock()
		p.operationCancel = nil
		p.operationMu.Unlock()
	}
}

func (p *tailscaleProvider) cancelOperation() {
	p.operationMu.Lock()
	cancel := p.operationCancel
	if cancel == nil {
		p.cancelPending = true
	}
	p.operationMu.Unlock()
	if cancel != nil {
		cancel()
	}
}

func (p *tailscaleProvider) clearPendingCancellation() {
	p.operationMu.Lock()
	p.cancelPending = false
	p.operationMu.Unlock()
}

func (p *tailscaleProvider) Authorize(name, credential string) tunnelResult {
	ctx, finish := p.beginOperation()
	defer finish()
	if ctx.Err() != nil {
		return tailscaleOperationFailure(ctx, "tailscale_authorize_failed", "authorize", ctx.Err(), credential)
	}
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	if p.server != nil && p.name != name {
		if p.httpServer != nil {
			return failureWithReason("provider_unavailable", "authorize.replace_server", "the current Tailscale server is serving another device name", credential)
		}
		if err := p.discardServer(); err != nil {
			return failureWithDiagnostic("provider_unavailable", "authorize.replace_server", err, credential)
		}
	}
	if p.server == nil {
		p.newServer(name, credential)
	}
	if err := p.server.Start(); err != nil {
		p.server.AuthKey = ""
		result := tailscaleOperationFailure(ctx, "tailscale_start_failed", "authorize.server_start", err, credential)
		return addSecondaryDiagnostic(result, "authorize.close_after_server_start_failure", p.discardServer(), credential)
	}
	// Auth keys are one-shot input; the long-lived node identity is kept only by Store.
	p.server.AuthKey = ""
	return p.waitForStatus(ctx, "authorize", credential)
}

func (p *tailscaleProvider) Status(name string) tunnelResult {
	if p.serving != nil {
		if result := p.serving.failure.Load(); result != nil {
			return *result
		}
	}
	ctx, finish := p.beginOperation()
	defer finish()
	if ctx.Err() != nil {
		return tailscaleOperationFailure(ctx, "tailscale_status_failed", "status", ctx.Err())
	}
	return p.status(ctx, name)
}

func (p *tailscaleProvider) status(ctx context.Context, name string, secrets ...string) tunnelResult {
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	if p.server != nil && p.name != name && p.httpServer != nil {
		return failureWithReason("provider_unavailable", "status.replace_server", "the current Tailscale server is serving another device name", secrets...)
	}
	if p.server == nil || p.name != name {
		if p.server != nil {
			if err := p.discardServer(); err != nil {
				return failureWithDiagnostic("provider_unavailable", "status.replace_server", err, secrets...)
			}
		}
		p.newServer(name, "")
	}
	if err := p.server.Start(); err != nil {
		result := tailscaleOperationFailure(ctx, "tailscale_start_failed", "status.server_start", err, secrets...)
		return addSecondaryDiagnostic(result, "status.close_after_server_start_failure", p.discardServer(), secrets...)
	}
	return p.waitForStatus(ctx, "status", secrets...)
}

func (p *tailscaleProvider) waitForStatus(ctx context.Context, stage string, secrets ...string) tunnelResult {
	client, err := p.server.LocalClient()
	if err != nil {
		return tailscaleOperationFailure(ctx, "tailscale_local_client_failed", stage+".local_client", err, secrets...)
	}
	statusCtx, cancel := context.WithTimeout(ctx, tailscaleStatusTimeout)
	defer cancel()
	status, err := client.Status(statusCtx)
	if err != nil {
		return tailscaleOperationFailure(ctx, "tailscale_status_failed", stage+".client_status", err, secrets...)
	}
	if status == nil {
		return tunnelResult{State: "starting"}
	}
	if status.AuthURL != "" {
		return tunnelResult{State: "login_required", LoginURL: status.AuthURL}
	}
	switch status.BackendState {
	case ipn.NeedsLogin.String():
		return tunnelResult{State: "login_required"}
	case ipn.NeedsMachineAuth.String():
		return tunnelResult{State: "approval_pending"}
	case ipn.Running.String():
		return tunnelResult{State: "authorized", Address: tailscaleAddress(status)}
	case ipn.NoState.String(), ipn.Starting.String():
		return tunnelResult{State: "starting"}
	case ipn.Stopped.String():
		return failureWithReason("tailscale_backend_stopped", stage+".client_status", "backend_state=Stopped", secrets...)
	default:
		return failureWithReason("tailscale_backend_state_unknown", stage+".client_status", "backend_state="+status.BackendState, secrets...)
	}
}

func (p *tailscaleProvider) Start(name string, localPort int, public bool, privateProxyCredential string) tunnelResult {
	ctx, finish := p.beginOperation()
	defer finish()
	if ctx.Err() != nil {
		return tailscaleOperationFailure(ctx, "tailscale_start_failed", "start", ctx.Err(), privateProxyCredential)
	}
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	if localPort < 1 || localPort > 65535 || !localHTTPServerAvailable(localPort) {
		return failure("invalid_port")
	}
	if !public && privateProxyCredential == "" {
		return failure("private_credential_missing")
	}
	if p.serving != nil {
		if failed := p.serving.failure.Load(); failed != nil {
			return *failed
		}
	}
	if p.httpServer != nil {
		if p.name == name && p.public == public {
			return tunnelResult{State: "serving", Address: p.address}
		}
		return failureWithReason("provider_unavailable", "start.replace_server", "the current Tailscale server is already serving a different configuration", privateProxyCredential)
	}
	statusResult := p.status(ctx, name, privateProxyCredential)
	if statusResult.State != "authorized" {
		return statusResult
	}
	if p.server == nil {
		return failureWithReason("not_authorized", "start", "Tailscale server is unavailable", privateProxyCredential)
	}
	status, err := p.server.Up(ctx)
	if err != nil {
		return tailscaleOperationFailure(ctx, "tailscale_up_failed", "start.up", err, privateProxyCredential)
	}
	address := tailscaleAddress(status)
	if address == "" {
		return failureWithReason("tailscale_https_failed", "start.status", "running backend status has no HTTPS certificate domain", privateProxyCredential)
	}
	listener, err := p.listen(ctx, public)
	if err != nil {
		if ctx.Err() != nil {
			// listen closes tsnet on cancellation and waits for its contextless
			// listener call to return; drop only the in-memory server handle.
			p.server = nil
			p.address = ""
			return tailscaleOperationFailure(ctx, "tailscale_listen_failed", "start.listen", err, privateProxyCredential)
		}
		if public {
			return failureWithDiagnostic(funnelFailureCode(err), "start.listen_funnel", err, privateProxyCredential)
		}
		return failureWithDiagnostic("tailscale_https_failed", "start.listen_tls", err, privateProxyCredential)
	}
	proxy, err := newLoopbackProxy(localPort, "tailscale", p.instanceID)
	if err != nil {
		result := failureWithDiagnostic("invalid_port", "start.local_proxy", err, privateProxyCredential)
		return addSecondaryDiagnostic(result, "start.close_listener_after_proxy_failure", listener.Close(), privateProxyCredential)
	}
	handler := p.transportDiagnosticsHandler(proxy, public)
	if !public {
		handler = p.privateAdmissionHandler(handler, privateProxyCredential)
	}
	server := &http.Server{
		Handler:           handler,
		ReadHeaderTimeout: 10 * time.Second,
		ErrorLog:          log.New(diagnosticWriter{"tailscale", "https generation=" + p.instanceID}, "", 0),
	}
	p.listener = listener
	p.httpServer = server
	p.address = address
	p.public = public
	p.name = name
	p.serving = serveTunnel("tailscale", p.instanceID, server, listener)
	return tunnelResult{State: "serving", Address: address}
}

func (p *tailscaleProvider) listen(ctx context.Context, public bool) (net.Listener, error) {
	type listenResult struct {
		listener net.Listener
		err      error
	}
	server := p.server
	completed := make(chan listenResult, 1)
	go func() {
		var listener net.Listener
		var err error
		if public {
			listener, err = server.ListenFunnel("tcp", ":443")
		} else {
			listener, err = server.ListenTLS("tcp", ":443")
		}
		if ctx.Err() != nil && listener != nil {
			_ = listener.Close()
			listener = nil
		}
		completed <- listenResult{listener: listener, err: err}
	}()
	select {
	case result := <-completed:
		if ctx.Err() != nil {
			if result.listener != nil {
				_ = result.listener.Close()
			}
			return nil, ctx.Err()
		}
		return result.listener, result.err
	case <-ctx.Done():
		// tsnet.ListenTLS/ListenFunnel use a background context internally; Close releases that wait.
		closeErr := server.Close()
		result := <-completed
		if result.listener != nil {
			if err := result.listener.Close(); err != nil {
				closeErr = errors.Join(closeErr, fmt.Errorf("close late tsnet listener: %w", err))
			}
		}
		if closeErr != nil {
			return nil, fmt.Errorf("close tsnet operation: %w", closeErr)
		}
		return nil, ctx.Err()
	}
}

func tailscaleOperationFailure(ctx context.Context, code, stage string, err error, secrets ...string) tunnelResult {
	if errors.Is(ctx.Err(), context.Canceled) {
		if err != nil && !errors.Is(err, context.Canceled) {
			return failureWithDiagnostic("provider_unavailable", stage, errors.Join(ctx.Err(), err), secrets...)
		}
		return tunnelResult{State: "cancelled", ErrorDetail: nativeErrorDetail(stage, err, secrets...)}
	}
	if errors.Is(ctx.Err(), context.DeadlineExceeded) {
		return failureWithDiagnostic("tailscale_operation_timeout", stage, errors.Join(ctx.Err(), err), secrets...)
	}
	return failureWithDiagnostic(code, stage, err, secrets...)
}

func (p *tailscaleProvider) privateAdmissionHandler(next http.Handler, privateProxyCredential string) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		// The caller cannot choose either trusted header; they are replaced only after WhoIs.
		r.Header.Del(proxyCredentialHeader)
		r.Header.Del(tailnetUserHeader)
		client, err := p.server.LocalClient()
		if err != nil {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
		defer cancel()
		peer, err := client.WhoIs(ctx, r.RemoteAddr)
		if err != nil || peer == nil || peer.UserProfile == nil || strings.TrimSpace(peer.UserProfile.LoginName) == "" {
			http.Error(w, "forbidden", http.StatusForbidden)
			return
		}
		r.Header.Set(proxyCredentialHeader, privateProxyCredential)
		r.Header.Set(tailnetUserHeader, peer.UserProfile.LoginName)
		next.ServeHTTP(w, r)
	})
}

type transportDiagnosticResponse struct {
	http.ResponseWriter
	status int
}

func (w *transportDiagnosticResponse) WriteHeader(status int) {
	w.status = status
	w.ResponseWriter.WriteHeader(status)
}

func (w *transportDiagnosticResponse) Unwrap() http.ResponseWriter { return w.ResponseWriter }

func (p *tailscaleProvider) transportDiagnosticsHandler(next http.Handler, public bool) http.Handler {
	server, generation := p.server, p.instanceID
	var mu sync.Mutex
	var last time.Time
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodPost || (r.URL.Path != "/live" && r.URL.Path != "/diagnostic") {
			next.ServeHTTP(w, r)
			return
		}
		response := &transportDiagnosticResponse{ResponseWriter: w, status: http.StatusOK}
		next.ServeHTTP(response, r)
		if response.status != http.StatusOK {
			return
		}
		mu.Lock()
		eligible := time.Since(last) >= 30*time.Second
		if eligible {
			last = time.Now()
		}
		mu.Unlock()
		if !eligible {
			return
		}
		// Observe only accepted requests, off the serving path; never log peer addresses or keys.
		remote, reason := r.RemoteAddr, r.URL.Path[1:]
		go func() {
			stage := "transport generation=" + generation + " reason=" + reason
			if public {
				nativeDiagnostic("tailscale", stage, "route=funnel viewer_peer_metrics=unavailable")
				return
			}
			address, err := netip.ParseAddrPort(remote)
			if err != nil {
				nativeDiagnostic("tailscale", stage, nativeErrorDetail("peer_address", err, remote))
				return
			}
			client, err := server.LocalClient()
			if err != nil {
				nativeDiagnostic("tailscale", stage, nativeErrorDetail("local_client", err))
				return
			}
			ctx, cancel := context.WithTimeout(context.Background(), tailscaleStatusTimeout)
			defer cancel()
			status, err := client.Status(ctx)
			if err != nil {
				nativeDiagnostic("tailscale", stage, nativeErrorDetail("peer_status", err))
				return
			}
			if status == nil {
				nativeDiagnostic("tailscale", stage, "peer_metrics=unavailable reason=empty_status")
				return
			}
			for _, peer := range status.Peer {
				if peer == nil {
					continue
				}
				for _, ip := range peer.TailscaleIPs {
					if ip.Unmap() != address.Addr().Unmap() {
						continue
					}
					detail := fmt.Sprintf("direct_endpoint_known=%t peer_relay_known=%t derp_region_known=%t active=%t peer_tx_bytes_total=%d peer_rx_bytes_total=%d",
						peer.CurAddr != "", peer.PeerRelay != "", peer.Relay != "", peer.Active, peer.TxBytes, peer.RxBytes)
					// TSMP measures the existing data path without a disco endpoint-discovery probe.
					ping, err := client.Ping(ctx, ip, tailcfg.PingTSMP)
					if err != nil {
						detail += " " + nativeErrorDetail("peer_ping", err, remote, ip.String(), peer.HostName, peer.DNSName)
					} else if ping == nil {
						detail += " rtt_ms=unknown reason=empty_ping"
					} else if ping.Err != "" {
						detail += " " + nativeErrorDetail("peer_ping", errors.New(ping.Err), remote, ip.String(), peer.HostName, peer.DNSName)
					} else {
						detail += fmt.Sprintf(" rtt_ms=%.3f", ping.LatencySeconds*1000)
					}
					nativeDiagnostic("tailscale", stage, detail)
					return
				}
			}
			nativeDiagnostic("tailscale", stage, "peer_metrics=unavailable reason=request_peer_not_in_status")
		}()
	})
}

func (p *tailscaleProvider) Stop() tunnelResult {
	if err := p.discardServer(); err != nil {
		return failureWithDiagnostic("provider_unavailable", "stop.close_server", err)
	}
	return tunnelResult{State: "stopped"}
}

func (p *tailscaleProvider) Revoke(name string) tunnelResult {
	ctx, finish := p.beginOperation()
	defer finish()
	if ctx.Err() != nil {
		return tailscaleOperationFailure(ctx, "tailscale_revoke_failed", "revoke", ctx.Err())
	}
	if !validDeviceName(name) {
		return failure("invalid_name")
	}
	var primary tunnelResult
	if p.server == nil {
		p.newServer(name, "")
		if err := p.server.Start(); err != nil {
			primary = tailscaleOperationFailure(ctx, "tailscale_start_failed", "revoke.server_start", err)
		}
	}
	if primary.State == "" && p.server != nil {
		client, err := p.server.LocalClient()
		if err != nil {
			primary = tailscaleOperationFailure(ctx, "tailscale_local_client_failed", "revoke.local_client", err)
		} else {
			status, statusErr := client.Status(ctx)
			if statusErr != nil {
				primary = tailscaleOperationFailure(ctx, "tailscale_status_failed", "revoke.client_status", statusErr)
			} else if status == nil {
				primary = failureWithReason("tailscale_status_failed", "revoke.client_status", "backend returned no status")
			} else if status != nil && status.AuthURL == "" {
				if err := client.Logout(ctx); err != nil {
					primary = tailscaleOperationFailure(ctx, "tailscale_logout_failed", "revoke.logout", err)
				}
			}
		}
	}
	cleanupErr := p.discardServer()
	if primary.State != "" {
		return addSecondaryDiagnostic(primary, "revoke.close_server", cleanupErr)
	}
	if cleanupErr != nil {
		return failureWithDiagnostic("provider_unavailable", "revoke.close_server", cleanupErr)
	}
	if err := deleteSecretPrefix("tunnel.tailscale."); err != nil {
		return failureWithDiagnostic("secure_store_error", "revoke.delete_state", err)
	}
	return tunnelResult{State: "revoked"}
}

func (p *tailscaleProvider) stopServing() error {
	if p.serving != nil {
		p.serving.expected.Store(true)
		p.serving = nil
	}
	var cleanupErr error
	if p.httpServer != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		if err := p.httpServer.Shutdown(ctx); err != nil {
			cleanupErr = errors.Join(cleanupErr, fmt.Errorf("shutdown HTTPS server: %w", err))
		}
		cancel()
		p.httpServer = nil
	}
	if p.listener != nil {
		if err := p.listener.Close(); err != nil && !errors.Is(err, net.ErrClosed) {
			cleanupErr = errors.Join(cleanupErr, fmt.Errorf("close tunnel listener: %w", err))
		}
		p.listener = nil
	}
	return cleanupErr
}

func (p *tailscaleProvider) CloseForModeChange() {
	_ = p.discardServer()
}

func (p *tailscaleProvider) newServer(name, authKey string) {
	p.name = name
	p.server = &tsnet.Server{
		Dir:      p.runtimeDir,
		Hostname: name,
		Store:    tailscaleEncryptedStateStore{},
		Logf:     func(string, ...any) {},
		UserLogf: func(string, ...any) {},
		AuthKey:  authKey,
	}
}

func (p *tailscaleProvider) discardServer() error {
	cleanupErr := p.stopServing()
	if p.server != nil {
		if err := p.server.Close(); err != nil {
			cleanupErr = errors.Join(cleanupErr, fmt.Errorf("close tsnet server: %w", err))
		}
		p.server = nil
	}
	p.address = ""
	return cleanupErr
}

func (p *tailscaleProvider) discardAfterFailure() {
	_ = p.discardServer()
}

func (p *tailscaleProvider) NetworkChanged() {
	if p.server != nil {
		p.server.NetworkChanged()
	}
}

type tailscaleEncryptedStateStore struct{}

func (tailscaleEncryptedStateStore) ReadState(id ipn.StateKey) ([]byte, error) {
	key := tailscaleStorageKey(string(id))
	value, err := readSecret(key)
	// Only keys accepted by the old store can contain an existing identity.
	legacy := tailscaleStatePrefix + string(id)
	if errorsIsSecretMissing(err) && legacyStateKey.MatchString(legacy) {
		value, err = readSecret(legacy)
		if err == nil {
			if writeErr := writeSecret(key, value); writeErr != nil {
				wipeBytes(value)
				return nil, fmt.Errorf("migrate Tailscale state: %w", writeErr)
			}
		}
	}
	if errorsIsSecretMissing(err) {
		return nil, ipn.ErrStateNotExist
	}
	if err != nil {
		return nil, err
	}
	return value, nil
}

func (tailscaleEncryptedStateStore) WriteState(id ipn.StateKey, value []byte) error {
	return writeSecret(tailscaleStorageKey(string(id)), value)
}

func localHTTPServerAvailable(port int) bool {
	connection, err := net.DialTimeout("tcp", fmt.Sprintf("127.0.0.1:%d", port), 400*time.Millisecond)
	if err != nil {
		return false
	}
	_ = connection.Close()
	return true
}

func newLoopbackProxy(port int, provider, generation string) (*httputil.ReverseProxy, error) {
	target, err := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", port))
	if err != nil {
		return nil, err
	}
	targetAddress := fmt.Sprintf("127.0.0.1:%d", port)
	transport := &http.Transport{
		Proxy: nil,
		DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
			if network != "tcp" || address != targetAddress {
				return nil, fmt.Errorf("blocked camera proxy target")
			}
			return (&net.Dialer{Timeout: 3 * time.Second, KeepAlive: 30 * time.Second}).DialContext(ctx, network, targetAddress)
		},
		DisableCompression: true,
	}
	proxy := httputil.NewSingleHostReverseProxy(target)
	originalDirector := proxy.Director
	proxy.Director = func(request *http.Request) {
		externalHost := request.Host
		originalDirector(request)
		request.Host = externalHost
	}
	proxy.Transport = transport
	proxy.ErrorLog = log.New(diagnosticWriter{provider, "proxy generation=" + generation}, "", 0)
	proxy.ErrorHandler = func(w http.ResponseWriter, _ *http.Request, err error) {
		nativeDiagnostic(provider, "loopback generation="+generation, nativeErrorDetail("forward", err))
		http.Error(w, "camera stream unavailable", http.StatusBadGateway)
	}
	return proxy, nil
}

func tailscaleAddress(status *ipnstate.Status) string {
	if status == nil || len(status.CertDomains) == 0 {
		return ""
	}
	return httpsAddress(status.CertDomains[0])
}

func tailscaleAddressFromServer(server *tsnet.Server) string {
	client, err := server.LocalClient()
	if err != nil {
		return ""
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	status, err := client.Status(ctx)
	if err != nil {
		return ""
	}
	return tailscaleAddress(status)
}

func httpsAddress(host string) string {
	host = strings.TrimSuffix(strings.TrimSpace(host), ".")
	if host == "" || strings.ContainsAny(host, "/?#@") {
		return ""
	}
	return "https://" + host
}

func errorsIsSecretMissing(err error) bool { return errors.Is(err, errSecretMissing) }

func validDeviceName(value string) bool {
	if len(value) < 1 || len(value) > 63 || value[0] == '-' || value[len(value)-1] == '-' {
		return false
	}
	for _, char := range value {
		if !((char >= 'a' && char <= 'z') || (char >= '0' && char <= '9') || char == '-') {
			return false
		}
	}
	return true
}

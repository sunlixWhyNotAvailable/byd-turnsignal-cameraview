package main

/*
#include <stdlib.h>
#include "jni_bridge.h"
*/
import "C"

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"runtime"
	"runtime/debug"
	"strings"
	"sync"
	"sync/atomic"
	"unsafe"

	"github.com/sirupsen/logrus"
)

type tunnelRequest struct {
	Provider               string `json:"provider"`
	InstanceID             string `json:"instanceId"`
	Name                   string `json:"name"`
	Credential             string `json:"credential"`
	LocalPort              int    `json:"localPort"`
	Public                 bool   `json:"public"`
	PrivateProxyCredential string `json:"privateProxyCredential"`
}

func main() {}

func init() {
	// Provider libraries must not write credentials, identities, or local state to logs.
	log.SetOutput(io.Discard)
	logrus.SetOutput(io.Discard)
}

type tunnelResult struct {
	State          string `json:"state"`
	Address        string `json:"address,omitempty"`
	LoginURL       string `json:"loginUrl,omitempty"`
	ErrorCode      string `json:"errorCode,omitempty"`
	ErrorDetail    string `json:"errorDetail,omitempty"`
	Diagnostic     string `json:"diagnostic,omitempty"`
	NetworkFailure bool   `json:"networkFailure,omitempty"`
}

type tunnelProvider interface {
	Authorize(name, credential string) tunnelResult
	Status(name string) tunnelResult
	Start(name string, localPort int, public bool, privateProxyCredential string) tunnelResult
	Stop() tunnelResult
	Revoke(name string) tunnelResult
}

type registeredProvider struct {
	mu                sync.Mutex
	cancelMu          sync.Mutex
	provider          tunnelProvider
	closed            bool
	operationInFlight bool
}

var (
	nativeURLPattern     = regexp.MustCompile(`(?i)\b(?:https?|wss?)://[^\s<>"']+`)
	nativeSecretPattern  = regexp.MustCompile(`(?i)(["']?(?:password|credential|privateProxyCredential|token|authkey|authorization|auth[_-]?url)["']?\s*[:=]\s*)(?:"[^"]*"|'[^']*'|[^\s,;}]+)`)
	nativeAuthKeyPattern = regexp.MustCompile(`\btskey-[A-Za-z0-9_-]+\b`)
)

var (
	providerMu             sync.Mutex
	providers              = map[string]*registeredProvider{}
	pendingCancellations   = map[string]struct{}{}
	runtimeDir             string
	networkEventGeneration atomic.Uint64
	networkWorkerRunning   atomic.Bool
)

//export camera_tunnel_set_runtime_dir
func camera_tunnel_set_runtime_dir(path *C.char) C.int {
	requestedPath := C.GoString(path)
	if requestedPath == "" {
		return 0
	}
	requested, err := filepath.Abs(requestedPath)
	if err != nil {
		return 0
	}
	providerMu.Lock()
	defer providerMu.Unlock()
	if runtimeDir != "" && runtimeDir != requested {
		return 0
	}
	absolute, err := prepareRuntimeDirectory(requested)
	if err != nil {
		return 0
	}
	if runtimeDir != "" && runtimeDir != absolute {
		return 0
	}
	runtimeDir = absolute
	return 1
}

func prepareRuntimeDirectory(path string) (string, error) {
	if path == "" {
		return "", errors.New("private runtime directory missing")
	}
	absolute, err := filepath.Abs(path)
	if err != nil {
		return "", err
	}
	logs := filepath.Join(absolute, "logs")
	for _, directory := range []string{absolute, logs} {
		if err := os.MkdirAll(directory, 0700); err != nil {
			return "", err
		}
		if err := os.Chmod(directory, 0700); err != nil {
			return "", err
		}
	}
	if err := os.Setenv("TS_LOGS_DIR", logs); err != nil {
		return "", err
	}
	return absolute, nil
}

//export camera_tunnel_network_changed
func camera_tunnel_network_changed() {
	networkEventGeneration.Add(1)
	if networkWorkerRunning.CompareAndSwap(false, true) {
		go processNetworkChanges()
	}
}

//export camera_tunnel_cancel
func camera_tunnel_cancel(provider *C.char, instanceID *C.char) {
	kind := C.GoString(provider)
	id := C.GoString(instanceID)
	if (kind != "tailscale" && kind != "zrok") || !validInstanceID(id) {
		return
	}
	providerMu.Lock()
	entry := providers[kind+"\x00"+id]
	pendingCancellations[kind+"\x00"+id] = struct{}{}
	if entry != nil {
		entry.cancelMu.Lock()
		if entry.operationInFlight {
			if cancellable, ok := entry.provider.(interface{ cancelOperation() }); ok {
				cancellable.cancelOperation()
			}
		}
		entry.cancelMu.Unlock()
	}
	providerMu.Unlock()
}

func processNetworkChanges() {
	for {
		generation := networkEventGeneration.Load()
		providerMu.Lock()
		entries := make([]*registeredProvider, 0, len(providers))
		for _, entry := range providers {
			entries = append(entries, entry)
		}
		providerMu.Unlock()

		for _, entry := range entries {
			entry.mu.Lock()
			if !entry.closed {
				if provider, ok := entry.provider.(*tailscaleProvider); ok {
					provider.NetworkChanged()
				}
			}
			entry.mu.Unlock()
		}

		networkWorkerRunning.Store(false)
		if networkEventGeneration.Load() == generation ||
			!networkWorkerRunning.CompareAndSwap(false, true) {
			return
		}
	}
}

//export camera_tunnel_call
func camera_tunnel_call(operation *C.char, requestJSON *C.char) (result *C.char) {
	operationName := safeOperation(C.GoString(operation))
	stage := "decode_request"
	var entry *registeredProvider
	var entryLocked bool
	var providerKey string
	var request tunnelRequest
	defer func() {
		if panicValue := recover(); panicValue != nil {
			if entryLocked {
				entry.closed = true
				safeDisposeProvider(entry.provider)
				finishProviderOperation(entry)
				entry.mu.Unlock()
				entryLocked = false
				removeProvider(providerKey, entry)
			}
			location := panicLocation()
			secrets := []string{request.Credential, request.PrivateProxyCredential}
			panicDetail := fmt.Sprintf("panic=%s location=%s stack=%s", safePanicText(panicValue, secrets...),
				location, safeNativeText(string(debug.Stack()), secrets...))
			logNativePanic(operationName, stage, location, panicDetail)
			result = C.CString(encodeResult(failureWithReason("runtime_error", stage, panicDetail, secrets...)))
		}
	}()
	requestBytes := []byte(C.GoString(requestJSON))
	if err := json.Unmarshal(requestBytes, &request); err != nil {
		wipeBytes(requestBytes)
		return C.CString(encodeResult(failure("invalid_request")))
	}
	wipeBytes(requestBytes)
	stage = "validate_request"
	if currentRuntimeDirectory() == "" {
		return C.CString(encodeResult(failure("native_unavailable")))
	}
	if !validInstanceID(request.InstanceID) {
		return C.CString(encodeResult(failure("invalid_request")))
	}
	if !validOperation(operationName) {
		return C.CString(encodeResult(failure("invalid_request")))
	}
	if request.Provider != "tailscale" && request.Provider != "zrok" {
		return C.CString(encodeResult(failure("invalid_provider")))
	}
	providerKey = request.Provider + "\x00" + request.InstanceID
	stage = "provider_lookup"
	entry = lookupProvider(providerKey, request.Provider, operationName)
	if entry == nil {
		return C.CString(encodeResult(tunnelResult{State: "stopped"}))
	}
	entry.mu.Lock()
	entryLocked = true
	if entry.closed {
		finishProviderOperation(entry)
		entry.mu.Unlock()
		entryLocked = false
		return C.CString(encodeResult(failure("native_unavailable")))
	}
	stage = "provider_call"
	var output tunnelResult
	switch operationName {
	case "authorize":
		output = entry.provider.Authorize(request.Name, request.Credential)
	case "status":
		output = entry.provider.Status(request.Name)
	case "start":
		output = entry.provider.Start(request.Name, request.LocalPort, request.Public, request.PrivateProxyCredential)
	case "stop":
		output = entry.provider.Stop()
	case "close":
		entry.closed = true
		output = entry.provider.Stop()
		removeProvider(providerKey, entry)
	case "revoke":
		output = entry.provider.Revoke(request.Name)
	}
	finishProviderOperation(entry)
	entry.mu.Unlock()
	entryLocked = false
	return C.CString(encodeResult(output))
}

func safeOperation(value string) string {
	switch value {
	case "authorize", "status", "start", "stop", "close", "revoke":
		return value
	default:
		return "unknown"
	}
}

func validOperation(value string) bool { return value != "unknown" }

func lookupProvider(key, kind, operation string) *registeredProvider {
	providerMu.Lock()
	entry := providers[key]
	if entry == nil {
		if operation == "stop" || operation == "close" {
			delete(pendingCancellations, key)
			providerMu.Unlock()
			return nil
		}
		var provider tunnelProvider
		switch kind {
		case "tailscale":
			provider = newTailscaleProvider(runtimeDir)
			provider.(*tailscaleProvider).instanceID = strings.SplitN(key, "\x00", 2)[1]
		case "zrok":
			provider = newZrokProvider(runtimeDir)
			provider.(*zrokProvider).instanceID = strings.SplitN(key, "\x00", 2)[1]
		}
		entry = &registeredProvider{provider: provider}
		providers[key] = entry
	}
	_, cancelPending := pendingCancellations[key]
	if cancelPending {
		delete(pendingCancellations, key)
	}
	if operation != "close" && operation != "stop" {
		entry.cancelMu.Lock()
		entry.operationInFlight = true
		entry.cancelMu.Unlock()
	}
	if cancelPending && operation != "close" && operation != "stop" {
		if cancellable, ok := entry.provider.(interface{ cancelOperation() }); ok {
			cancellable.cancelOperation()
		}
	}
	providerMu.Unlock()
	return entry
}

func finishProviderOperation(entry *registeredProvider) {
	entry.cancelMu.Lock()
	entry.operationInFlight = false
	if cancellable, ok := entry.provider.(interface{ clearPendingCancellation() }); ok {
		cancellable.clearPendingCancellation()
	}
	entry.cancelMu.Unlock()
}

func currentRuntimeDirectory() string {
	providerMu.Lock()
	defer providerMu.Unlock()
	return runtimeDir
}

func removeProvider(key string, entry *registeredProvider) {
	providerMu.Lock()
	if providers[key] == entry {
		delete(providers, key)
	}
	providerMu.Unlock()
}

func safeDisposeProvider(provider tunnelProvider) {
	defer func() { _ = recover() }()
	switch provider := provider.(type) {
	case *tailscaleProvider:
		provider.discardAfterFailure()
	case *zrokProvider:
		provider.discardAfterFailure()
	}
}

func panicLocation() string {
	var pcs [20]uintptr
	count := runtime.Callers(2, pcs[:])
	frames := runtime.CallersFrames(pcs[:count])
	for {
		frame, more := frames.Next()
		if !strings.HasPrefix(frame.Function, "runtime.") &&
			!strings.Contains(frame.Function, "camera_tunnel_call.func") &&
			frame.Function != "main.camera_tunnel_call" && frame.Function != "main.panicLocation" {
			if frame.Function != "" {
				return frame.Function
			}
		}
		if !more {
			return "unknown"
		}
	}
}

func logNativePanic(operation, stage, location, detail string) {
	cOperation := C.CString(operation)
	cStage := C.CString(stage)
	cLocation := C.CString(location)
	cDetail := C.CString(detail)
	defer C.free(unsafe.Pointer(cOperation))
	defer C.free(unsafe.Pointer(cStage))
	defer C.free(unsafe.Pointer(cLocation))
	defer C.free(unsafe.Pointer(cDetail))
	C.camera_tunnel_log_panic(cOperation, cStage, cLocation, cDetail)
}

func safePanicText(value any, secrets ...string) (text string) {
	defer func() {
		if recover() != nil {
			text = "panic value could not be rendered"
		}
	}()
	switch value := value.(type) {
	case error:
		return safeNativeText(fmt.Sprintf("%T: %s", value, value.Error()), secrets...)
	case string:
		return safeNativeText(value, secrets...)
	default:
		return fmt.Sprintf("%T", value)
	}
}

//export camera_tunnel_free
func camera_tunnel_free(value *C.char) {
	if value != nil {
		C.free(unsafe.Pointer(value))
	}
}

var errSecretMissing = errors.New("secret missing")
var errSecureStore = errors.New("secure store failure")

func readSecret(key string) ([]byte, error) {
	cKey := C.CString(key)
	defer C.free(unsafe.Pointer(cKey))
	var value *C.char
	var length C.size_t
	status := int(C.camera_secret_get(cKey, &value, &length))
	if status == 0 {
		return nil, errSecretMissing
	}
	if status < 0 || value == nil || uint64(length) > uint64(^uint32(0)>>1) {
		if value != nil {
			C.camera_secret_clear(unsafe.Pointer(value), length)
			C.free(unsafe.Pointer(value))
		}
		return nil, fmt.Errorf("read: %w", errSecureStore)
	}
	defer func() {
		C.camera_secret_clear(unsafe.Pointer(value), length)
		C.free(unsafe.Pointer(value))
	}()
	return C.GoBytes(unsafe.Pointer(value), C.int(length)), nil
}

func writeSecret(key string, value []byte) error {
	cKey := C.CString(key)
	defer C.free(unsafe.Pointer(cKey))
	var cValue unsafe.Pointer
	if len(value) != 0 {
		cValue = C.CBytes(value)
		defer func() {
			C.camera_secret_clear(cValue, C.size_t(len(value)))
			C.free(cValue)
		}()
	}
	if C.camera_secret_put(cKey, (*C.char)(cValue), C.size_t(len(value))) == 0 {
		return fmt.Errorf("write: %w", errSecureStore)
	}
	return nil
}

func deleteSecret(key string) error {
	cKey := C.CString(key)
	defer C.free(unsafe.Pointer(cKey))
	if C.camera_secret_remove(cKey) == 0 {
		return fmt.Errorf("remove: %w", errSecureStore)
	}
	return nil
}

func deleteSecretPrefix(prefix string) error {
	cPrefix := C.CString(prefix)
	defer C.free(unsafe.Pointer(cPrefix))
	if C.camera_secret_remove_prefix(cPrefix) == 0 {
		return fmt.Errorf("remove prefix: %w", errSecureStore)
	}
	return nil
}

func failure(code string) tunnelResult {
	details := map[string]string{
		"invalid_request":             "The tunnel request is invalid.",
		"invalid_provider":            "The selected tunnel provider is not supported.",
		"invalid_name":                "Use a valid lowercase device or connection name.",
		"invalid_port":                "The local camera server is not available on the requested port.",
		"credential_required":         "Enter the provider credential to authorize this tunnel.",
		"login_required":              "Complete provider sign-in before starting the tunnel.",
		"not_authorized":              "Authorize this provider before starting the tunnel.",
		"private_credential_missing":  "Private camera access could not be established.",
		"provider_unavailable":        "The tunnel provider could not complete the request.",
		"tailscale_https_failed":      "Private Tailscale HTTPS access could not be started.",
		"tailscale_funnel_failed":     "Tailscale Funnel could not be started.",
		"tailscale_start_failed":      "The Tailscale operation could not be started.",
		"tailscale_status_failed":     "Tailscale status could not be read.",
		"tailscale_logout_failed":     "The Tailscale session could not be revoked.",
		"tailscale_operation_timeout": "The Tailscale operation exceeded its time limit.",
		"zrok_enable_failed":          "The vehicle could not be registered with Zrok.",
		"zrok_share_failed":           "The Zrok viewing link could not be created.",
		"zrok_operation_timeout":      "The Zrok operation exceeded its time limit.",
		"secure_store_error":          "Encrypted tunnel state could not be read or saved.",
		"revoke_required":             "Revoke the current Zrok account before authorizing another account.",
		"native_unavailable":          "The secure tunnel runtime is unavailable on this device.",
		"runtime_error":               "The secure tunnel runtime could not complete the request.",
	}
	detail, ok := details[code]
	if !ok {
		detail = details["runtime_error"]
	}
	return tunnelResult{State: "error", ErrorCode: code, ErrorDetail: detail}
}

func failureWithDiagnostic(code, stage string, err error, secrets ...string) tunnelResult {
	if errors.Is(err, errSecureStore) {
		code = "secure_store_error"
	}
	result := failure(code)
	var networkError *net.OpError
	var dnsError *net.DNSError
	result.NetworkFailure = (errors.As(err, &networkError) && networkError.Net != "") || errors.As(err, &dnsError)
	// TLS handshake timeouts are wrapped by net/http, not necessarily net.OpError.
	var requestError *url.Error
	if errors.As(err, &requestError) && requestError.Timeout() && !errors.Is(err, context.Canceled) {
		result.NetworkFailure = true
	}
	if errors.Is(err, context.Canceled) || errors.Is(err, errSecureStore) {
		result.NetworkFailure = false
	}
	if err != nil {
		result.ErrorDetail = nativeErrorDetail(stage, err, secrets...)
	}
	return result
}

// The serving goroutine reports failure, but never mutates a newer provider run.
type servingRun struct {
	expected atomic.Bool
	failure  atomic.Pointer[tunnelResult]
}

func serveTunnel(kind, id string, server *http.Server, listener net.Listener) *servingRun {
	run := &servingRun{}
	go func() {
		err := server.Serve(listener)
		if run.expected.Load() || errors.Is(err, http.ErrServerClosed) {
			return
		}
		if err == nil {
			err = errors.New("listener exited unexpectedly")
		}
		result := failureWithDiagnostic("listener_failed", "serve", err)
		run.failure.Store(&result)
		nativeDiagnostic(kind, "serve generation="+id, result.ErrorDetail)
		instance, value := C.CString(id), C.CString(encodeResult(result))
		defer C.free(unsafe.Pointer(instance))
		defer C.free(unsafe.Pointer(value))
		C.camera_tunnel_event(instance, value)
	}()
	return run
}

func nativeDiagnostic(provider, stage, detail string) {
	p, s, d := C.CString(provider), C.CString(stage), C.CString(safeNativeText(detail))
	defer C.free(unsafe.Pointer(p))
	defer C.free(unsafe.Pointer(s))
	defer C.free(unsafe.Pointer(d))
	C.camera_tunnel_diagnostic(p, s, d)
}

type diagnosticWriter struct{ provider, stage string }

func (w diagnosticWriter) Write(data []byte) (int, error) {
	nativeDiagnostic(w.provider, w.stage, string(data))
	return len(data), nil
}

func failureWithReason(code, stage, reason string, secrets ...string) tunnelResult {
	result := failure(code)
	result.ErrorDetail = "stage=" + safeNativeText(stage, secrets...) + " cause=" + safeNativeText(reason, secrets...)
	return result
}

func addSecondaryDiagnostic(result tunnelResult, stage string, err error, secrets ...string) tunnelResult {
	if err != nil {
		if result.State == "cancelled" {
			result.State = "error"
			result.ErrorCode = "provider_unavailable"
		}
		result.ErrorDetail += " secondary=" + nativeErrorDetail(stage, err, secrets...)
	}
	return result
}

func nativeErrorDetail(stage string, err error, secrets ...string) string {
	causes := make([]string, 0, 3)
	appendNativeCauses(&causes, err, secrets, 0)
	return "stage=" + safeNativeText(stage, secrets...) + " cause=" + strings.Join(causes, " <- ")
}

func appendNativeCauses(causes *[]string, err error, secrets []string, depth int) {
	if err == nil || depth >= 16 {
		return
	}
	*causes = append(*causes, fmt.Sprintf("%T: %s", err, safeNativeText(err.Error(), secrets...)))
	if joined, ok := err.(interface{ Unwrap() []error }); ok {
		for _, cause := range joined.Unwrap() {
			appendNativeCauses(causes, cause, secrets, depth+1)
		}
		return
	}
	appendNativeCauses(causes, errors.Unwrap(err), secrets, depth+1)
}

func safeNativeText(value string, secrets ...string) string {
	value = strings.ReplaceAll(strings.ReplaceAll(value, "\r", " "), "\n", " ")
	value = nativeURLPattern.ReplaceAllString(value, "[url redacted]")
	value = nativeSecretPattern.ReplaceAllString(value, "$1[redacted]")
	value = nativeAuthKeyPattern.ReplaceAllString(value, "[redacted]")
	for _, secret := range secrets {
		if secret != "" {
			value = strings.ReplaceAll(value, secret, "[redacted]")
		}
	}
	return strings.TrimSpace(value)
}

func encodeResult(result tunnelResult) string {
	encoded, err := json.Marshal(result)
	if err != nil {
		return `{"state":"error","errorCode":"runtime_error","errorDetail":"The secure tunnel runtime could not complete the request."}`
	}
	return string(encoded)
}

func wipeBytes(value []byte) {
	for index := range value {
		value[index] = 0
	}
}

func validInstanceID(value string) bool {
	if len(value) != 36 {
		return false
	}
	for index, char := range value {
		if index == 8 || index == 13 || index == 18 || index == 23 {
			if char != '-' {
				return false
			}
			continue
		}
		if !((char >= '0' && char <= '9') || (char >= 'a' && char <= 'f')) {
			return false
		}
	}
	return true
}

#!/bin/bash
# Memegram GKE Deployment Script
# Usage: ./deploy-gke.sh [setup|build|push|deploy|status|stop|start|destroy|cost|ip|security|dns]
#
# Workflow:
#   1. setup    - One-time: create GKE cluster + Artifact Registry
#   2. security - One-time: create static IP + Cloud Armor + SSL policy (after setup)
#   3. dns      - Show DNS record you need to create at your registrar
#   4. build    - Build all Docker images
#   5. push     - Push images to Artifact Registry
#   6. deploy   - Apply k8s manifests to GKE
#   7. status   - Show pods, services, external IP
#   8. stop     - Scale node pool to 0 (saves money, keeps cluster)
#   9. start    - Scale node pool back up
#  10. destroy  - Delete EVERYTHING (cluster + registry) to save all money
#  11. cost     - Show estimated daily cost
#  12. ip       - Show external IP of orchestrator

set -e

# ==================== CONFIGURATION ====================
# CHANGE THESE VALUES TO MATCH YOUR GCP PROJECT
PROJECT_ID="${GCP_PROJECT_ID:-}"          # Your GCP project ID
REGION="europe-west1"                      # Cheapest EU region
ZONE="${REGION}-b"                          # Zone within region
CLUSTER_NAME="memegram-cluster"
REGISTRY_NAME="memegram"
NAMESPACE="memegram"
OVERLAY="k8s/overlays/prod"
MACHINE_TYPE="e2-standard-2"               # 2 vCPU, 8 GB RAM — $0.067/hr
NUM_NODES=3                                # 3 nodes = 6 vCPU, 24 GB total
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# ==================== HTTPS / SECURITY CONFIGURATION ====================
# Domain pointing to the static IP (set this after you buy a domain)
# Example: export DOMAIN_NAME=api.memegram.com
DOMAIN_NAME="${DOMAIN_NAME:-}"

# Names of out-of-band GCP resources (created by 'security' command).
# These names are referenced from k8s manifests in k8s/overlays/prod/ingress/.
STATIC_IP_NAME="memegram-ip"
ARMOR_POLICY="memegram-armor-policy"
SSL_POLICY="memegram-ssl-policy"

# Cloud Armor rate limit: max requests per IP per minute before throttling.
# 600 req/min ≈ 10 req/sec — safe baseline for an API used by mobile clients.
ARMOR_RATE_LIMIT_RPM=600

SERVICES=(
    auth-service
    user-service
    contacts-service
    messaging-service
    media-service
    item-storage-service
    notifications-service
    orchestrator
)

IMAGE_TAG="v1.0.0"
# Unique tag per build run — guarantees k8s pulls a fresh image even if :latest is cached
BUILD_TAG="build-$(date +%Y%m%d-%H%M%S)-$(git -C "$(cd "$(dirname "$0")" && pwd)" rev-parse --short HEAD 2>/dev/null || echo nogit)"
REGISTRY_URL="${REGION}-docker.pkg.dev/${PROJECT_ID}/${REGISTRY_NAME}"

# ==================== COLORS ====================
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
NC='\033[0m'

log()  { echo -e "${GREEN}[+]${NC} $1"; }
warn() { echo -e "${YELLOW}[!]${NC} $1"; }
err()  { echo -e "${RED}[x]${NC} $1"; exit 1; }
info() { echo -e "${CYAN}[i]${NC} $1"; }

# ==================== CHECKS ====================
check_project() {
    if [ -z "$PROJECT_ID" ]; then
        err "GCP_PROJECT_ID is not set. Run: export GCP_PROJECT_ID=your-project-id"
    fi
    log "Using project: $PROJECT_ID"
}

check_gcloud() {
    if ! command -v gcloud &>/dev/null; then
        err "gcloud CLI not found. Install: https://cloud.google.com/sdk/docs/install"
    fi
    if ! command -v kubectl &>/dev/null; then
        err "kubectl not found. Run: gcloud components install kubectl"
    fi
}

# ==================== SECURITY (HTTPS + Cloud Armor) ====================
check_domain() {
    if [ -z "$DOMAIN_NAME" ]; then
        err "DOMAIN_NAME is not set. Run: export DOMAIN_NAME=api.yourdomain.com"
    fi
}

setup_security() {
    check_gcloud
    check_project

    log "=== Setting up HTTPS + Cloud Armor stack ==="

    # 1. Reserve a global static external IP for the Ingress Load Balancer
    log "Reserving global static IP '$STATIC_IP_NAME'..."
    gcloud compute addresses create "$STATIC_IP_NAME" \
        --global \
        --ip-version=IPV4 \
        2>/dev/null || warn "Static IP already exists"

    STATIC_IP=$(gcloud compute addresses describe "$STATIC_IP_NAME" --global --format='value(address)')
    log "Static IP reserved: $STATIC_IP"

    # 2. Create Cloud Armor security policy
    log "Creating Cloud Armor policy '$ARMOR_POLICY'..."
    gcloud compute security-policies create "$ARMOR_POLICY" \
        --description="Memegram WAF + rate limiting + DDoS protection" \
        2>/dev/null || warn "Armor policy already exists"

    # 2a. Enable adaptive protection (free, ML-based DDoS detection)
    log "Enabling Adaptive Protection (free L7 DDoS ML)..."
    gcloud compute security-policies update "$ARMOR_POLICY" \
        --enable-layer7-ddos-defense \
        --quiet 2>/dev/null || warn "Adaptive Protection may already be enabled"

    # 2b. Add OWASP-ish preconfigured WAF rules (SQLi, XSS, LFI, RCE)
    # Sensitivity level 1 = only highest-confidence signatures (least false positives).
    # Use evaluatePreconfiguredWaf(...) with {'sensitivity': 1} so JSON bodies on
    # auth/login endpoints don't get falsely blocked (e.g. nicknames, invite codes).
    log "Adding WAF rule: SQL injection (sqli-v33-stable, sensitivity=1)..."
    gcloud compute security-policies rules create 1000 \
        --security-policy="$ARMOR_POLICY" \
        --expression="evaluatePreconfiguredWaf('sqli-v33-stable', {'sensitivity': 1})" \
        --action=deny-403 \
        --description="Block SQL injection attempts (high-confidence only)" \
        2>/dev/null || warn "Rule 1000 already exists"

    log "Adding WAF rule: Cross-site scripting (xss-v33-stable, sensitivity=1)..."
    gcloud compute security-policies rules create 1001 \
        --security-policy="$ARMOR_POLICY" \
        --expression="evaluatePreconfiguredWaf('xss-v33-stable', {'sensitivity': 1})" \
        --action=deny-403 \
        --description="Block XSS attempts (high-confidence only)" \
        2>/dev/null || warn "Rule 1001 already exists"

    log "Adding WAF rule: Local file inclusion (lfi-v33-stable, sensitivity=1)..."
    gcloud compute security-policies rules create 1002 \
        --security-policy="$ARMOR_POLICY" \
        --expression="evaluatePreconfiguredWaf('lfi-v33-stable', {'sensitivity': 1})" \
        --action=deny-403 \
        --description="Block LFI attempts (high-confidence only)" \
        2>/dev/null || warn "Rule 1002 already exists"

    log "Adding WAF rule: Remote code execution (rce-v33-stable, sensitivity=1)..."
    gcloud compute security-policies rules create 1003 \
        --security-policy="$ARMOR_POLICY" \
        --expression="evaluatePreconfiguredWaf('rce-v33-stable', {'sensitivity': 1})" \
        --action=deny-403 \
        --description="Block RCE attempts (high-confidence only)" \
        2>/dev/null || warn "Rule 1003 already exists"

    # 2c. Per-IP rate limit (throttle action — return 429)
    log "Adding rate limit rule: $ARMOR_RATE_LIMIT_RPM req/min per IP..."
    gcloud compute security-policies rules create 2000 \
        --security-policy="$ARMOR_POLICY" \
        --expression="true" \
        --action=throttle \
        --rate-limit-threshold-count="$ARMOR_RATE_LIMIT_RPM" \
        --rate-limit-threshold-interval-sec=60 \
        --conform-action=allow \
        --exceed-action=deny-429 \
        --enforce-on-key=IP \
        --description="Rate limit: $ARMOR_RATE_LIMIT_RPM req/min per IP" \
        2>/dev/null || warn "Rule 2000 already exists"

    # 3. SSL policy: enforce TLS 1.2+ and modern ciphers
    log "Creating SSL policy '$SSL_POLICY' (TLS 1.2+, MODERN profile)..."
    gcloud compute ssl-policies create "$SSL_POLICY" \
        --profile=MODERN \
        --min-tls-version=1.2 \
        2>/dev/null || warn "SSL policy already exists"

    log "=== Security stack ready ==="
    info "Static IP:         $STATIC_IP"
    info "Cloud Armor:       $ARMOR_POLICY"
    info "SSL policy:        $SSL_POLICY (TLS 1.2+)"
    echo ""
    info "Next steps:"
    info "  1. Buy a domain (e.g. on Cloudflare Registrar — ~\$10/year for .com)"
    info "  2. Create an A record:  yourdomain.com  ->  $STATIC_IP"
    info "  3. export DOMAIN_NAME=yourdomain.com"
    info "  4. ./deploy-gke.sh deploy"
}

show_dns() {
    check_gcloud
    STATIC_IP=$(gcloud compute addresses describe "$STATIC_IP_NAME" --global --format='value(address)' 2>/dev/null || echo "")
    if [ -z "$STATIC_IP" ]; then
        err "Static IP '$STATIC_IP_NAME' not found. Run: ./deploy-gke.sh security"
    fi
    echo ""
    log "=== DNS Configuration ==="
    echo ""
    info "At your domain registrar (e.g. Cloudflare DNS), create:"
    echo ""
    echo "  Type:  A"
    echo "  Name:  @  (or 'api', or whatever subdomain you want)"
    echo "  Value: $STATIC_IP"
    echo "  TTL:   Auto / 300"
    echo "  Proxy: DNS only (for Google Managed Cert provisioning; can enable Cloudflare proxy AFTER cert is ACTIVE)"
    echo ""
    info "Then:  export DOMAIN_NAME=yourdomain.com  &&  ./deploy-gke.sh deploy"
    echo ""
}

# Render ManagedCertificate with the actual domain before apply
render_managed_cert() {
    check_domain
    local src="$SCRIPT_DIR/k8s/overlays/prod/ingress/managed-certificate.yaml"
    local dst="$SCRIPT_DIR/k8s/overlays/prod/ingress/.managed-certificate.rendered.yaml"
    sed "s|DOMAIN_NAME|$DOMAIN_NAME|g" "$src" > "$dst"
    # Apply directly (kustomize will still apply the placeholder version, so we override after)
    kubectl apply -f "$dst"
    rm -f "$dst"
}
setup() {
    check_gcloud
    check_project

    log "Setting GCP project..."
    gcloud config set project "$PROJECT_ID"

    log "Enabling required APIs..."
    gcloud services enable \
        container.googleapis.com \
        artifactregistry.googleapis.com \
        compute.googleapis.com

    log "Creating Artifact Registry repository..."
    gcloud artifacts repositories create "$REGISTRY_NAME" \
        --repository-format=docker \
        --location="$REGION" \
        --description="Memegram container images" \
        2>/dev/null || warn "Registry already exists"

    log "Configuring Docker authentication for Artifact Registry..."
    gcloud auth configure-docker "${REGION}-docker.pkg.dev" --quiet

    log "Creating GKE cluster (this takes 3-5 minutes)..."
    gcloud container clusters create "$CLUSTER_NAME" \
        --zone="$ZONE" \
        --machine-type="$MACHINE_TYPE" \
        --num-nodes="$NUM_NODES" \
        --disk-size=30 \
        --disk-type=pd-standard \
        --enable-ip-alias \
        --no-enable-master-authorized-networks \
        --release-channel=regular \
        --workload-pool="${PROJECT_ID}.svc.id.goog" \
        --labels=app=memegram,env=prod

    log "Getting cluster credentials..."
    gcloud container clusters get-credentials "$CLUSTER_NAME" --zone="$ZONE"

    log "Cluster is ready!"
    kubectl cluster-info
}

# ==================== BUILD ====================
build_images() {
    check_project
    log "Building Docker images for linux/amd64 (GKE node architecture)..."

    # Ensure buildx builder exists
    if ! docker buildx inspect memegram-builder &>/dev/null; then
        log "Creating buildx builder 'memegram-builder'..."
        docker buildx create --name memegram-builder --use
    else
        docker buildx use memegram-builder
    fi

    for svc in "${SERVICES[@]}"; do
        log "Building $svc (linux/amd64) [tag: $BUILD_TAG]..."
        docker buildx build \
            --platform linux/amd64 \
            --no-cache \
            --pull \
            -t "memegram/${svc}:latest" \
            -t "${REGISTRY_URL}/${svc}:${IMAGE_TAG}" \
            -t "${REGISTRY_URL}/${svc}:${BUILD_TAG}" \
            -t "${REGISTRY_URL}/${svc}:latest" \
            --load \
            "$SCRIPT_DIR/backend/$svc" -q
    done
    log "All images built!"
}

# ==================== PUSH ====================
push_images() {
    check_project
    log "Pushing images to Artifact Registry..."
    for svc in "${SERVICES[@]}"; do
        log "Pushing $svc..."
        docker push "${REGISTRY_URL}/${svc}:${IMAGE_TAG}" -q
        docker push "${REGISTRY_URL}/${svc}:${BUILD_TAG}" -q
        docker push "${REGISTRY_URL}/${svc}:latest" -q
    done
    log "All images pushed!"
}

# ==================== DEPLOY ====================
deploy() {
    check_project
    check_gcloud
    check_domain

    log "Ensuring kubectl context points to GKE cluster..."
    gcloud container clusters get-credentials "$CLUSTER_NAME" --zone="$ZONE"

    log "Verifying security stack exists..."
    gcloud compute addresses describe "$STATIC_IP_NAME" --global &>/dev/null \
        || err "Static IP '$STATIC_IP_NAME' not found. Run: ./deploy-gke.sh security"
    gcloud compute security-policies describe "$ARMOR_POLICY" &>/dev/null \
        || err "Cloud Armor policy '$ARMOR_POLICY' not found. Run: ./deploy-gke.sh security"

    log "Deploying to GKE with prod overlay (domain: $DOMAIN_NAME)..."
    # Render kustomize and substitute DOMAIN_NAME placeholder before apply
    kubectl kustomize "$SCRIPT_DIR/$OVERLAY" \
        | sed "s|DOMAIN_NAME|$DOMAIN_NAME|g" \
        | kubectl apply -f -

    log "Waiting for pods to be ready (timeout: 5 min)..."
    kubectl wait --for=condition=ready pod --all -n "$NAMESPACE" --timeout=300s 2>/dev/null || true

    status
    echo ""
    STATIC_IP=$(gcloud compute addresses describe "$STATIC_IP_NAME" --global --format='value(address)')
    info "Ingress static IP: $STATIC_IP"
    info "Domain:            $DOMAIN_NAME"
    echo ""
    info "Next:"
    info "  - Make sure DNS A record points $DOMAIN_NAME -> $STATIC_IP"
    info "  - Wait 15-60 min for the Google-managed SSL certificate to become ACTIVE"
    info "  - Check cert status: kubectl describe managedcertificate memegram-cert -n $NAMESPACE"
    info "  - Then your API is live at: https://$DOMAIN_NAME"
}

# ==================== STATUS ====================
status() {
    echo ""
    log "=== Pod Status ==="
    kubectl get pods -n "$NAMESPACE" -o wide 2>/dev/null || warn "No pods found"
    echo ""
    log "=== Services ==="
    kubectl get svc -n "$NAMESPACE" 2>/dev/null || warn "No services found"
    echo ""
    log "=== Ingress ==="
    kubectl get ingress -n "$NAMESPACE" 2>/dev/null || warn "No ingress found"
    echo ""
    log "=== Managed Certificate ==="
    kubectl get managedcertificate -n "$NAMESPACE" 2>/dev/null \
        || warn "No managed certificate found"
    echo ""
    log "=== PVCs ==="
    kubectl get pvc -n "$NAMESPACE" 2>/dev/null || warn "No PVCs found"
}

# ==================== GET EXTERNAL IP ====================
get_ip() {
    STATIC_IP=$(gcloud compute addresses describe "$STATIC_IP_NAME" --global --format='value(address)' 2>/dev/null || echo "")
    if [ -n "$STATIC_IP" ]; then
        log "Ingress static IP: $STATIC_IP"
        if [ -n "$DOMAIN_NAME" ]; then
            log "Public URL:        https://$DOMAIN_NAME"
            log "Health check:      https://$DOMAIN_NAME/health"
        else
            warn "DOMAIN_NAME not set. The Load Balancer requires HTTPS via your domain."
        fi
    else
        warn "Static IP '$STATIC_IP_NAME' not yet created. Run: ./deploy-gke.sh security"
    fi
}

# ==================== STOP (save money, keep cluster) ====================
stop_cluster() {
    check_project
    check_gcloud

    warn "Scaling node pool to 0 (keeps cluster metadata, stops billing for nodes)..."
    gcloud container clusters resize "$CLUSTER_NAME" \
        --zone="$ZONE" \
        --node-pool=default-pool \
        --num-nodes=0 \
        --quiet

    log "Node pool scaled to 0. Cluster is paused."
    info "Cost while paused: ~\$0.00/hr (only minimal control plane costs)"
    info "To resume: ./deploy-gke.sh start"
}

# ==================== START (resume after stop) ====================
start_cluster() {
    check_project
    check_gcloud

    log "Scaling node pool back to $NUM_NODES nodes..."
    gcloud container clusters resize "$CLUSTER_NAME" \
        --zone="$ZONE" \
        --node-pool=default-pool \
        --num-nodes="$NUM_NODES" \
        --quiet

    log "Getting cluster credentials..."
    gcloud container clusters get-credentials "$CLUSTER_NAME" --zone="$ZONE"

    log "Waiting for nodes to be ready..."
    kubectl wait --for=condition=ready node --all --timeout=180s 2>/dev/null || true

    log "Nodes are back. Pods should auto-restart."
    info "Wait 1-2 minutes, then run: ./deploy-gke.sh status"
}

# ==================== DESTROY (delete everything) ====================
destroy() {
    check_project
    check_gcloud

    warn "=== THIS WILL DELETE EVERYTHING ==="
    warn "Cluster: $CLUSTER_NAME"
    warn "All PVCs/data will be LOST"
    echo ""
    read -p "Type 'yes' to confirm: " confirm
    if [ "$confirm" != "yes" ]; then
        err "Aborted."
    fi

    log "Deleting k8s resources..."
    kubectl delete -k "$SCRIPT_DIR/$OVERLAY" --ignore-not-found 2>/dev/null || true

    log "Deleting GKE cluster (takes 2-3 min)..."
    gcloud container clusters delete "$CLUSTER_NAME" \
        --zone="$ZONE" \
        --quiet

    log "Deleting Cloud Armor policy..."
    gcloud compute security-policies delete "$ARMOR_POLICY" --quiet 2>/dev/null || true

    log "Deleting SSL policy..."
    gcloud compute ssl-policies delete "$SSL_POLICY" --quiet 2>/dev/null || true

    log "Releasing static IP..."
    gcloud compute addresses delete "$STATIC_IP_NAME" --global --quiet 2>/dev/null || true

    log "Cluster deleted. No more compute charges."
    info "Images in Artifact Registry are preserved (cheap storage)."
    info "To fully clean up images too: gcloud artifacts repositories delete $REGISTRY_NAME --location=$REGION"
}

# ==================== COST ESTIMATE ====================
estimate_cost() {
    echo ""
    info "=== Estimated Costs (europe-west1) ==="
    echo ""
    echo "  GKE cluster management (zonal):  \$0.00/hr  (first zonal cluster is free)"
    echo "  $NUM_NODES x $MACHINE_TYPE nodes:            \$$(echo "$NUM_NODES * 0.067" | bc)/hr = \$$(echo "$NUM_NODES * 0.067 * 24" | bc)/day"
    echo "  Persistent disks (~50 GB total):  ~\$2.00/month"
    echo "  Load Balancer:                    ~\$0.025/hr = \$0.60/day"
    echo "  Artifact Registry storage:        ~\$0.10/GB/month (negligible)"
    echo ""
    DAILY=$(echo "$NUM_NODES * 0.067 * 24 + 0.60" | bc)
    WEEKLY=$(echo "$DAILY * 7" | bc)
    echo "  TOTAL: ~\$${DAILY}/day | ~\$${WEEKLY}/week"
    echo ""
    info "With \$300 free trial: ~$(echo "300 / $DAILY" | bc) days of continuous running"
    echo ""
    info "Cost saving strategies:"
    echo "    ./deploy-gke.sh stop     — scale to 0 nodes (~\$0/hr)"
    echo "    ./deploy-gke.sh start    — bring nodes back in 2 min"
    echo "    ./deploy-gke.sh destroy  — delete cluster completely (\$0)"
    echo ""
}

# ==================== FULL DEPLOY (build + push + deploy) ====================
full_deploy() {
    build_images
    push_images
    deploy
    log "Forcing rollout restart so new :latest images are pulled..."
    kubectl rollout restart deployment -n "$NAMESPACE"
    kubectl rollout status deployment -n "$NAMESPACE" --timeout=300s 2>/dev/null || true
}

# ==================== REDEPLOY (just deploy, no rebuild) ====================
redeploy() {
    check_project
    check_gcloud
    check_domain
    gcloud container clusters get-credentials "$CLUSTER_NAME" --zone="$ZONE"
    kubectl kustomize "$SCRIPT_DIR/$OVERLAY" \
        | sed "s|DOMAIN_NAME|$DOMAIN_NAME|g" \
        | kubectl apply -f -
    kubectl rollout restart deployment -n "$NAMESPACE"
    log "Redeploying... Run './deploy-gke.sh status' in a minute."
}

# ==================== LOGS ====================
show_logs() {
    local svc="${2:-orchestrator}"
    kubectl logs -n "$NAMESPACE" -l "app=$svc" --tail=50 -f
}

# ==================== MAIN ====================
case "${1}" in
    setup)
        setup
        ;;
    security)
        setup_security
        ;;
    dns)
        show_dns
        ;;
    build)
        build_images
        ;;
    push)
        push_images
        ;;
    deploy)
        deploy
        ;;
    full)
        full_deploy
        ;;
    redeploy)
        redeploy
        ;;
    status)
        status
        ;;
    ip)
        get_ip
        ;;
    stop)
        stop_cluster
        ;;
    start)
        start_cluster
        ;;
    destroy)
        destroy
        ;;
    cost)
        estimate_cost
        ;;
    logs)
        show_logs "$@"
        ;;
    *)
        echo "Memegram GKE Deployment"
        echo ""
        echo "Usage: $0 <command>"
        echo ""
        echo "  Initial Setup:"
        echo "    setup     Create GKE cluster + Artifact Registry (one-time)"
        echo "    security  Create static IP + Cloud Armor + SSL policy (one-time, after setup)"
        echo "    dns       Show DNS A record to configure at your registrar"
        echo ""
        echo "  Deployment:"
        echo "    build     Build all Docker images locally"
        echo "    push      Push images to Artifact Registry"
        echo "    deploy    Apply k8s manifests to GKE (requires DOMAIN_NAME)"
        echo "    full      Build + Push + Deploy (all-in-one)"
        echo "    redeploy  Re-apply manifests and restart pods"
        echo ""
        echo "  Monitoring:"
        echo "    status    Show pods, services, ingress, certificate status"
        echo "    ip        Show static IP + public HTTPS URL"
        echo "    logs [svc] Follow logs (default: orchestrator)"
        echo ""
        echo "  Cost Management:"
        echo "    stop      Scale nodes to 0 (pause, save money)"
        echo "    start     Scale nodes back up (resume)"
        echo "    destroy   Delete cluster + Cloud Armor + static IP"
        echo "    cost      Show cost estimates"
        echo ""
        echo "  Required env:"
        echo "    export GCP_PROJECT_ID=your-project-id"
        echo "    export DOMAIN_NAME=api.yourdomain.com   (for 'deploy')"
        ;;
esac

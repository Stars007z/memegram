#!/bin/bash
# Memegram GKE Deployment Script
# Usage: ./deploy-gke.sh [setup|build|push|deploy|status|stop|start|destroy|cost|ip]
#
# Workflow:
#   1. setup   - One-time: create GKE cluster + Artifact Registry
#   2. build   - Build all Docker images
#   3. push    - Push images to Artifact Registry
#   4. deploy  - Apply k8s manifests to GKE
#   5. status  - Show pods, services, external IP
#   6. stop    - Scale node pool to 0 (saves money, keeps cluster)
#   7. start   - Scale node pool back up
#   8. destroy - Delete EVERYTHING (cluster + registry) to save all money
#   9. cost    - Show estimated daily cost
#  10. ip      - Show external IP of orchestrator

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

# ==================== SETUP ====================
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
        log "Building $svc (linux/amd64)..."
        docker buildx build \
            --platform linux/amd64 \
            -t "memegram/${svc}:latest" \
            -t "${REGISTRY_URL}/${svc}:${IMAGE_TAG}" \
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
        docker push "${REGISTRY_URL}/${svc}:latest" -q
    done
    log "All images pushed!"
}

# ==================== DEPLOY ====================
deploy() {
    check_project
    check_gcloud

    log "Ensuring kubectl context points to GKE cluster..."
    gcloud container clusters get-credentials "$CLUSTER_NAME" --zone="$ZONE"

    log "Deploying to GKE with prod overlay..."
    kubectl apply -k "$SCRIPT_DIR/$OVERLAY"

    log "Waiting for pods to be ready (timeout: 5 min)..."
    kubectl wait --for=condition=ready pod --all -n "$NAMESPACE" --timeout=300s 2>/dev/null || true

    status
    echo ""
    info "Waiting for external IP assignment (can take 1-2 min)..."
    for i in $(seq 1 24); do
        EXTERNAL_IP=$(kubectl get svc orchestrator -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
        if [ -n "$EXTERNAL_IP" ]; then
            echo ""
            log "Application is available at: http://${EXTERNAL_IP}:8000"
            log "Health check: http://${EXTERNAL_IP}:8000/health"
            return
        fi
        printf "."
        sleep 5
    done
    warn "External IP not yet assigned. Run './deploy-gke.sh ip' to check later."
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
    log "=== PVCs ==="
    kubectl get pvc -n "$NAMESPACE" 2>/dev/null || warn "No PVCs found"
}

# ==================== GET EXTERNAL IP ====================
get_ip() {
    EXTERNAL_IP=$(kubectl get svc orchestrator -n "$NAMESPACE" -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo "")
    if [ -n "$EXTERNAL_IP" ]; then
        log "External IP: http://${EXTERNAL_IP}:8000"
        log "Health: http://${EXTERNAL_IP}:8000/health"
    else
        warn "No external IP assigned yet. Wait a minute and try again."
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
}

# ==================== REDEPLOY (just deploy, no rebuild) ====================
redeploy() {
    check_project
    check_gcloud
    gcloud container clusters get-credentials "$CLUSTER_NAME" --zone="$ZONE"
    kubectl apply -k "$SCRIPT_DIR/$OVERLAY"
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
        echo ""
        echo "  Deployment:"
        echo "    build     Build all Docker images locally"
        echo "    push      Push images to Artifact Registry"
        echo "    deploy    Apply k8s manifests to GKE"
        echo "    full      Build + Push + Deploy (all-in-one)"
        echo "    redeploy  Re-apply manifests and restart pods"
        echo ""
        echo "  Monitoring:"
        echo "    status    Show pods, services, PVCs"
        echo "    ip        Show external IP of orchestrator"
        echo "    logs [svc] Follow logs (default: orchestrator)"
        echo ""
        echo "  Cost Management:"
        echo "    stop      Scale nodes to 0 (pause, save money)"
        echo "    start     Scale nodes back up (resume)"
        echo "    destroy   Delete cluster entirely"
        echo "    cost      Show cost estimates"
        echo ""
        echo "  Required env: export GCP_PROJECT_ID=your-project-id"
        ;;
esac

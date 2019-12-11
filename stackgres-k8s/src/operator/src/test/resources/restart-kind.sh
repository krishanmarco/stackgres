#!/bin/sh
set -e
KUBERNETES_VERSION="${KUBERNETES_VERSION:-1.12.10}"
if [ -z "$KIND_NAME" ]
then
  KIND_NAME="$(docker inspect -f '{{.Name}}' "$(hostname)"|cut -d '/' -f 2)"
fi
#echo "Installing kubectl"
#wget -q -L -O /bin/kubectl https://storage.googleapis.com/kubernetes-release/release/v${KUBERNETES_VERSION}/bin/linux/amd64/kubectl
#chmod a+x /bin/kubectl
#echo "Installing kind"
#wget -q -L -O /bin/kind  https://github.com/kubernetes-sigs/kind/releases/download/v0.5.1/kind-$(uname)-amd64
#chmod a+x /bin/kind
#echo "Installing helm"
#wget -q -L https://get.helm.sh/helm-v2.14.3-linux-amd64.tar.gz -O -|tar xz --strip-components=1 -C /bin -f - linux-amd64/helm
if [ "$REUSE_KIND" != "true" ] \
  || ! kind get nodes --name "$KIND_NAME" | wc -l | grep -q "^$1$"
then
  kind delete cluster --name "$KIND_NAME" || true
  cat << EOF > kind-config.yaml
kind: Cluster
apiVersion: kind.sigs.k8s.io/v1alpha3
nodes:
- role: control-plane
EOF
  if [ ! -z "$1" ] && [ "$1" -ge 2 ]
  then
    for i in $(seq 2 "$1")
    do
      echo '- role: worker' >> kind-config.yaml
    done
  fi
  kind create cluster --config kind-config.yaml --name "$KIND_NAME" --image "kindest/node:v${KUBERNETES_VERSION}"
fi
if [ -f /certs/server.crt ]
then
  for node in $(kind get nodes --name "$KIND_NAME")
  do
    (
    docker cp /certs/server.crt $node:/usr/local/share/ca-certificates/validator.crt
    docker exec -t $node sh -c "update-ca-certificates"
    ) &
  done
fi
for node in $(kind get nodes --name "$KIND_NAME")
do
  (
  docker exec -t $node sh -c 'DEBIAN_FRONTEND=noninteractive apt-get update -y -qq < /dev/null > /dev/null'
  docker exec -t $node sh -c 'DEBIAN_FRONTEND=noninteractive apt-get install -y -qq nfs-common < /dev/null > /dev/null'
  ) &
done
wait
export KUBECONFIG="$(kind get kubeconfig-path --name="$KIND_NAME")"
mkdir -p "$(dirname "$KUBECONFIG")"
kind get kubeconfig --internal --name "$KIND_NAME" > "$KUBECONFIG"
echo "export KUBECONFIG='$KUBECONFIG'" > "$HOME/.profile"
if echo "$KUBERNETES_VERSION" | grep -q '^1\.12\.'
then
  # Patch coredns to version 1.3.1 (see https://github.com/coredns/coredns/issues/2391)
  kubectl patch deployment -n kube-system coredns --type json \
    --patch '[{"op":"replace","path":"/spec/template/spec/containers/0/image","value":"k8s.gcr.io/coredns:1.3.1"}]'
fi
cat << 'EOF' | kubectl apply -f -
kind: ClusterRoleBinding
apiVersion: rbac.authorization.k8s.io/v1beta1
metadata:
  name: tiller-clusterrolebinding
subjects:
- kind: ServiceAccount
  name: default
  namespace: kube-system
roleRef:
  kind: ClusterRole
  name: cluster-admin
  apiGroup: ""
EOF
helm init --history-max 20
while ! helm version > /dev/null 2>&1; do sleep 0.5; done
cat << 'EOF' | kubectl apply -f -
---
apiVersion: v1
kind: LimitRange
metadata:
  name: cpu-limit-range
spec:
  limits:
  - default:
      cpu: 0.1
    defaultRequest:
      cpu: 0.1
    type: Container
---
apiVersion: v1
kind: LimitRange
metadata:
  name: mem-limit-range
spec:
  limits:
  - default:
      memory: 16Mi
    defaultRequest:
      memory: 16Mi
    type: Container
EOF
helm repo update
helm dependency update /resources/stackgres-operator
helm dependency update /resources/stackgres-cluster
echo "Kind started k8s cluster"


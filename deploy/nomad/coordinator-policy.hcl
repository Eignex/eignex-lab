# Use a dedicated namespace in production and change it here and in coordinator settings together.
namespace "default" {
  capabilities = ["read-job", "submit-job", "alloc-lifecycle", "host-volume-read", "host-volume-create"]
}
node {
  policy = "read"
}
host_volume "lab-status" {
  capabilities = ["mount-readonly"]
}
host_volume "lab-cache" {
  capabilities = ["mount-readwrite"]
}

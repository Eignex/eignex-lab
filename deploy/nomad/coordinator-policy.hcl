# Use a dedicated namespace in production and change it here and in coordinator settings together.
namespace "default" {
  capabilities = ["read-job", "submit-job", "alloc-lifecycle"]
}
node {
  policy = "read"
}
host_volume "lab-status" {
  capabilities = ["mount-readonly"]
}

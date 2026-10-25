variable "project_id" { type = string }
variable "region" { type = string }
variable "zone" { type = string }
variable "deployment_id" { type = string }
variable "pool_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,47}$", var.pool_id))
    error_message = "Use a provider-neutral capacity pool identifier."
  }
}
variable "dedicated_project" {
  type = bool
  validation {
    condition     = var.dedicated_project
    error_message = "This reference adapter requires a dedicated lab project with finite Spot vCPU quotas."
  }
}
variable "image" {
  type = string
  validation {
    condition     = can(regex("^projects/[^/]+/global/images/[^/]+$", var.image))
    error_message = "Pin a baked image by name, never an image family."
  }
}
variable "machine_type" { type = string }
variable "vcpus" { type = number }
variable "memory_mib" { type = number }
variable "max_vcpus" { type = number }
variable "max_nodes" {
  type = number
  validation {
    condition     = var.max_nodes >= 1 && var.max_nodes <= 1000 && floor(var.max_nodes) == var.max_nodes
    error_message = "Use a finite integer node limit."
  }
}
variable "subnetwork" { type = string }
variable "network_tags" { type = list(string) }
variable "control_member" { type = string }
variable "bootstrap_secret" { type = string }
variable "signing_secret" { type = string }
variable "nomad_servers" { type = list(string) }
variable "node_class" { type = string }
variable "datacenter" {
  type    = string
  default = "lab"
}
variable "worker_image" {
  type = string
  validation {
    condition     = can(regex("^[^[:space:]]+@sha256:[0-9a-f]{64}$", var.worker_image))
    error_message = "Use the same immutable portable OCI worker from a configurable registry."
  }
}
variable "architecture" {
  type    = string
  default = "x86_64"
}
variable "minimum_features" {
  type    = list(string)
  default = ["avx2"]
}

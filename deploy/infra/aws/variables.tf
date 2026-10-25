variable "deployment_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,47}$", var.deployment_id))
    error_message = "Use a unique lab deployment identifier."
  }
}
variable "pool_id" {
  type = string
  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{0,47}$", var.pool_id))
    error_message = "Use the provider-neutral capacity pool identifier."
  }
}
variable "ami_id" {
  type = string
  validation {
    condition     = can(regex("^ami-[0-9a-f]+$", var.ami_id))
    error_message = "Pin a baked worker AMI ID, never an image lookup or latest alias."
  }
}
variable "instance_type" { type = string }
variable "vcpus" { type = number }
variable "memory_mib" { type = number }
variable "max_vcpus" { type = number }
variable "max_nodes" {
  type = number
  validation {
    condition     = var.max_nodes >= 1 && var.max_nodes <= 1000 && floor(var.max_nodes) == var.max_nodes
    error_message = "Provide a finite integer node limit."
  }
}
variable "market" {
  type    = string
  default = "spot"
  validation {
    condition     = contains(["spot", "on-demand"], var.market)
    error_message = "Purchasing is explicit: spot or on-demand."
  }
}
variable "subnet_ids" {
  type = list(string)
  validation {
    condition     = length(var.subnet_ids) > 0
    error_message = "Use private/routed subnets with the required outbound connectivity."
  }
}
variable "security_group_ids" { type = list(string) }
variable "control_principal_arn" { type = string }
variable "bootstrap_secret_arn" { type = string }
variable "signing_secret_arn" { type = string }
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
    error_message = "Use an immutable OCI worker from a configurable registry."
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
variable "alarm_topic_arn" {
  type    = string
  default = null
}

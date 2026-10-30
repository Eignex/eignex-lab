variable "capacity_contract" {
  type        = string
  default     = ""
  description = "The pool contract `eignex-lab capacity-export` wrote from the lab's capacity settings; when given, this pool must be its pool of pool_id, of the same size and limit."
}

locals {
  contract_pools = var.capacity_contract == "" ? [] : [for pool in jsondecode(file(var.capacity_contract)).pools : pool if pool.id == var.pool_id]
}

resource "terraform_data" "capacity_contract" {
  input = var.capacity_contract
  lifecycle {
    precondition {
      condition = var.capacity_contract == "" || (length(local.contract_pools) == 1 && try(
        local.contract_pools[0].maxNodes == var.max_nodes && local.contract_pools[0].vcpus == var.vcpus && local.contract_pools[0].memoryMiB == var.memory_mib,
      false))
      error_message = "pool_id, max_nodes, vcpus and memory_mib must be the capacity contract's pool of pool_id."
    }
  }
}

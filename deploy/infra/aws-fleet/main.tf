variable "deployment_id" { type = string }
variable "subnet_ids" { type = list(string) }
variable "security_group_ids" { type = list(string) }
variable "control_principal_arn" { type = string }
variable "bootstrap_secret_arn" { type = string }
variable "signing_secret_arn" { type = string }
variable "nomad_servers" { type = list(string) }
variable "adapter_role_name" { type = string }
variable "scaler_role_name" { type = string }
variable "max_vcpus" {
  type    = number
  default = 128
}
variable "pools" {
  type = map(object({
    ami_id              = string
    instance_type       = string
    spot_instance_types = optional(list(string), [])
    vcpus               = number
    memory_mib          = number
    max_nodes           = number
    node_class          = string
    architecture        = string
    minimum_features    = list(string)
    worker_image        = string
  }))
}

module "pool" {
  source                = "../aws"
  for_each              = var.pools
  deployment_id         = var.deployment_id
  pool_id               = each.key
  ami_id                = each.value.ami_id
  instance_type         = each.value.instance_type
  spot_instance_types   = each.value.spot_instance_types
  vcpus                 = each.value.vcpus
  memory_mib            = each.value.memory_mib
  max_nodes             = each.value.max_nodes
  max_vcpus             = var.max_vcpus
  market                = "spot"
  subnet_ids            = var.subnet_ids
  security_group_ids    = var.security_group_ids
  control_principal_arn = var.control_principal_arn
  bootstrap_secret_arn  = var.bootstrap_secret_arn
  signing_secret_arn    = var.signing_secret_arn
  nomad_servers         = var.nomad_servers
  node_class            = each.value.node_class
  architecture          = each.value.architecture
  minimum_features      = each.value.minimum_features
  worker_image          = each.value.worker_image
}

# The supervised adapter and scaler retain their separate identities. Each grant is
# exactly the module's policy for that pool; neither role receives worker credentials.
resource "aws_iam_role_policy" "adapter" {
  for_each = var.pools
  name     = "eignex-lab-pool-${each.key}"
  role     = var.adapter_role_name
  policy   = module.pool[each.key].adapter_policy_json
}

# All pools have identical scaler actions and conditions. Combine their exact ASG ARNs
# into one managed policy instead of spending the role's 10-KiB inline-policy budget
# once for every shape. The original single-pool role policy remains independent.
locals {
  scaler_policies = [for pool in values(module.pool) : jsondecode(pool.scaler_policy_json)]
  scaler_groups   = [for policy in local.scaler_policies : policy.Statement[1].Resource]
}

resource "aws_iam_policy" "scaler_fleet" {
  name = "lab-${var.deployment_id}-fleet-scaler"
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = flatten([for policy in slice(local.scaler_policies, 0, min(1, length(local.scaler_policies))) : concat(
      [policy.Statement[0]],
      [for statement in slice(policy.Statement, 1, 4) : merge(statement, { Resource = local.scaler_groups })]
    )])
  })
}

resource "aws_iam_role_policy_attachment" "scaler_fleet" {
  role       = var.scaler_role_name
  policy_arn = aws_iam_policy.scaler_fleet.arn
}

output "pools" {
  value = { for id, pool in module.pool : id => {
    group_name      = pool.group_name
    renew_function  = pool.renew_function
    expiry_function = pool.expiry_function
  } }
}

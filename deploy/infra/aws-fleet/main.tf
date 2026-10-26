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
    ami_id           = string
    instance_type    = string
    vcpus            = number
    memory_mib       = number
    max_nodes        = number
    node_class       = string
    architecture     = string
    minimum_features = list(string)
    worker_image     = string
  }))
}

module "pool" {
  source                = "../aws"
  for_each              = var.pools
  deployment_id         = var.deployment_id
  pool_id               = each.key
  ami_id                = each.value.ami_id
  instance_type         = each.value.instance_type
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

resource "aws_iam_role_policy" "scaler" {
  for_each = var.pools
  name     = "eignex-lab-pool-${each.key}"
  role     = var.scaler_role_name
  policy   = module.pool[each.key].scaler_policy_json
}

output "pools" {
  value = { for id, pool in module.pool : id => {
    group_name      = pool.group_name
    renew_function  = pool.renew_function
    expiry_function = pool.expiry_function
  } }
}

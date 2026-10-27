data "aws_partition" "current" {}
data "aws_caller_identity" "current" {}
data "aws_region" "current" {}
data "aws_ec2_instance_type" "spot_choices" {
  for_each      = toset(var.spot_instance_types)
  instance_type = each.value
}
locals {
  name          = "lab-${substr(var.deployment_id, 0, 16)}-${substr(var.pool_id, 0, 16)}-${substr(sha256("${var.deployment_id}/${var.pool_id}"), 0, 8)}"
  tags          = { "lab:deployment" = var.deployment_id, "lab:pool" = var.pool_id }
  trust_control = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { AWS = var.control_principal_arn }, Action = "sts:AssumeRole" }] })
  trust_lambda  = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "lambda.amazonaws.com" }, Action = "sts:AssumeRole" }] })
  node_config = {
    provider     = "aws"
    pool         = var.pool_id, nodeClass = var.node_class, datacenter = var.datacenter, servers = var.nomad_servers,
    workerImage  = var.worker_image, vcpus = var.vcpus, memoryMiB = coalesce(var.bootstrap_memory_mib, var.memory_mib),
    architecture = var.architecture, minimumFeatures = var.minimum_features, lifetimeSeconds = 21600
  }
}
check "finite_capacity" {
  assert {
    condition     = var.vcpus >= 1 && var.memory_mib > 1024 && var.max_nodes * var.vcpus <= var.max_vcpus
    error_message = "The homogeneous pool must fit its explicit vCPU limit."
  }
}
check "bootstrap_memory" {
  assert {
    condition     = coalesce(var.bootstrap_memory_mib, var.memory_mib) > 1024 && coalesce(var.bootstrap_memory_mib, var.memory_mib) <= var.memory_mib
    error_message = "The OS-visible bootstrap memory baseline must fit the nominal instance RAM."
  }
}
check "separate_credentials" {
  assert {
    condition     = var.bootstrap_secret_arn != var.signing_secret_arn
    error_message = "Workers must never receive the capacity signing key."
  }
}
resource "aws_iam_role" "worker" {
  name               = "${local.name}-worker"
  assume_role_policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Principal = { Service = "ec2.amazonaws.com" }, Action = "sts:AssumeRole" }] })
}
resource "aws_iam_role_policy" "worker" {
  role   = aws_iam_role.worker.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [{ Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = var.bootstrap_secret_arn }] })
}
resource "aws_iam_instance_profile" "worker" {
  name = "${local.name}-worker"
  role = aws_iam_role.worker.name
}
resource "aws_launch_template" "worker" {
  name_prefix   = "${local.name}-"
  image_id      = var.ami_id
  instance_type = var.instance_type
  iam_instance_profile { name = aws_iam_instance_profile.worker.name }
  vpc_security_group_ids = var.security_group_ids
  user_data              = base64encode(templatefile("${path.module}/user-data.sh.tftpl", { config = jsonencode(local.node_config), secret = var.bootstrap_secret_arn, region = data.aws_region.current.region }))
  metadata_options {
    http_endpoint               = "enabled"
    http_tokens                 = "required"
    http_put_response_hop_limit = 1
    http_protocol_ipv6          = "disabled"
  }
  block_device_mappings {
    device_name = "/dev/sda1"
    ebs {
      volume_size           = 100
      volume_type           = "gp3"
      encrypted             = true
      delete_on_termination = true
    }
  }
  dynamic "instance_market_options" {
    for_each = var.market == "spot" && length(var.spot_instance_types) == 0 ? [1] : []
    content {
      market_type = "spot"
      spot_options {
        instance_interruption_behavior = "terminate"
        spot_instance_type             = "one-time"
      }
    }
  }
  tag_specifications {
    resource_type = "instance"
    tags          = local.tags
  }
  tag_specifications {
    resource_type = "volume"
    tags          = local.tags
  }
}
resource "aws_autoscaling_group" "workers" {
  name                      = local.name
  min_size                  = 0
  max_size                  = 0 # The cloud renewal function opens the finite configured limit after a fresh grant.
  desired_capacity          = 0
  vpc_zone_identifier       = var.subnet_ids
  health_check_type         = "EC2"
  health_check_grace_period = 600
  capacity_rebalance        = false # Rebalance surges must not spend unreserved vCPUs.
  protect_from_scale_in     = false
  max_instance_lifetime     = 86400
  dynamic "launch_template" {
    for_each = length(var.spot_instance_types) == 0 ? [1] : []
    content {
      id      = aws_launch_template.worker.id
      version = aws_launch_template.worker.latest_version
    }
  }
  dynamic "mixed_instances_policy" {
    for_each = length(var.spot_instance_types) > 0 ? [1] : []
    content {
      instances_distribution {
        on_demand_base_capacity                  = 0
        on_demand_percentage_above_base_capacity = 0
        spot_allocation_strategy                 = "price-capacity-optimized"
      }
      launch_template {
        launch_template_specification {
          launch_template_id = aws_launch_template.worker.id
          version            = aws_launch_template.worker.latest_version
        }
        dynamic "override" {
          for_each = var.spot_instance_types
          content { instance_type = override.value }
        }
      }
    }
  }
  dynamic "tag" {
    for_each = local.tags
    content {
      key                 = tag.key
      value               = tag.value
      propagate_at_launch = true
    }
  }
  lifecycle {
    ignore_changes = [desired_capacity, min_size, max_size]
    precondition {
      condition = (length(var.spot_instance_types) == 0 || var.market == "spot") && alltrue([
        for choice in data.aws_ec2_instance_type.spot_choices : choice.default_vcpus == var.vcpus && choice.memory_size == var.memory_mib && contains(choice.supported_architectures, var.architecture)
      ])
      error_message = "Spot alternatives must preserve the pool's architecture, vCPU count and memory."
    }
    precondition {
      condition     = var.vcpus >= 1 && floor(var.vcpus) == var.vcpus && var.memory_mib > 1024 && var.max_nodes * var.vcpus <= var.max_vcpus
      error_message = "Finite homogeneous capacity must fit the vCPU limit."
    }
    precondition {
      condition     = var.bootstrap_secret_arn != var.signing_secret_arn
      error_message = "Workers cannot receive the capacity signing key."
    }
  }
}
resource "aws_dynamodb_table" "leases" {
  name         = "${local.name}-leases"
  billing_mode = "PAY_PER_REQUEST"
  hash_key     = "pool"
  attribute {
    name = "pool"
    type = "S"
  }
  ttl {
    attribute_name = "lease_until"
    enabled        = true
  }
  server_side_encryption {
    enabled = true
  }
}
data "archive_file" "functions" {
  type        = "zip"
  output_path = "${path.root}/functions-${var.pool_id}.zip"
  source {
    content  = file("${path.module}/functions.py")
    filename = "functions.py"
  }
  source {
    content  = file("${path.module}/../common/capacity_grants.py")
    filename = "capacity_grants.py"
  }
}
resource "aws_iam_role" "renew" {
  name               = "${local.name}-renew"
  assume_role_policy = local.trust_lambda
}
resource "aws_iam_role" "expiry" {
  name               = "${local.name}-expiry"
  assume_role_policy = local.trust_lambda
}
resource "aws_iam_role_policy_attachment" "renew_logs" {
  role       = aws_iam_role.renew.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}
resource "aws_iam_role_policy_attachment" "expiry_logs" {
  role       = aws_iam_role.expiry.name
  policy_arn = "arn:${data.aws_partition.current.partition}:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole"
}
resource "aws_iam_role_policy" "renew" {
  role = aws_iam_role.renew.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["autoscaling:DescribeAutoScalingGroups"], Resource = "*" },
    { Effect = "Allow", Action = ["autoscaling:UpdateAutoScalingGroup"], Resource = aws_autoscaling_group.workers.arn },
    { Effect = "Allow", Action = ["dynamodb:PutItem"], Resource = aws_dynamodb_table.leases.arn, Condition = { "ForAllValues:StringEquals" = { "dynamodb:LeadingKeys" = [var.pool_id] } } },
    { Effect = "Allow", Action = ["secretsmanager:GetSecretValue"], Resource = var.signing_secret_arn }
  ] })
}
resource "aws_iam_role_policy" "expiry" {
  role = aws_iam_role.expiry.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["autoscaling:DescribeAutoScalingGroups", "ec2:DescribeInstances"], Resource = "*" },
    { Effect = "Allow", Action = ["autoscaling:SetInstanceProtection"], Resource = aws_autoscaling_group.workers.arn },
    { Effect = "Allow", Action = ["autoscaling:UpdateAutoScalingGroup"], Resource = aws_autoscaling_group.workers.arn, Condition = { NumericEquals = { "autoscaling:MaxSize" = 0, "autoscaling:MinSize" = 0 } } },
    { Effect = "Allow", Action = ["dynamodb:GetItem"], Resource = aws_dynamodb_table.leases.arn },
    { Effect = "Allow", Action = ["ec2:TerminateInstances"], Resource = "arn:${data.aws_partition.current.partition}:ec2:${data.aws_region.current.region}:${data.aws_caller_identity.current.account_id}:instance/*", Condition = { StringEquals = { "ec2:ResourceTag/lab:deployment" = var.deployment_id, "ec2:ResourceTag/lab:pool" = var.pool_id } } }
  ] })
}
resource "aws_lambda_function" "renew" {
  function_name    = "${local.name}-renew"
  role             = aws_iam_role.renew.arn
  runtime          = "python3.13"
  handler          = "functions.renew"
  filename         = data.archive_file.functions.output_path
  source_code_hash = data.archive_file.functions.output_base64sha256
  timeout          = 60
  environment { variables = { GROUP = aws_autoscaling_group.workers.name, TABLE = aws_dynamodb_table.leases.name, POOL = var.pool_id, DEPLOYMENT = var.deployment_id, MAX_NODES = tostring(var.max_nodes), SIGNING_SECRET = var.signing_secret_arn } }
}
resource "aws_lambda_function" "expiry" {
  function_name    = "${local.name}-expiry"
  role             = aws_iam_role.expiry.arn
  runtime          = "python3.13"
  handler          = "functions.expire"
  filename         = data.archive_file.functions.output_path
  source_code_hash = data.archive_file.functions.output_base64sha256
  timeout          = 60
  environment { variables = { GROUP = aws_autoscaling_group.workers.name, TABLE = aws_dynamodb_table.leases.name, POOL = var.pool_id, DEPLOYMENT = var.deployment_id } }
}
resource "aws_cloudwatch_event_rule" "expiry" {
  name                = "${local.name}-expiry"
  schedule_expression = "rate(5 minutes)"
}
resource "aws_cloudwatch_event_target" "expiry" {
  rule = aws_cloudwatch_event_rule.expiry.name
  arn  = aws_lambda_function.expiry.arn
}
resource "aws_lambda_permission" "expiry" {
  statement_id  = "CloudCapacityExpiry"
  action        = "lambda:InvokeFunction"
  function_name = aws_lambda_function.expiry.function_name
  principal     = "events.amazonaws.com"
  source_arn    = aws_cloudwatch_event_rule.expiry.arn
}
resource "aws_cloudwatch_metric_alarm" "expiry_failure" {
  alarm_name          = "${local.name}-expiry-failure"
  namespace           = "AWS/Lambda"
  metric_name         = "Errors"
  statistic           = "Sum"
  period              = 300
  evaluation_periods  = 1
  threshold           = 0
  comparison_operator = "GreaterThanThreshold"
  treat_missing_data  = "notBreaching"
  dimensions          = { FunctionName = aws_lambda_function.expiry.function_name }
  alarm_actions       = var.alarm_topic_arn == null ? [] : [var.alarm_topic_arn]
}
resource "aws_iam_role" "scaler" {
  name               = "${local.name}-scaler"
  assume_role_policy = local.trust_control
}
resource "aws_iam_role_policy" "scaler" {
  role = aws_iam_role.scaler.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["autoscaling:DescribeAutoScalingGroups", "autoscaling:DescribeScalingActivities", "autoscaling:DescribeInstanceRefreshes"], Resource = "*" },
    # The autoscaler can change desired capacity, but cannot reopen max/min after cloud expiry.
    { Effect = "Allow", Action = ["autoscaling:UpdateAutoScalingGroup"], Resource = aws_autoscaling_group.workers.arn, Condition = { Null = { "autoscaling:MaxSize" = "true", "autoscaling:MinSize" = "true" } } },
    { Effect = "Allow", Action = ["autoscaling:TerminateInstanceInAutoScalingGroup"], Resource = aws_autoscaling_group.workers.arn },
    # Preserve ownership tags used by independent expiry. The pinned plugin writes only lifecycle phase tags.
    { Effect = "Allow", Action = ["autoscaling:CreateOrUpdateTags"], Resource = aws_autoscaling_group.workers.arn,
    Condition = { "ForAllValues:StringLike" = { "aws:TagKeys" = ["nomad_autoscaler_lifecycle_phase_*"] }, Null = { "aws:TagKeys" = "false" } } }
  ] })
}
resource "aws_iam_role" "adapter" {
  name               = "${local.name}-adapter"
  assume_role_policy = local.trust_control
}
resource "aws_iam_role_policy" "adapter" {
  role = aws_iam_role.adapter.id
  policy = jsonencode({ Version = "2012-10-17", Statement = [
    { Effect = "Allow", Action = ["autoscaling:DescribeAutoScalingGroups", "autoscaling:DescribeScalingActivities"], Resource = "*" },
    { Effect = "Allow", Action = ["lambda:InvokeFunction"], Resource = aws_lambda_function.renew.arn }
  ] })
}

output "group_name" { value = aws_autoscaling_group.workers.name }
output "renew_function" { value = aws_lambda_function.renew.function_name }
output "scaler_role_arn" { value = aws_iam_role.scaler.arn }
output "adapter_role_arn" { value = aws_iam_role.adapter.arn }
output "expiry_function" { value = aws_lambda_function.expiry.function_name }

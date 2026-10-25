output "group_name" { value = google_compute_instance_group_manager.workers.name }
output "renew_url" { value = google_cloudfunctions2_function.capacity["renew"].service_config[0].uri }
output "expiry_url" { value = google_cloudfunctions2_function.capacity["expiry"].service_config[0].uri }
output "scaler_service_account" { value = google_service_account.roles["scaler"].email }
output "adapter_service_account" { value = google_service_account.roles["adapter"].email }
output "ownership" { value = local.ownership }
output "lease_bucket" { value = google_storage_bucket.leases.name }

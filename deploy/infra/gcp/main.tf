locals {
  name          = "lab-${substr(var.pool_id, 0, 22)}-${substr(sha256("${var.deployment_id}/${var.pool_id}"), 0, 8)}"
  role_suffix   = substr(sha256("${var.deployment_id}/${var.pool_id}"), 0, 12)
  ownership     = "eignex-lab:${var.deployment_id}:${var.pool_id}"
  lease_title   = "lab-capacity-${local.role_suffix}"
  source_bucket = "lab-${substr(var.project_id, 0, 20)}-${local.role_suffix}"
  node_config = {
    provider     = "gcp", pool = var.pool_id, nodeClass = var.node_class, datacenter = var.datacenter, servers = var.nomad_servers,
    workerImage  = var.worker_image, vcpus = var.vcpus, memoryMiB = var.memory_mib,
    architecture = var.architecture, minimumFeatures = var.minimum_features
  }
  function_env = {
    PROJECT       = var.project_id, ZONE = var.zone, GROUP = google_compute_instance_group_manager.workers.name,
    POOL          = var.pool_id, OWNERSHIP = local.ownership, BUCKET = google_storage_bucket.leases.name,
    MAX_NODES     = tostring(var.max_nodes), SIGNING_SECRET = var.signing_secret,
    SCALER_ROLE   = google_project_iam_custom_role.resize.name,
    SCALER_MEMBER = "serviceAccount:${google_service_account.roles["scaler"].email}", LEASE_TITLE = local.lease_title
  }
}
resource "google_project_service" "required" {
  for_each           = toset(["compute.googleapis.com", "iam.googleapis.com", "secretmanager.googleapis.com", "cloudfunctions.googleapis.com", "run.googleapis.com", "cloudbuild.googleapis.com", "artifactregistry.googleapis.com", "cloudscheduler.googleapis.com", "iamcredentials.googleapis.com", "storage.googleapis.com", "cloudresourcemanager.googleapis.com"])
  project            = var.project_id
  service            = each.key
  disable_on_destroy = false
}
resource "google_service_account" "roles" {
  for_each   = toset(["worker", "scaler", "adapter", "renew", "expiry", "schedule", "build"])
  project    = var.project_id
  account_id = "lab-${local.role_suffix}-${each.key}"
}
resource "google_secret_manager_secret_iam_member" "worker" {
  project   = var.project_id
  secret_id = var.bootstrap_secret
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.roles["worker"].email}"
}
resource "google_secret_manager_secret_iam_member" "signer" {
  project   = var.project_id
  secret_id = var.signing_secret
  role      = "roles/secretmanager.secretAccessor"
  member    = "serviceAccount:${google_service_account.roles["renew"].email}"
}
resource "google_service_account_iam_member" "control" {
  for_each           = toset(["adapter", "scaler"])
  service_account_id = google_service_account.roles[each.key].name
  role               = "roles/iam.serviceAccountTokenCreator"
  member             = var.control_member
}
resource "google_compute_instance_template" "worker" {
  project      = var.project_id
  name_prefix  = "${local.name}-"
  machine_type = var.machine_type
  tags         = var.network_tags
  labels       = { lab_deployment = var.deployment_id, lab_pool = var.pool_id }
  disk {
    source_image = var.image
    boot         = true
    auto_delete  = true
    disk_size_gb = 100
    disk_type    = "pd-balanced"
  }
  network_interface { subnetwork = var.subnetwork }
  service_account {
    email  = google_service_account.roles["worker"].email
    scopes = ["https://www.googleapis.com/auth/cloud-platform"]
  }
  scheduling {
    provisioning_model          = "SPOT"
    automatic_restart           = false
    on_host_maintenance         = "TERMINATE"
    instance_termination_action = "DELETE"
  }
  shielded_instance_config {
    enable_vtpm                 = true
    enable_integrity_monitoring = true
  }
  metadata = {
    block-project-ssh-keys = "true"
    enable-oslogin         = "true"
    startup-script         = templatefile("${path.module}/user-data.sh.tftpl", { config = jsonencode(local.node_config), secret = var.bootstrap_secret })
  }
  lifecycle { create_before_destroy = true }
  depends_on = [google_project_service.required]
}
resource "google_compute_instance_group_manager" "workers" {
  project            = var.project_id
  zone               = var.zone
  name               = local.name
  description        = local.ownership
  base_instance_name = local.name
  target_size        = 0
  version { instance_template = google_compute_instance_template.worker.id }
  update_policy {
    type                  = "OPPORTUNISTIC"
    minimal_action        = "REPLACE"
    max_surge_fixed       = 0
    max_unavailable_fixed = 1
  }
  lifecycle {
    ignore_changes = [target_size]
    precondition {
      condition     = var.dedicated_project && var.vcpus >= 1 && floor(var.vcpus) == var.vcpus && var.memory_mib > 1024 && var.max_nodes * var.vcpus <= var.max_vcpus
      error_message = "A finite homogeneous pool and dedicated project vCPU quota are required."
    }
    precondition {
      condition     = var.bootstrap_secret != var.signing_secret
      error_message = "Workers must not receive the capacity signing key."
    }
  }
}
resource "google_project_iam_custom_role" "read" {
  project     = var.project_id
  role_id     = "labCapacityRead${local.role_suffix}"
  title       = "Lab capacity inventory"
  permissions = ["compute.instanceGroupManagers.get", "compute.instanceGroupManagers.list", "compute.instances.get", "compute.instances.list", "compute.zones.get", "compute.zoneOperations.get"]
}
resource "google_project_iam_custom_role" "resize" {
  project     = var.project_id
  role_id     = "labCapacityResize${local.role_suffix}"
  title       = "Lab capacity leased resize"
  permissions = ["compute.instanceGroupManagers.update"]
}
resource "google_project_iam_custom_role" "lease_admin" {
  project     = var.project_id
  role_id     = "labCapacityLease${local.role_suffix}"
  title       = "Lab cloud-side capacity lease binding administration"
  permissions = ["resourcemanager.projects.getIamPolicy", "resourcemanager.projects.setIamPolicy"]
}
resource "google_project_iam_member" "read" {
  for_each = toset(["scaler", "adapter", "renew", "expiry"])
  project  = var.project_id
  role     = google_project_iam_custom_role.read.name
  member   = "serviceAccount:${google_service_account.roles[each.key].email}"
}
resource "google_project_iam_member" "scaler_lease" {
  project = var.project_id
  role    = google_project_iam_custom_role.resize.name
  member  = "serviceAccount:${google_service_account.roles["scaler"].email}"
  condition {
    title      = local.lease_title
    expression = "request.time < timestamp('1970-01-01T00:00:00Z')"
  }
  # Only the verified cloud renewal function advances this conditional deadline.
  lifecycle { ignore_changes = [condition] }
}
resource "google_project_iam_member" "expiry_resize" {
  project = var.project_id
  role    = google_project_iam_custom_role.resize.name
  member  = "serviceAccount:${google_service_account.roles["expiry"].email}"
}
resource "google_project_iam_member" "lease_admin" {
  project = var.project_id
  role    = google_project_iam_custom_role.lease_admin.name
  member  = "serviceAccount:${google_service_account.roles["renew"].email}"
}
resource "google_storage_bucket" "leases" {
  project                     = var.project_id
  name                        = "${local.source_bucket}-leases"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
}
resource "google_storage_bucket_iam_member" "lease_write" {
  bucket = google_storage_bucket.leases.name
  role   = "roles/storage.objectUser"
  member = "serviceAccount:${google_service_account.roles["renew"].email}"
}
resource "google_storage_bucket_iam_member" "lease_read" {
  bucket = google_storage_bucket.leases.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.roles["expiry"].email}"
}
resource "google_storage_bucket" "source" {
  project                     = var.project_id
  name                        = "${local.source_bucket}-source"
  location                    = var.region
  uniform_bucket_level_access = true
  public_access_prevention    = "enforced"
}
data "archive_file" "functions" {
  type        = "zip"
  output_path = "${path.module}/functions.zip"
  source {
    content  = file("${path.module}/main.py")
    filename = "main.py"
  }
  source {
    content  = file("${path.module}/../common/capacity_grants.py")
    filename = "capacity_grants.py"
  }
  source {
    content  = file("${path.module}/requirements.txt")
    filename = "requirements.txt"
  }
}
resource "google_storage_bucket_object" "source" {
  name   = "functions-${data.archive_file.functions.output_sha256}.zip"
  bucket = google_storage_bucket.source.name
  source = data.archive_file.functions.output_path
}
resource "google_storage_bucket_iam_member" "build_read" {
  bucket = google_storage_bucket.source.name
  role   = "roles/storage.objectViewer"
  member = "serviceAccount:${google_service_account.roles["build"].email}"
}
resource "google_artifact_registry_repository" "functions" {
  project       = var.project_id
  location      = var.region
  repository_id = "${local.name}-functions"
  format        = "DOCKER"
  depends_on    = [google_project_service.required]
}
resource "google_artifact_registry_repository_iam_member" "build_write" {
  project    = var.project_id
  location   = var.region
  repository = google_artifact_registry_repository.functions.name
  role       = "roles/artifactregistry.writer"
  member     = "serviceAccount:${google_service_account.roles["build"].email}"
}
resource "google_project_iam_member" "logs" {
  for_each = toset(["renew", "expiry", "build"])
  project  = var.project_id
  role     = "roles/logging.logWriter"
  member   = "serviceAccount:${google_service_account.roles[each.key].email}"
}
resource "google_cloudfunctions2_function" "capacity" {
  for_each = toset(["renew", "expiry"])
  project  = var.project_id
  location = var.region
  name     = "${local.name}-${each.key}"
  build_config {
    runtime           = "python313"
    entry_point       = each.key == "renew" ? "renew" : "expire"
    service_account   = google_service_account.roles["build"].id
    docker_repository = google_artifact_registry_repository.functions.id
    source {
      storage_source {
        bucket = google_storage_bucket.source.name
        object = google_storage_bucket_object.source.name
      }
    }
  }
  service_config {
    available_memory                 = "256M"
    timeout_seconds                  = 60
    max_instance_count               = 1
    max_instance_request_concurrency = 1
    service_account_email            = google_service_account.roles[each.key].email
    environment_variables            = local.function_env
    ingress_settings                 = "ALLOW_ALL" # Platform IAM requires a dedicated invoker identity; never grant allUsers.
    all_traffic_on_latest_revision   = true
  }
  depends_on = [google_project_service.required, google_storage_bucket_iam_member.build_read, google_artifact_registry_repository_iam_member.build_write, google_project_iam_member.logs, google_project_iam_member.scaler_lease]
}
resource "google_cloud_run_service_iam_member" "invoke" {
  for_each = { renew = "adapter", expiry = "schedule" }
  project  = var.project_id
  location = var.region
  service  = google_cloudfunctions2_function.capacity[each.key].name
  role     = "roles/run.invoker"
  member   = "serviceAccount:${google_service_account.roles[each.value].email}"
}
resource "google_cloud_scheduler_job" "expiry" {
  project          = var.project_id
  region           = var.region
  name             = "${local.name}-expiry"
  schedule         = "*/5 * * * *"
  time_zone        = "Etc/UTC"
  attempt_deadline = "90s"
  http_target {
    uri         = google_cloudfunctions2_function.capacity["expiry"].service_config[0].uri
    http_method = "POST"
    oidc_token {
      service_account_email = google_service_account.roles["schedule"].email
      audience              = google_cloudfunctions2_function.capacity["expiry"].service_config[0].uri
    }
  }
  depends_on = [google_cloud_run_service_iam_member.invoke]
}

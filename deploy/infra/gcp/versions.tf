terraform {
  required_version = ">= 1.10, < 2.0"
  required_providers {
    google  = { source = "hashicorp/google", version = "7.25.0" }
    archive = { source = "hashicorp/archive", version = "2.7.1" }
  }
}

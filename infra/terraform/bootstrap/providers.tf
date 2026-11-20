provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "whycode"
      Environment = "shared"
      ManagedBy   = "terraform"
    }
  }
}

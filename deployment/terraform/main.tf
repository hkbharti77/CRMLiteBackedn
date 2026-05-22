# CRMLite Multi-Region Infrastructure (AWS)

provider "aws" {
  alias  = "us_east"
  region = "us-east-1"
}

provider "aws" {
  alias  = "eu_central"
  region = "eu-central-1"
}

# --- Aurora Global Database ---

resource "aws_rds_global_cluster" "crmlite_global" {
  global_cluster_identifier = "crmlite-global-db"
  engine                    = "aurora-postgresql"
  engine_version            = "15.4"
  database_name             = "crmlite"
  storage_encrypted         = true
}

# Primary Cluster (US-East-1)
resource "aws_rds_cluster" "primary" {
  provider                  = aws.us_east
  cluster_identifier        = "crmlite-primary-cluster"
  engine                    = aws_rds_global_cluster.crmlite_global.engine
  engine_version            = aws_rds_global_cluster.crmlite_global.engine_version
  global_cluster_identifier = aws_rds_global_cluster.crmlite_global.id
  master_username           = "admin"
  master_password           = var.db_password
  db_subnet_group_name      = aws_db_subnet_group.us_east.name
  vpc_security_group_ids    = [aws_security_group.us_east.id]
  
  # Enable pgvector is usually done via DB parameter group
}

# Secondary Cluster (EU-Central-1)
resource "aws_rds_cluster" "secondary" {
  provider                  = aws.eu_central
  cluster_identifier        = "crmlite-secondary-cluster"
  engine                    = aws_rds_global_cluster.crmlite_global.engine
  engine_version            = aws_rds_global_cluster.crmlite_global.engine_version
  global_cluster_identifier = aws_rds_global_cluster.crmlite_global.id
  db_subnet_group_name      = aws_db_subnet_group.eu_central.name
  vpc_security_group_ids    = [aws_security_group.eu_central.id]
  
  depends_on = [aws_rds_cluster.primary]
}

# --- Regional Networking ---

# VPC Module (Conceptual)
module "vpc_us" {
  source = "./modules/vpc"
  providers = { aws = aws.us_east }
  region = "us-east-1"
  cidr   = "10.1.0.0/16"
}

module "vpc_eu" {
  source = "./modules/vpc"
  providers = { aws = aws.eu_central }
  region = "eu-central-1"
  cidr   = "10.2.0.0/16"
}

# --- Global Traffic Management (Anycast) ---

resource "aws_globalaccelerator_accelerator" "crmlite" {
  name            = "crmlite-global-accelerator"
  ip_address_type = "IPV4"
  enabled         = true
}

resource "aws_globalaccelerator_listener" "crmlite" {
  accelerator_arn = aws_globalaccelerator_accelerator.crmlite.id
  client_affinity = "SOURCE_IP"
  protocol        = "TCP"

  port_range {
    from_port = 80
    to_port   = 80
  }
  port_range {
    from_port = 443
    to_port   = 443
  }
}

resource "aws_globalaccelerator_endpoint_group" "us_east" {
  listener_arn = aws_globalaccelerator_listener.crmlite.id
  endpoint_group_region = "us-east-1"

  endpoint_configuration {
    endpoint_id = module.vpc_us.alb_arn # ALB in US
    weight      = 100
  }
}

resource "aws_globalaccelerator_endpoint_group" "eu_central" {
  listener_arn = aws_globalaccelerator_listener.crmlite.id
  endpoint_group_region = "eu-central-1"

  endpoint_configuration {
    endpoint_id = module.vpc_eu.alb_arn # ALB in EU
    weight      = 100
  }
}

# --- Route53 Health Checks ---

resource "aws_route53_health_check" "us_east" {
  fqdn              = "us-east.api.crmlite.com"
  port              = 443
  type              = "HTTPS"
  resource_path     = "/health"
  failure_threshold = "3"
  request_interval  = "30"
}

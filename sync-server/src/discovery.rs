use std::collections::HashMap;

use mdns_sd::{ServiceDaemon, ServiceInfo};

use crate::{RelayConfig, config::PROTOCOL_VERSION, error::RelayError};

pub const SERVICE_TYPE: &str = "_vaultnote-sync._tcp.local.";

pub struct DiscoveryAdvertisement {
    pub instance_name: String,
    pub host_name: String,
    pub port: u16,
    pub vault_id: String,
    pub certificate_sha256: String,
}

impl DiscoveryAdvertisement {
    pub fn from_config(config: &RelayConfig, port: u16) -> Self {
        Self {
            instance_name: format!("VaultNote {}", &config.vault_id[..8]),
            host_name: format!("{}.", config.tls.dns_name),
            port,
            vault_id: config.vault_id.clone(),
            certificate_sha256: config.tls.certificate_sha256.clone(),
        }
    }

    pub fn start(self) -> Result<DiscoveryGuard, RelayError> {
        let daemon = ServiceDaemon::new()?;
        let service = self.service_info()?;
        let fullname = service.get_fullname().to_owned();
        daemon.register(service)?;
        Ok(DiscoveryGuard { daemon, fullname })
    }

    pub fn service_info(&self) -> Result<ServiceInfo, RelayError> {
        let properties = HashMap::from([
            ("protocol".to_owned(), PROTOCOL_VERSION.to_string()),
            ("vault".to_owned(), self.vault_id.clone()),
            ("tls".to_owned(), "required".to_owned()),
            ("certSha256".to_owned(), self.certificate_sha256.clone()),
        ]);
        Ok(ServiceInfo::new(
            SERVICE_TYPE,
            &self.instance_name,
            &self.host_name,
            "",
            self.port,
            properties,
        )?
        .enable_addr_auto())
    }
}

pub struct DiscoveryGuard {
    daemon: ServiceDaemon,
    fullname: String,
}

impl Drop for DiscoveryGuard {
    fn drop(&mut self) {
        let _ = self.daemon.unregister(&self.fullname);
        let _ = self.daemon.shutdown();
    }
}

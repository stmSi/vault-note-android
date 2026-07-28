use std::time::{Duration, Instant};

use mdns_sd::{ServiceDaemon, ServiceEvent};
use serde::Serialize;

use crate::{error::AppError, sync_wire};

pub const SERVICE_TYPE: &str = "_vaultnote-sync._tcp.local.";

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DiscoveredRelay {
    pub instance_name: String,
    pub host_address: String,
    pub port: u16,
    pub vault_id: String,
    pub certificate_sha256: String,
}

pub fn discover_relays(timeout: Duration) -> Result<Vec<DiscoveredRelay>, AppError> {
    let daemon = ServiceDaemon::new().map_err(|_| AppError::NetworkUnavailable)?;
    let receiver = daemon
        .browse(SERVICE_TYPE)
        .map_err(|_| AppError::NetworkUnavailable)?;
    let deadline = Instant::now() + timeout;
    let mut relays = Vec::new();
    while let Some(remaining) = deadline.checked_duration_since(Instant::now()) {
        let Ok(event) = receiver.recv_timeout(remaining.min(Duration::from_millis(250))) else {
            continue;
        };
        let ServiceEvent::ServiceResolved(service) = event else {
            continue;
        };
        if service.get_property_val_str("protocol") != Some("3")
            || service.get_property_val_str("tls") != Some("required")
        {
            continue;
        }
        let Some(vault_id) = service
            .get_property_val_str("vault")
            .filter(|value| sync_wire::valid_id(value))
        else {
            continue;
        };
        let Some(certificate_sha256) = service
            .get_property_val_str("certSha256")
            .filter(|value| sync_wire::lower_hex_sha256(value))
        else {
            continue;
        };
        let Some(address) = service
            .get_addresses_v4()
            .into_iter()
            .find(|address| !address.is_unspecified() && !address.is_multicast())
        else {
            continue;
        };
        let relay = DiscoveredRelay {
            instance_name: service
                .get_fullname()
                .strip_suffix(SERVICE_TYPE)
                .unwrap_or(service.get_fullname())
                .trim_end_matches('.')
                .to_owned(),
            host_address: address.to_string(),
            port: service.get_port(),
            vault_id: vault_id.to_owned(),
            certificate_sha256: certificate_sha256.to_owned(),
        };
        if !relays.iter().any(|existing: &DiscoveredRelay| {
            existing.vault_id == relay.vault_id
                && existing.certificate_sha256 == relay.certificate_sha256
                && existing.host_address == relay.host_address
                && existing.port == relay.port
        }) {
            relays.push(relay);
        }
    }
    let _ = daemon.stop_browse(SERVICE_TYPE);
    let _ = daemon.shutdown();
    relays.sort_by(|left, right| {
        left.instance_name
            .cmp(&right.instance_name)
            .then_with(|| left.host_address.cmp(&right.host_address))
    });
    Ok(relays)
}

pub fn discover_matching(
    vault_id: &str,
    certificate_sha256: &str,
    timeout: Duration,
) -> Result<Option<DiscoveredRelay>, AppError> {
    Ok(discover_relays(timeout)?
        .into_iter()
        .find(|relay| relay.vault_id == vault_id && relay.certificate_sha256 == certificate_sha256))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn discovery_record_contains_no_credential_field() {
        let encoded = serde_json::to_string(&DiscoveredRelay {
            instance_name: "VaultNote test".to_owned(),
            host_address: "192.168.1.2".to_owned(),
            port: 8787,
            vault_id: "vault_test".to_owned(),
            certificate_sha256: "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
                .to_owned(),
        })
        .expect("record should serialize");
        assert!(!encoded.contains("token"));
        assert!(!encoded.contains("password"));
        assert!(!encoded.contains("key"));
    }
}

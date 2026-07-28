use std::{
    net::{IpAddr, Ipv4Addr, SocketAddr},
    num::NonZeroU16,
    path::PathBuf,
    time::Duration,
};

use axum_server::{Handle, tls_rustls::RustlsConfig};
use clap::{Parser, Subcommand};
use vaultnote_sync_server::{
    AppState, DiscoveryAdvertisement, Storage, initialize_relay, load_config,
    rotate_authentication_token, router,
};

#[derive(Parser)]
#[command(name = "vaultnote-sync-server")]
#[command(about = "Self-hostable opaque synchronization relay for VaultNote")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// Create a new relay identity and print its authentication token once.
    Init {
        #[arg(long, value_name = "ABSOLUTE_PATH")]
        data_directory: PathBuf,
    },
    /// Replace a lost or exposed authentication token without changing encrypted vault data.
    RotateToken {
        #[arg(long, value_name = "ABSOLUTE_PATH")]
        data_directory: PathBuf,
    },
    /// Run the HTTPS relay.
    Serve {
        #[arg(long, value_name = "ABSOLUTE_PATH")]
        data_directory: PathBuf,
        #[arg(long, default_value = "8787")]
        port: NonZeroU16,
        /// Listen on all interfaces and advertise through mDNS for local Wi-Fi/hotspot pairing.
        #[arg(long)]
        lan: bool,
        /// Bind to a specific IP. Ignored when --lan is used.
        #[arg(long, default_value_t = IpAddr::V4(Ipv4Addr::LOCALHOST))]
        listen: IpAddr,
    },
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    match Cli::parse().command {
        Command::Init { data_directory } => {
            let initialized = initialize_relay(&data_directory)?;
            println!("Relay identity initialized.");
            println!("Vault ID: {}", initialized.config.vault_id);
            println!(
                "TLS certificate SHA-256: {}",
                initialized.config.tls.certificate_sha256,
            );
            println!(
                "Authentication token (shown once): {}",
                initialized.authentication_token.as_str(),
            );
            println!("Keep the token private and separate from the sync encryption password.");
            let storage = Storage::open(&data_directory, &initialized.config)?;
            drop(storage);
            println!("Relay storage initialized.");
        }
        Command::RotateToken { data_directory } => {
            let authentication_token = rotate_authentication_token(&data_directory)?;
            println!("Authentication token rotated.");
            println!(
                "New authentication token (shown once): {}",
                authentication_token.as_str(),
            );
            println!("Previously issued authentication tokens are no longer accepted.");
        }
        Command::Serve {
            data_directory,
            port,
            lan,
            listen,
        } => {
            let port = port.get();
            let config = load_config(&data_directory)?;
            vaultnote_sync_server::config::verify_tls_identity(&data_directory, &config)?;
            let storage = Storage::open(&data_directory, &config)?;
            let address = SocketAddr::new(
                if lan {
                    IpAddr::V4(Ipv4Addr::UNSPECIFIED)
                } else {
                    listen
                },
                port,
            );
            let tls = RustlsConfig::from_pem_file(
                vaultnote_sync_server::config::tls_certificate_path(&data_directory),
                vaultnote_sync_server::config::tls_private_key_path(&data_directory),
            )
            .await?;
            let _discovery = if lan {
                Some(DiscoveryAdvertisement::from_config(&config, port).start()?)
            } else {
                None
            };
            let state = AppState::new(config.clone(), storage)?;
            let handle = Handle::new();
            let shutdown_handle = handle.clone();
            tokio::spawn(async move {
                shutdown_signal().await;
                shutdown_handle.graceful_shutdown(Some(Duration::from_secs(10)));
            });
            println!(
                "VaultNote sync relay listening at https://{}:{}",
                config.tls.dns_name, port,
            );
            if lan {
                println!(
                    "LAN discovery enabled as {} with no credentials in mDNS.",
                    vaultnote_sync_server::discovery::SERVICE_TYPE,
                );
            }
            axum_server::tls_rustls::bind_rustls(address, tls)
                .handle(handle)
                .serve(router(state).into_make_service())
                .await?;
        }
    }
    Ok(())
}

async fn shutdown_signal() {
    #[cfg(unix)]
    {
        let terminate = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate());
        if let Ok(mut terminate) = terminate {
            tokio::select! {
                _ = tokio::signal::ctrl_c() => {}
                _ = terminate.recv() => {}
            }
            return;
        }
    }
    let _ = tokio::signal::ctrl_c().await;
}
